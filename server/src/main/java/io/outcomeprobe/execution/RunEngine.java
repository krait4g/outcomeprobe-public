package io.outcomeprobe.execution;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.outcomeprobe.delivery.CallbackDeliveryRepository;
import io.outcomeprobe.evidence.AppendEvidenceCommand;
import io.outcomeprobe.evidence.EvidenceQueryPort;
import io.outcomeprobe.evidence.EvidenceRecorder;
import io.outcomeprobe.evidence.EvidenceSource;
import io.outcomeprobe.evidence.EvidenceType;
import io.outcomeprobe.emulator.AliasLockPort;
import io.outcomeprobe.emulator.EmulatorRunContext;
import io.outcomeprobe.emulator.ExpectedInvocation;
import io.outcomeprobe.emulator.ExpectedInvocationPort;
import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.foundation.IdGenerator;
import io.outcomeprobe.foundation.ProblemCode;
import io.outcomeprobe.profile.ProfileLookup;
import io.outcomeprobe.profile.TargetProfileSafeSnapshot;
import io.outcomeprobe.profile.VersionedTargetProfile;
import io.outcomeprobe.probe.ProbeAttemptIntent;
import io.outcomeprobe.probe.ProbeAttemptTransactionCoordinator;
import io.outcomeprobe.probe.ProbeRunFence;
import io.outcomeprobe.scenario.ArtifactDefinition;
import io.outcomeprobe.scenario.ArtifactImportException;
import io.outcomeprobe.scenario.ArtifactKind;
import io.outcomeprobe.scenario.ArtifactRepository;
import io.outcomeprobe.scenario.ScenarioMapper;
import io.outcomeprobe.scenario.contract.ProviderContract;
import io.outcomeprobe.scenario.definition.ScenarioDefinition;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scenario Run의 durable 실행을 조정하는 엔진이다.
 *
 * <p>책임: Run 생성·lease·Step 진행·최종 판정·alias 해제를 한 곳에서 조정한다.
 * 한 번의 lease에서 하나의 작업 단위만 처리해 thread·transaction 점유를 최소화한다.</p>
 * <p>핵심 불변식: terminal Run은 다시 실행 상태로 돌아가지 않는다. FAILED와
 * ERROR는 구분돼 보고된다. Provider commit·Evidence·상태 변경은 같은 트랜잭션에
 * 저장되고, 외부 HTTP 호출은 트랜잭션 밖에서 일어난다.</p>
 */
@Service
public class RunEngine {

    private static final Logger log = LoggerFactory.getLogger(RunEngine.class);
    private static final long LEASE_DURATION_MS = 10_000L;
    private static final long TICK_ADVANCE_MS = 50L;

    private final ScenarioRunPort runRepository;
    private final RunStepPort stepRepository;
    private final ArtifactRepository artifactRepository;
    private final ScenarioMapper scenarioMapper;
    private final ScenarioCompiler scenarioCompiler;
    private final ProfileLookup profileLookup;
    private final RunSnapshotIntegrity snapshotIntegrity;
    private final TransactionalRunStateCoordinator runStateCoordinator;
    private final ProbeAttemptTransactionCoordinator probeAttemptCoordinator;
    private final CallbackDeliveryRepository callbackDeliveryRepository;
    private final AliasLockPort aliasLockPort;
    private final ExpectedInvocationPort expectedInvocationPort;
    private final EvidenceRecorder evidenceRecorder;
    private final EvidenceQueryPort evidenceQueryPort;
    private final ClockProvider clockProvider;
    private final IdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final Map<StepType, StepExecutor> executors;
    private final io.outcomeprobe.infrastructure.metrics.MetricsService metricsService;
    private final int maxConcurrentRuns;
    private final String workerId;

    /**
     * 실행 엔진에 필요한 협업 객체를 주입한다.
     *
     * @param runRepository Run 저장소 포트
     * @param stepRepository Step 저장소 포트
     * @param artifactRepository Artifact 저장소
     * @param scenarioMapper Scenario·Contract 변환기
     * @param scenarioCompiler 실행 계획 컴파일러
     * @param profileLookup Profile 조회 포트
     * @param snapshotIntegrity Artifact snapshot 생성·검증기
     * @param runStateCoordinator Run·Step·Evidence·alias 원자 저장 경계
     * @param probeAttemptCoordinator Probe intent 조회·중단 원자 저장 경계
     * @param callbackDeliveryRepository Callback terminal 하위 작업 정리 포트
     * @param aliasLockPort alias 독점 포트
     * @param expectedInvocationPort expectation 포트
     * @param evidenceRecorder 증거 기록기
     * @param evidenceQueryPort 증거 조회 포트
     * @param clockProvider 시각 제공자
     * @param idGenerator 식별자 생성기
     * @param objectMapper JSON 변환 mapper
     * @param stepExecutors 등록된 Step 실행기 목록
     * @param metricsService 운영 메트릭 기록기
     * @param maxConcurrentRuns 동시 실행 Run 수 상한 (DoS 방어)
     */
    public RunEngine(ScenarioRunPort runRepository, RunStepPort stepRepository,
                     ArtifactRepository artifactRepository, ScenarioMapper scenarioMapper,
                     ScenarioCompiler scenarioCompiler, ProfileLookup profileLookup,
                     RunSnapshotIntegrity snapshotIntegrity,
                     TransactionalRunStateCoordinator runStateCoordinator,
                     ProbeAttemptTransactionCoordinator probeAttemptCoordinator,
                     CallbackDeliveryRepository callbackDeliveryRepository,
                     AliasLockPort aliasLockPort, ExpectedInvocationPort expectedInvocationPort,
                     EvidenceRecorder evidenceRecorder, EvidenceQueryPort evidenceQueryPort,
                     ClockProvider clockProvider, IdGenerator idGenerator,
                     ObjectMapper objectMapper, List<StepExecutor> stepExecutors,
                     io.outcomeprobe.infrastructure.metrics.MetricsService metricsService,
                     @org.springframework.beans.factory.annotation.Value(
                             "${outcomeprobe.limits.max-concurrent-runs:10}") int maxConcurrentRuns) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.artifactRepository = artifactRepository;
        this.scenarioMapper = scenarioMapper;
        this.scenarioCompiler = scenarioCompiler;
        this.profileLookup = profileLookup;
        this.snapshotIntegrity = snapshotIntegrity;
        this.runStateCoordinator = runStateCoordinator;
        this.probeAttemptCoordinator = probeAttemptCoordinator;
        this.callbackDeliveryRepository = callbackDeliveryRepository;
        this.aliasLockPort = aliasLockPort;
        this.expectedInvocationPort = expectedInvocationPort;
        this.evidenceRecorder = evidenceRecorder;
        this.evidenceQueryPort = evidenceQueryPort;
        this.clockProvider = clockProvider;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.executors = stepExecutors.stream()
                .collect(Collectors.toMap(StepExecutor::supports, Function.identity()));
        this.metricsService = metricsService;
        this.maxConcurrentRuns = maxConcurrentRuns;
        // PID만으로 workerId를 만들면 컨테이너(PID=1 고정)에서 여러 replica가 충돌한다.
        this.workerId = "worker-" + ProcessHandle.current().pid()
                + "-" + java.lang.management.ManagementFactory.getRuntimeMXBean().getName().hashCode();
    }

    /**
     * Scenario·Contract·Profile을 검증하고 Run을 QUEUED로 저장한다.
     *
     * <p>왜 생성 트랜잭션에 외부 호출을 포함하지 않는가: Run 생성은 입력 검증과
     * snapshot 고정만으로 완료돼야 DB 장애와 Target 장애를 분리할 수 있다.</p>
     *
     * @param request Run 생성 요청
     * @return 생성된 Run 식별자
     * @throws ArtifactImportException 참조·검증 실패 시 발생한다
     * @throws CapacityLimitExceededException 동시 실행 Run 수 상한 초과 시 발생한다
     */
    @Transactional
    public String createRun(CreateRunRequest request) {
        // DoS 방어: 동시 실행 Run 수 상한을 초과하면 접수를 거부한다.
        if (runRepository.countActive() >= maxConcurrentRuns) {
            throw new CapacityLimitExceededException(
                    "동시 실행 Run 수 상한을 초과했습니다: " + maxConcurrentRuns);
        }
        ArtifactDefinition scenarioArtifact = artifactRepository
                .findByIdentity(ArtifactKind.SCENARIO,
                        request.scenarioRef().name(), request.scenarioRef().version())
                .orElseThrow(() -> new ArtifactImportException(
                        ProblemCode.SCENARIO_REFERENCE_NOT_FOUND, "scenarioRef",
                        "Scenario를 찾을 수 없습니다: " + request.scenarioRef().name()
                                + "@" + request.scenarioRef().version()));
        ScenarioDefinition scenario = scenarioMapper.toScenario(scenarioArtifact.parsedJson());

        ArtifactDefinition contractArtifact = artifactRepository
                .findByIdentity(ArtifactKind.PROVIDER_CONTRACT,
                        scenario.contractRef().name(), scenario.contractRef().version())
                .orElseThrow(() -> new ArtifactImportException(
                        ProblemCode.SCENARIO_REFERENCE_NOT_FOUND, "contractRef",
                        "ProviderContract를 찾을 수 없습니다: " + scenario.contractRef().name()));
        ProviderContract contract = scenarioMapper.toContract(contractArtifact.parsedJson());
        scenarioMapper.validateReferences(scenario, contract);

        String targetProfileName = request.targetProfile() != null
                ? request.targetProfile()
                : scenario.targetProfileRef();
        if (targetProfileName == null) {
            throw new ArtifactImportException(ProblemCode.TARGET_PROFILE_NOT_FOUND, "targetProfile",
                    "Target Profile이 지정되지 않았습니다.");
        }
        VersionedTargetProfile profileDefinition = profileLookup.findVersionedByName(targetProfileName)
                .orElseThrow(() -> new ArtifactImportException(
                        ProblemCode.TARGET_PROFILE_NOT_FOUND, "targetProfile",
                        "Target Profile을 찾을 수 없습니다: " + targetProfileName));

        String runId = idGenerator.nextId();
        Instant now = clockProvider.now();
        ArtifactSnapshot scenarioSnapshot = snapshotIntegrity.snapshot(
                scenarioArtifact, ArtifactKind.SCENARIO);
        ArtifactSnapshot contractSnapshot = snapshotIntegrity.snapshot(
                contractArtifact, ArtifactKind.PROVIDER_CONTRACT);
        TargetProfileSafeSnapshot profileSnapshot = snapshotIntegrity.snapshot(profileDefinition);
        CompiledRunPlan plan = scenarioCompiler.compile(
                runId,
                scenarioSnapshot,
                scenario,
                contractSnapshot,
                contract,
                profileSnapshot,
                request.seed(), request.variables(), now);
        saveQueuedRun(plan, now);
        return runId;
    }

    /**
     * 기존 Run과 동일한 Scenario·Target·seed·변수로 새 Run을 생성한다.
     *
     * <p>왜 필요한가: 실패한 Run을 같은 조건으로 다시 돌려 재현·비교하려면
     * 원본 입력을 재구성해야 한다. 원본 Run의 snapshot에서 변수를 복원하므로
     * 재실행 결과가 결정론적으로 재현된다.</p>
     * <p>핵심 불변식: 원본 Run은 수정하지 않고 항상 새 Run을 만든다. terminal
     * Run은 재실행 상태로 전이되지 않는다(RUN-02).</p>
     *
     * @param sourceRunId 재실행의 기준이 될 원본 Run 식별자
     * @return 새로 생성된 Run 식별자
     * @throws ArtifactImportException 원본 Run이 없거나 참조 검증 실패 시 발생한다
     */
    @Transactional
    public String rerun(String sourceRunId) {
        ScenarioRun source = runRepository.findById(sourceRunId)
                .orElseThrow(() -> new ArtifactImportException(
                        ProblemCode.SCENARIO_REFERENCE_NOT_FOUND, "runId",
                        "재실행할 Run을 찾을 수 없습니다: " + sourceRunId));

        if (runRepository.countActive() >= maxConcurrentRuns) {
            throw new CapacityLimitExceededException(
                    "동시 실행 Run 수 상한을 초과했습니다: " + maxConcurrentRuns);
        }

        CompiledRunPlan sourcePlan = toPlan(source);
        snapshotIntegrity.verify(sourcePlan);
        ScenarioDefinition scenario = scenarioMapper.toScenario(
                sourcePlan.scenarioSnapshot().safeSpec());
        ProviderContract contract = scenarioMapper.toContract(
                sourcePlan.contractSnapshot().safeSpec());
        scenarioMapper.validateReferences(scenario, contract);

        String rerunId = idGenerator.nextId();
        Instant now = clockProvider.now();
        CompiledRunPlan rerunPlan = scenarioCompiler.compile(
                rerunId,
                sourcePlan.scenarioSnapshot(),
                scenario,
                sourcePlan.contractSnapshot(),
                contract,
                sourcePlan.targetProfileSnapshot(),
                sourcePlan.seed(),
                sourcePlan.variables(),
                now);
        saveQueuedRun(rerunPlan, now);
        return rerunId;
    }

    /**
     * 완성된 immutable plan을 QUEUED Run과 최초 Evidence로 저장한다.
     *
     * @param plan 저장할 v2 plan
     * @param now Run 생성 시각
     */
    private void saveQueuedRun(CompiledRunPlan plan, Instant now) {
        snapshotIntegrity.verify(plan);
        ScenarioRun run = new ScenarioRun(
                plan.runId(),
                plan.scenarioName(),
                plan.scenarioVersion(),
                plan.scenarioHash(),
                plan.targetProfileName(),
                plan.seed(),
                RunStatus.QUEUED,
                null,
                objectMapper.valueToTree(plan),
                now,
                now.plusMillis(plan.timeoutMs()),
                null,
                null,
                0,
                0,
                false,
                null,
                null,
                null,
                null,
                now);
        runRepository.save(run);
        evidenceRecorder.append(AppendEvidenceCommand
                .of(plan.runId(), EvidenceType.RUN_QUEUED, EvidenceSource.ENGINE)
                .withAttribute("scenario", plan.scenarioName() + "@" + plan.scenarioVersion())
                .withAttribute("seed", String.valueOf(plan.seed())));
    }

    /**
     * 기한이 도래한 Run을 lease해 한 단위 작업을 처리한다.
     *
     * <p>왜 scheduled polling인가: WAITING Step과 예정된 재평가는 thread sleep 없이
     * nextActionAt 기반으로 재개돼야 하며, lease 만료는 process crash 복구 경로다.</p>
     */
    @Scheduled(fixedDelay = 250L)
    public void pollAndProcess() {
        try {
            runRepository.leaseNextDueRun(workerId, clockProvider.now(), LEASE_DURATION_MS)
                    .ifPresent(this::processOne);
        } catch (ArtifactImportException e) {
            // 입력 오류는 영구 실패이므로 lease된 Run을 ERROR로 확정해 무한 재시도를 막는다.
            log.warn("run 처리 중 입력 오류가 발생했습니다: {}", e.getMessage());
        } catch (Exception e) {
            // 엔진 자체 결함은 Run ERROR로 보고하고 Worker는 계속 동작한다.
            log.error("run 처리 중 내부 오류가 발생했습니다.", e);
        }
    }

    /**
     * lease된 Run 하나를 현재 상태에 따라 준비·진행·종료 경로로 분기한다.
     * deadline 초과 Run은 실패로, 취소 요청 Run은 CANCELLED로 확정한다.
     */
    private void processOne(ScenarioRun run) {
        CompiledRunPlan plan;
        try {
            plan = toPlan(run);
            snapshotIntegrity.verify(plan);
        } catch (RuntimeException exception) {
            runStateCoordinator.commit(
                    () -> failCorruptedRunWithoutPlan(run, exception.getMessage()));
            return;
        }

        if (run.cancelRequested() && !run.status().isTerminal()) {
            runStateCoordinator.commit(() -> cancelRun(run, plan));
            return;
        }
        if (run.deadlineAt() != null && clockProvider.now().isAfter(run.deadlineAt())) {
            runStateCoordinator.commit(() -> failRun(
                    run, plan, ProblemCode.SCENARIO_TIMEOUT_EXCEEDED,
                    "Run 전체 제한 시간을 초과했습니다.", null));
            return;
        }

        switch (run.status()) {
            case QUEUED -> runStateCoordinator.commit(() -> prepareRun(run, plan));
            case PREPARING, RUNNING, ASSERTING -> advanceRun(run, plan);
            default -> {
                // terminal Run은 다시 처리하지 않는다.
            }
        }
    }

    /**
     * alias 독점을 획득하고 Step 행을 생성해 Run을 RUNNING으로 전이한다.
     * alias 경합은 deadline까지 재큐로 흡수하고, deadline 이후에만 BUSY로 실패한다.
     */
    private void prepareRun(ScenarioRun run, CompiledRunPlan plan) {
        ProviderContract contract = loadContract(plan);
        EmulatorRunContext context = new EmulatorRunContext(
                run.id(), plan.emulatorAlias(), plan.seed(), contract);

        // alias 독점은 부분 unique index로 보호된다. 실패는 다른 Run이 사용 중임을 의미한다.
        boolean acquired = aliasLockPort.tryAcquire(plan.emulatorAlias(), run.id(), context);
        if (!acquired) {
            // 즉시 실패하지 않고 deadline까지 재큐해 이전 Run의 release를 흡수한다(OP-AUDIT-CORE-002).
            if (run.deadlineAt() != null
                    && clockProvider.now().plusSeconds(2).isBefore(run.deadlineAt())) {
                updateRun(run, RunStatus.QUEUED, clockProvider.now().plusSeconds(1), null, null,
                        run.startedAt(), null, null);
                return;
            }
            failRun(run, plan, ProblemCode.PROVIDER_ALIAS_BUSY,
                    "Emulator alias가 다른 Run에 의해 사용 중입니다: " + plan.emulatorAlias(), null);
            return;
        }

        for (CompiledRunPlan.CompiledStep step : plan.steps()) {
            stepRepository.save(new RunStepExecution(
                    idGenerator.nextId(),
                    run.id(),
                    step.id(),
                    step.ordinal(),
                    step.type(),
                    StepStatus.PENDING,
                    step.detail(),
                    null,
                    null,
                    null,
                    run.deadlineAt(),
                    0,
                    null,
                    null,
                    0,
                    null,
                    null));
        }

        evidenceRecorder.append(AppendEvidenceCommand
                .of(run.id(), EvidenceType.RUN_STARTED, EvidenceSource.ENGINE)
                .withAttribute("scenario", plan.scenarioName() + "@" + plan.scenarioVersion())
                .withAttribute("seed", String.valueOf(plan.seed()))
                .withAttribute("targetProfile", plan.targetProfileName()));

        metricsService.runStarted(plan.scenarioName());

        updateRun(run, RunStatus.RUNNING, clockProvider.now(), null, null,
                clockProvider.now(), null, 0);
    }

    /**
     * 터미널이 아닌 첫 Step을 실행하고 결과로 Run을 갱신한다.
     * 모든 Step이 종료되면 최종 판정(finalize)으로 넘긴다.
     */
    private void advanceRun(ScenarioRun run, CompiledRunPlan plan) {
        List<RunStepExecution> steps = stepRepository.findByRunId(run.id());
        RunStepExecution current = steps.stream()
                .filter(step -> !step.status().isTerminal())
                .findFirst()
                .orElse(null);

        if (current == null) {
            runStateCoordinator.commit(() -> finalizeRun(run, plan));
            return;
        }

        StepExecutor executor = executors.get(current.stepType());
        if (executor == null) {
            runStateCoordinator.commit(() -> errorRun(run, plan,
                    ProblemCode.INTERNAL_EXECUTION_ERROR,
                    "Step 실행기를 찾을 수 없습니다: " + current.stepType()));
            return;
        }

        // 원격 HTTP를 포함할 수 있는 Step 실행은 트랜잭션 밖에서 끝낸 뒤 결과만 원자 저장한다.
        StepExecutionResult executed = executor.execute(new StepExecutionContext(run, plan, current));
        if (executed instanceof StepExecutionResult.Transactional transactional) {
            // 외부 호출이 없는 DB-only Step은 계산·Evidence·Step·Run CAS 전체를 한 번에 commit한다.
            runStateCoordinator.commit(() -> {
                StepExecutionResult result = transactional.execution().get();
                rejectNestedDeferredResult(result);
                commitStepResult(run, plan, current, result, clockProvider.now());
            });
            return;
        }
        StepExecutionResult.Deferred deferred = executed instanceof StepExecutionResult.Deferred value
                ? value : null;
        StepExecutionResult result = deferred == null ? executed : deferred.result();
        Instant now = clockProvider.now();

        runStateCoordinator.commit(() -> {
            if (deferred != null) {
                // 외부 호출이 끝난 DB-only 결과를 Step·Run CAS와 같은 트랜잭션에 먼저 넣는다.
                deferred.persistence().run();
            }
            commitStepResult(run, plan, current, result, now);
        });
        if (deferred != null) {
            deferred.afterCommit().run();
        }
    }

    /**
     * 외부 실행이 끝난 Step 결과와 Run·Evidence 상태를 현재 트랜잭션에 함께 반영한다.
     *
     * <p>CAS 실패 예외를 잡지 않아 Step만 바뀌거나 Evidence만 남는 부분 commit을
     * 방지한다.</p>
     *
     * @param run lease 시점 Run snapshot
     * @param plan 고정 실행 계획
     * @param current 갱신할 현재 Step
     * @param result 트랜잭션 밖에서 계산된 실행 결과
     * @param now 결과 확정 시각
     */
    private void commitStepResult(ScenarioRun run, CompiledRunPlan plan,
                                  RunStepExecution current, StepExecutionResult result,
                                  Instant now) {
        switch (result) {
            case StepExecutionResult.Transactional ignored -> throw new IllegalStateException(
                    "중첩 Transactional Step 결과는 저장할 수 없습니다.");
            case StepExecutionResult.Deferred ignored -> throw new IllegalStateException(
                    "중첩 Deferred Step 결과는 저장할 수 없습니다.");
            case StepExecutionResult.Completed completed -> {
                updateStep(current, StepStatus.PASSED, completed.output(), null, now, null);
                evidenceRecorder.append(AppendEvidenceCommand
                        .of(run.id(), EvidenceType.STEP_PASSED, EvidenceSource.ENGINE)
                        .withCorrelation(current.stepId(), null, null)
                        .withAttribute("stepType", current.stepType().name()));
                updateRun(run, RunStatus.RUNNING, now.plusMillis(TICK_ADVANCE_MS), null, null,
                        run.startedAt(), null, current.ordinal());
            }
            case StepExecutionResult.Waiting waiting -> {
                updateStep(current, StepStatus.WAITING, null, waiting.observation(), now, null);
                if (current.attemptCount() == 0) {
                    evidenceRecorder.append(AppendEvidenceCommand
                            .of(run.id(), EvidenceType.STEP_WAITING, EvidenceSource.ENGINE)
                            .withCorrelation(current.stepId(), null, null)
                            .withAttribute("stepType", current.stepType().name()));
                }
                // WAITING Step은 thread를 점유하지 않고 nextActionAt에 재 lease된다.
                updateRun(run, RunStatus.RUNNING, waiting.nextActionAt(), null, null,
                        run.startedAt(), null, current.ordinal());
            }
            case StepExecutionResult.Failed failed -> {
                updateStep(current, StepStatus.FAILED, null, null, now, failed.problemCode().name());
                evidenceRecorder.append(AppendEvidenceCommand
                        .of(run.id(), EvidenceType.STEP_FAILED, EvidenceSource.ENGINE)
                        .withCorrelation(current.stepId(), null, null)
                        .withAttribute("problemCode", failed.problemCode().name()));
                failRun(run, plan, failed.problemCode(), failed.detail(), failed.detailJson());
            }
            case StepExecutionResult.Errored errored -> {
                updateStep(current, StepStatus.ERROR, null, null, now, errored.problemCode().name());
                errorRun(run, plan, errored.problemCode(), errored.detail());
            }
        }
    }

    /**
     * 트랜잭션 안에서 계산된 결과가 다시 지연 작업을 요구하지 않는지 검증한다.
     *
     * @param result DB-only Step이 계산한 최종 결과
     */
    private void rejectNestedDeferredResult(StepExecutionResult result) {
        if (result instanceof StepExecutionResult.Deferred
                || result instanceof StepExecutionResult.Transactional) {
            throw new IllegalStateException("중첩 지연 Step 결과는 저장할 수 없습니다.");
        }
    }

    /**
     * Provider expectation 충족 여부를 확인해 최종 판정한다.
     * 미충족 expectation이 있으면 FAILED, 없으면 PASSED로 확정한다.
     */
    private void finalizeRun(ScenarioRun run, CompiledRunPlan plan) {
        // 최종 expectation 충족 여부는 Run 판정의 필수 조건이다.
        List<ExpectedInvocation> unsatisfied = expectedInvocationPort.unsatisfied(run.id());
        if (!unsatisfied.isEmpty()) {
            ObjectNode detail = objectMapper.createObjectNode();
            unsatisfied.forEach(invocation -> detail.put(
                    invocation.stepId() + "." + invocation.operationKey(),
                    "expected >= " + invocation.minimumCount() + " but was " + invocation.matchedCount()));
            failRun(run, plan, ProblemCode.PROVIDER_EXPECTATION_UNSATISFIED,
                    "충족되지 않은 Provider expectation이 있습니다.", detail);
            return;
        }
        passRun(run, plan);
    }

    /**
     * RUN_PASSED 증거를 기록하고 alias 독점을 해제한 뒤 Run을 종료한다.
     */
    private void passRun(ScenarioRun run, CompiledRunPlan plan) {
        evidenceRecorder.append(AppendEvidenceCommand
                .of(run.id(), EvidenceType.RUN_PASSED, EvidenceSource.ENGINE));
        aliasLockPort.release(plan.emulatorAlias(), run.id());
        recordRunCompleted(plan, "PASSED", run);
        updateRun(run, RunStatus.PASSED, null, null, null,
                run.startedAt(), clockProvider.now(), run.currentStepOrdinal());
    }

    /**
     * 남은 Step을 건너뛰고 RUN_FAILED를 기록한 뒤 alias를 해제하고 Run을 종료한다.
     * Target 업무 상태·상호작용 불일치를 나타내며 OutcomeProbe 내부 오류(ERROR)와 구분된다.
     */
    private void failRun(ScenarioRun run, CompiledRunPlan plan, ProblemCode code,
                         String detail, JsonNode detailJson) {
        ObjectNode problem = detailJson instanceof ObjectNode objectNode
                ? objectNode
                : objectMapper.createObjectNode();
        problem.put("message", detail);
        if (code == ProblemCode.SCENARIO_TIMEOUT_EXCEEDED) {
            interruptCurrentProbeAttempt(run, ProbeRunFence.Boundary.DEADLINE_INTERRUPT);
        }
        updateRun(run, RunStatus.FAILED, null, code.name(), problem,
                run.startedAt(), clockProvider.now(), run.currentStepOrdinal());
        if (code == ProblemCode.SCENARIO_TIMEOUT_EXCEEDED) {
            cancelPendingCallbacks(run.id());
            failCurrentStepAndSkipRemainingSteps(run.id(), code);
        } else {
            skipRemainingSteps(run.id());
        }
        evidenceRecorder.append(AppendEvidenceCommand
                .of(run.id(), EvidenceType.RUN_FAILED, EvidenceSource.ENGINE)
                .withAttribute("problemCode", code.name()));
        aliasLockPort.release(plan.emulatorAlias(), run.id());
        recordRunCompleted(plan, "FAILED", run);
    }

    /**
     * OutcomeProbe 내부 오류를 RUN_ERROR로 기록하고 alias를 해제한 뒤 Run을 종료한다.
     */
    private void errorRun(ScenarioRun run, CompiledRunPlan plan, ProblemCode code, String detail) {
        ObjectNode problem = objectMapper.createObjectNode();
        problem.put("message", detail);
        updateRun(run, RunStatus.ERROR, null, code.name(), problem,
                run.startedAt(), clockProvider.now(), run.currentStepOrdinal());
        skipRemainingSteps(run.id());
        cancelPendingCallbacks(run.id());
        evidenceRecorder.append(AppendEvidenceCommand
                .of(run.id(), EvidenceType.RUN_ERROR, EvidenceSource.ENGINE)
                .withAttribute("problemCode", code.name())
                .withAttribute("faultOrigin", "OUTCOMEPROBE_INTERNAL"));
        aliasLockPort.release(plan.emulatorAlias(), run.id());
        recordRunCompleted(plan, "ERROR", run);
    }

    /**
     * 남은 Step을 건너뛰고 RUN_CANCELLED를 기록한 뒤 Run을 종료한다.
     * 이미 외부로 나간 요청은 되돌리지 않는다.
     */
    private void cancelRun(ScenarioRun run, CompiledRunPlan plan) {
        interruptCurrentProbeAttempt(run, ProbeRunFence.Boundary.CANCEL_INTERRUPT);
        updateRun(run, RunStatus.CANCELLED, null, null, null,
                run.startedAt(), clockProvider.now(), run.currentStepOrdinal());
        skipRemainingSteps(run.id());
        cancelPendingCallbacks(run.id());
        evidenceRecorder.append(AppendEvidenceCommand
                .of(run.id(), EvidenceType.RUN_CANCELLED, EvidenceSource.ENGINE));
        aliasLockPort.release(plan.emulatorAlias(), run.id());
        recordRunCompleted(plan, "CANCELLED", run);
    }

    /**
     * 취소·전역 deadline이 실행기를 선점할 때 현재 Probe의 미확정 intent를 함께 종결한다.
     *
     * <p>새 intent를 만들지 않고 현재 비종결 Step의 다음 attempt identity만 조회한다.
     * 실제 전송 여부를 알 수 없는 기존 in-flight 행만 UNKNOWN 중단 Evidence와 함께 닫아,
     * terminal Run에 PLANNED Evidence와 빈 intent가 영구 잔존하지 않게 한다.</p>
     *
     * @param run terminal 상태로 전이할 Run snapshot
     */
    private void interruptCurrentProbeAttempt(ScenarioRun run,
                                              ProbeRunFence.Boundary boundary) {
        RunStepExecution current = stepRepository.findByRunId(run.id()).stream()
                .filter(step -> !step.status().isTerminal())
                .findFirst()
                .orElse(null);
        if (current == null || current.stepType() != StepType.PROBE_EVENTUALLY) {
            return;
        }

        ProbeAttemptIntent candidate = new ProbeAttemptIntent(null, run.id(),
                current.stepId(), current.attemptCount() + 1, clockProvider.now());
        JsonNode input = current.inputSnapshot();
        String path = input == null
                ? "" : input.path("request").path("path").asText("");
        // 중단 행·Evidence·Step·Run terminal 전이가 하나라도 실패하면 모두 rollback돼야 한다.
        probeAttemptCoordinator.interruptExisting(
                ProbeRunFenceFactory.at(run, clockProvider.now(), boundary), candidate, path);
    }

    /**
     * Run 종료 메트릭을 기록한다. startedAt이 있으면 소요 시간을 계산한다.
     */
    private void recordRunCompleted(CompiledRunPlan plan, String status, ScenarioRun run) {
        java.time.Duration duration = run.startedAt() != null
                ? java.time.Duration.between(run.startedAt(), clockProvider.now())
                : java.time.Duration.ZERO;
        metricsService.runCompleted(plan.scenarioName(), status, duration);
    }

    /**
     * 터미널이 아닌 모든 Step을 SKIPPED로 전이시킨다.
     */
    private void skipRemainingSteps(String runId) {
        Instant now = clockProvider.now();
        stepRepository.findByRunId(runId).stream()
                .filter(step -> !step.status().isTerminal())
                .forEach(step -> updateStep(step, StepStatus.SKIPPED, null, null, now, null));
    }

    /**
     * Run 전체 deadline에 걸린 현재 Step을 실패시키고 이후 비종결 Step을 건너뛴다.
     *
     * <p>Run Step 조회는 ordinal 정렬 계약을 따르므로 첫 비종결 Step만 실제로 deadline을
     * 맞은 현재 Step이다. 나머지는 실행되지 않은 상태로 보존해야 하므로 실패로 부풀리지 않고
     * SKIPPED로 닫는다. Step과 Run의 terminal 전이는 호출자의 같은 transaction에서 확정된다.</p>
     *
     * @param runId deadline을 초과한 Run 식별자
     * @param problemCode 현재 Step에 기록할 전체 deadline 문제 코드
     */
    private void failCurrentStepAndSkipRemainingSteps(String runId, ProblemCode problemCode) {
        Instant now = clockProvider.now();
        List<RunStepExecution> remaining = stepRepository.findByRunId(runId).stream()
                .filter(step -> !step.status().isTerminal())
                .toList();
        if (remaining.isEmpty()) {
            return;
        }

        RunStepExecution current = remaining.getFirst();
        updateStep(current, StepStatus.FAILED, null, null, now, problemCode.name());
        evidenceRecorder.append(AppendEvidenceCommand
                .of(runId, EvidenceType.STEP_FAILED, EvidenceSource.ENGINE)
                .withCorrelation(current.stepId(), null, null)
                .withAttribute("problemCode", problemCode.name()));
        remaining.stream().skip(1)
                .forEach(step -> updateStep(step, StepStatus.SKIPPED, null, null, now, null));
    }

    /**
     * Step 상태를 optimistic version으로 갱신한다. CAS 실패는 lease 상실을 의미한다.
     */
    private void updateStep(RunStepExecution step, StepStatus status, JsonNode output,
                            JsonNode observation, Instant now, String problemCode) {
        RunStepExecution updated = new RunStepExecution(
                step.id(),
                step.runId(),
                step.stepId(),
                step.ordinal(),
                step.stepType(),
                status,
                step.inputSnapshot(),
                output != null ? output : step.outputSnapshot(),
                step.waitStartedAt() != null ? step.waitStartedAt()
                        : (status == StepStatus.WAITING ? now : null),
                step.nextActionAt(),
                step.deadlineAt(),
                step.attemptCount() + 1,
                observation != null ? observation : step.lastObservation(),
                problemCode != null ? problemCode : step.problemCode(),
                step.version(),
                step.startedAt() != null ? step.startedAt() : now,
                status.isTerminal() ? now : null);
        runStateCoordinator.updateStep(updated);
    }

    /**
     * Run 상태를 optimistic version으로 갱신하고 lease를 해제한다.
     * CAS 실패는 다른 Worker가 Run을 인수했음을 의미하므로 현재 트랜잭션을 실패시킨다.
     */
    private void updateRun(ScenarioRun run, RunStatus status, Instant nextActionAt,
                           String problemCode, JsonNode problemDetail, Instant startedAt,
                           Instant finishedAt, Integer currentStepOrdinal) {
        ScenarioRun updated = new ScenarioRun(
                run.id(),
                run.scenarioName(),
                run.scenarioVersion(),
                run.scenarioHash(),
                run.targetProfileName(),
                run.seed(),
                status,
                currentStepOrdinal != null ? currentStepOrdinal : run.currentStepOrdinal(),
                run.compiledPlan(),
                nextActionAt,
                run.deadlineAt(),
                null,
                null,
                run.version(),
                run.nextEvidenceSequence(),
                run.cancelRequested(),
                problemCode != null ? problemCode : run.problemCode(),
                problemDetail != null ? problemDetail : run.problemDetail(),
                startedAt != null ? startedAt : run.startedAt(),
                finishedAt,
                run.createdAt());
        runStateCoordinator.updateRun(updated);
    }

    /**
     * JSONB로 저장된 컴파일 계획을 역직렬화한다.
     */
    private CompiledRunPlan toPlan(ScenarioRun run) {
        try {
            return objectMapper.treeToValue(run.compiledPlan(), CompiledRunPlan.class);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("compiled plan 해석에 실패했습니다.", e);
        }
    }

    /**
     * 계획에 고정된 Provider 계약 snapshot을 타입 안전 모델로 복원한다.
     *
     * @param plan 실행 계획
     * @return 고정된 Provider 계약
     */
    private ProviderContract loadContract(CompiledRunPlan plan) {
        return scenarioMapper.toContract(plan.contractSnapshot().safeSpec());
    }

    /**
     * 신뢰할 수 없는 compiled plan을 사용하거나 alias를 release하지 않고 Run을 ERROR로 끝낸다.
     *
     * <p>plan JSON 자체가 손상됐을 수 있으므로 emulatorAlias·Step 입력에는 접근하지 않는다.
     * 이 경로가 없으면 lease 만료 뒤 같은 손상 Run이 무한 재처리된다.</p>
     *
     * @param run 손상된 계획을 가진 Run
     * @param detail 역직렬화·무결성 실패 상세
     */
    private void failCorruptedRunWithoutPlan(ScenarioRun run, String detail) {
        ObjectNode problem = objectMapper.createObjectNode();
        problem.put("message", detail == null ? "compiled plan snapshot이 손상되었습니다." : detail);
        updateRun(run, RunStatus.ERROR, null,
                ProblemCode.ARTIFACT_SNAPSHOT_CORRUPTED.name(), problem,
                run.startedAt(), clockProvider.now(), run.currentStepOrdinal());
        skipRemainingSteps(run.id());
        cancelPendingCallbacks(run.id());
        evidenceRecorder.append(AppendEvidenceCommand
                .of(run.id(), EvidenceType.RUN_ERROR, EvidenceSource.ENGINE)
                .withAttribute("problemCode", ProblemCode.ARTIFACT_SNAPSHOT_CORRUPTED.name())
                .withAttribute("faultOrigin", "OUTCOMEPROBE_INTERNAL"));
    }

    /**
     * 아직 외부 전송을 시작하지 않은 Callback을 Run terminal transaction에서 취소한다.
     *
     * @param runId terminal 전환할 Run 식별자
     */
    private void cancelPendingCallbacks(String runId) {
        callbackDeliveryRepository.cancelPendingForRun(runId, clockProvider.now());
    }

    /**
     * Run 취소를 요청한다.
     *
     * <p>취소는 Worker가 다음 안전 경계에서 반영한다. 이미 전송된 외부 요청은
     * 되돌리지 않는다.</p>
     *
     * @param runId 취소할 Run 식별자
     * @return 요청 수락 여부
     */
    @Transactional
    public boolean requestCancel(String runId) {
        return runRepository.findById(runId)
                .filter(run -> !run.status().isTerminal())
                .map(run -> {
                    ScenarioRun updated = new ScenarioRun(
                            run.id(), run.scenarioName(), run.scenarioVersion(), run.scenarioHash(),
                            run.targetProfileName(), run.seed(), run.status(), run.currentStepOrdinal(),
                            run.compiledPlan(), run.nextActionAt() == null
                                    ? clockProvider.now() : run.nextActionAt(),
                            run.deadlineAt(), run.leaseOwner(), run.leaseUntil(), run.version(),
                            run.nextEvidenceSequence(), true, run.problemCode(), run.problemDetail(),
                            run.startedAt(), run.finishedAt(), run.createdAt());
                    return runRepository.update(updated);
                })
                .orElse(false);
    }

    /**
     * Run을 식별자로 조회한다.
     *
     * @param runId Run 식별자
     * @return 존재하면 Run
     */
    public java.util.Optional<ScenarioRun> findRun(String runId) {
        return runRepository.findById(runId);
    }

    /**
     * 최근 Run을 생성 시각 역순으로 조회한다.
     *
     * @param limit 최대 조회 건수
     * @return Run 목록
     */
    public java.util.List<ScenarioRun> findRecentRuns(int limit) {
        return runRepository.findRecent(limit);
    }

    /**
     * Run의 Step 실행 목록을 조회한다.
     *
     * @param runId Run 식별자
     * @return ordinal 정렬된 Step 목록
     */
    public List<RunStepExecution> findSteps(String runId) {
        return stepRepository.findByRunId(runId);
    }

    /**
     * Run의 Evidence Timeline을 조회한다.
     *
     * @param runId Run 식별자
     * @return sequence 정렬된 Evidence 목록
     */
    public java.util.List<io.outcomeprobe.evidence.EvidenceEvent> timeline(String runId) {
        return evidenceQueryPort.timeline(runId);
    }
}
