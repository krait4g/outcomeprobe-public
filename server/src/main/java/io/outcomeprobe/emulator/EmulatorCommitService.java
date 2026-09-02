package io.outcomeprobe.emulator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.ObjectMapper;
import io.outcomeprobe.evidence.AppendEvidenceCommand;
import io.outcomeprobe.evidence.EvidenceRecorder;
import io.outcomeprobe.evidence.EvidenceSource;
import io.outcomeprobe.evidence.EvidenceType;
import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.foundation.SeedGenerator;
import io.outcomeprobe.scenario.contract.ProviderContract;
import io.outcomeprobe.scenario.definition.DslDurations;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provider operation의 상태 commit과 증거 기록을 원자화하는 트랜잭션 서비스다.
 *
 * <p>책임: expectation 횟수 증가, resource version CAS, idempotency receipt 저장,
 * PROVIDER_OPERATION_COMMITTED evidence append를 한 트랜잭션으로 묶어
 * "commit됐는데 증거가 없거나" 그 반대인 상태를 만들지 않는다.</p>
 * <p>핵심 불변식: HTTP 응답 지연·쓰기는 이 서비스 밖에서 일어난다. Provider
 * truth 확정은 응답 전달 여부와 무관하다.</p>
 */
@Service
public class EmulatorCommitService {

    private final ProviderStatePort providerStatePort;
    private final ExpectedInvocationPort expectedInvocationPort;
    private final InvocationReceiptPort invocationReceiptPort;
    private final EvidenceRecorder evidenceRecorder;
    private final SeedGenerator seedGenerator;
    private final ClockProvider clockProvider;
    private final TemplateRenderer templateRenderer;
    private final ObjectMapper objectMapper;
    private final io.outcomeprobe.infrastructure.metrics.MetricsService metricsService;

    /**
     * commit 조정에 필요한 협업 객체를 주입한다.
     *
     * @param providerStatePort Provider 자원 상태 포트
     * @param expectedInvocationPort expectation 매칭 포트
     * @param invocationReceiptPort idempotency 영수증 포트
     * @param evidenceRecorder 증거 기록기
     * @param seedGenerator 결정론 값 생성기
     * @param clockProvider 관측 시각 제공자
     * @param templateRenderer 응답 template 렌더러
     * @param objectMapper JSON 변환 mapper
     * @param metricsService 운영 메트릭 기록기
     */
    public EmulatorCommitService(ProviderStatePort providerStatePort,
                                 ExpectedInvocationPort expectedInvocationPort,
                                 InvocationReceiptPort invocationReceiptPort,
                                 EvidenceRecorder evidenceRecorder,
                                 SeedGenerator seedGenerator,
                                 ClockProvider clockProvider,
                                 TemplateRenderer templateRenderer,
                                 ObjectMapper objectMapper,
                                 io.outcomeprobe.infrastructure.metrics.MetricsService metricsService) {
        this.providerStatePort = providerStatePort;
        this.expectedInvocationPort = expectedInvocationPort;
        this.invocationReceiptPort = invocationReceiptPort;
        this.evidenceRecorder = evidenceRecorder;
        this.seedGenerator = seedGenerator;
        this.clockProvider = clockProvider;
        this.templateRenderer = templateRenderer;
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
    }

    /**
     * Provider 상태를 commit하고 응답 계획을 확정한다.
     *
     * <p>실패 시 보존하는 것: CAS 충돌이면 어떤 행도 변경되지 않는다.
     * 예외 발생 시 트랜잭션 전체가 rollback돼 부분 commit이 남지 않는다.</p>
     *
     * @param request 처리할 operation 요청 문맥
     * @return 확정된 응답 계획 또는 충돌 표시
     */
    @Transactional
    public CommitOutcome commitOperation(ProviderOperationRequest request) {
        ExpectedInvocation expectation = request.expectation();

        // 횟수 증가와 상태 전이를 모두 version CAS로 보호해 동시 요청의 중복 commit을 막는다.
        if (!expectedInvocationPort.recordMatch(expectation.id(), expectation.version())) {
            return CommitOutcome.conflicted();
        }

        ProviderContract.TransitionSpec transition = request.operation().transition();
        JsonNode behavior = expectation.behavior();
        int staleAttempts = behavior.path("queryVisibility").path("staleAttempts").asInt(0);
        String staleState = behavior.path("queryVisibility").path("staleState").asText(null);
        // 시간 기준 stale: visibleAfter 시각 이전 조회는 옛 상태를 반환한다.
        Instant visibleAfter = null;
        if (behavior.path("queryVisibility").path("visibleAfter").isTextual()) {
            java.time.Duration staleDuration = io.outcomeprobe.scenario.definition.DslDurations.parse(
                    behavior.path("queryVisibility").path("visibleAfter").asText());
            visibleAfter = clockProvider.now().plus(staleDuration);
        }

        // stale view 계획이 있으면 조회에는 옛 상태(또는 지정 staleState)를 노출하고 truth만 먼저 확정한다.
        ProviderResource resource = request.resource();
        String committedState = transition.committed();
        String visibleState;
        if (staleAttempts > 0 || visibleAfter != null) {
            visibleState = staleState != null ? staleState : resource.canonicalState();
        } else {
            visibleState = committedState;
        }

        String transactionId = deterministicTransactionId(request);
        Map<String, Object> attributeUpdates = new LinkedHashMap<>();
        attributeUpdates.put("transactionId", transactionId);

        boolean committed = providerStatePort.commitTransition(
                request.runId(), resource.resourceType(), resource.resourceId(),
                resource.version(), committedState, visibleState, staleAttempts, visibleAfter,
                attributeUpdates);
        if (!committed) {
            // CAS 실패는 동시 전이가 이미 이 version을 소비했음을 의미한다.
            return CommitOutcome.conflicted();
        }

        evidenceRecorder.append(AppendEvidenceCommand
                .of(request.runId(), EvidenceType.PROVIDER_OPERATION_COMMITTED, EvidenceSource.EMULATOR)
                .withCorrelation(expectation.stepId(), resource.resourceType(), resource.resourceId())
                .withAttribute("operation", request.operationKey())
                .withAttribute("state", committedState)
                .withAttribute("occurrence", String.valueOf(expectation.matchedCount() + 1))
                .withAttribute("faultOrigin", "SCENARIO_PLANNED"));

        metricsService.providerCommit(request.operationKey(), committedState);

        ProviderResource afterCommit = providerStatePort
                .find(request.runId(), resource.resourceType(), resource.resourceId())
                .orElse(resource);

        JsonNode renderedBody = renderSuccessBody(request, afterCommit, transactionId);
        int successStatus = request.operation().successResponse().status();

        if (request.idempotencyKeyHash() != null) {
            ObjectNode snapshot = objectMapper.createObjectNode();
            snapshot.put("status", successStatus);
            snapshot.set("body", renderedBody);
            invocationReceiptPort.save(request.runId(), request.operationKey(),
                    request.idempotencyKeyHash(), request.requestHash(),
                    resource.resourceType(), resource.resourceId(), snapshot);
        }

        String faultType = behavior.path("responseFault").path("type").asText("NONE");
        Duration delay = parseDelay(behavior);
        byte[] bodyBytes = renderedBody.toString().getBytes(StandardCharsets.UTF_8);

        if ("TIMEOUT_AFTER_COMMIT".equals(faultType)) {
            appendFaultEvidence(request, resource, faultType, successStatus);
            // commit은 확정됐고 응답만 지연돼 Target client timeout을 유도한다.
            return new CommitOutcome(successStatus, bodyBytes, delay, false);
        }
        if ("HTTP_ERROR_AFTER_COMMIT".equals(faultType)) {
            int errorStatus = behavior.path("responseFault").path("status").asInt(500);
            appendFaultEvidence(request, resource, faultType, errorStatus);
            return new CommitOutcome(errorStatus, new byte[0], Duration.ZERO, false);
        }

        evidenceRecorder.append(AppendEvidenceCommand
                .of(request.runId(), EvidenceType.PROVIDER_RESPONSE_SENT, EvidenceSource.EMULATOR)
                .withCorrelation(expectation.stepId(), resource.resourceType(), resource.resourceId())
                .withAttribute("operation", request.operationKey())
                .withAttribute("status", String.valueOf(successStatus)));
        return new CommitOutcome(successStatus, bodyBytes, Duration.ZERO, false);
    }

    /**
     * 조회 operation의 stale view 소모와 증거 기록을 수행한다.
     *
     * <p>왜 트랜잭션인가: stale attempt 소모와 PROVIDER_QUERY_RECEIVED 증거가
     * 분리되면 Timeline과 실제 노출 상태가 어긋난다.</p>
     *
     * @param request 처리할 조회 요청 문맥
     * @return 확정된 조회 응답 계획, 자원이 없으면 empty
     */
    @Transactional
    public Optional<CommitOutcome> registerQuery(ProviderOperationRequest request) {
        Optional<ProviderResource> queried = providerStatePort.consumeQuery(
                request.runId(), request.operation().request().resourceType(), request.resourceId());
        if (queried.isEmpty()) {
            return Optional.empty();
        }
        ProviderResource resource = queried.get();

        evidenceRecorder.append(AppendEvidenceCommand
                .of(request.runId(), EvidenceType.PROVIDER_QUERY_RECEIVED, EvidenceSource.EMULATOR)
                .withCorrelation(request.expectation() == null ? null : request.expectation().stepId(),
                        resource.resourceType(), resource.resourceId())
                .withAttribute("operation", request.operationKey()));

        evidenceRecorder.append(AppendEvidenceCommand
                .of(request.runId(), EvidenceType.PROVIDER_VISIBLE_STATE_RETURNED, EvidenceSource.EMULATOR)
                .withCorrelation(null, resource.resourceType(), resource.resourceId())
                .withAttribute("operation", request.operationKey())
                .withAttribute("canonicalState", resource.canonicalState())
                .withAttribute("visibleState", resource.visibleState())
                .withAttribute("remainingStaleQueryAttempts",
                        String.valueOf(resource.remainingStaleQueryAttempts())));

        String transactionId = String.valueOf(resource.attributes().getOrDefault("transactionId", ""));
        JsonNode rendered = renderSuccessBody(request, resource, transactionId);
        int status = request.operation().successResponse().status();
        return Optional.of(new CommitOutcome(status,
                rendered.toString().getBytes(StandardCharsets.UTF_8), Duration.ZERO, false));
    }

    /**
     * idempotent replay의 최초 결과를 렌더링 없이 재사용 응답으로 반환한다.
     *
     * <p>왜 재사용인가: 같은 idempotency key의 같은 요청은 Provider state를
     * 다시 바꾸지 않고 최초 결과를 돌려줘야 멱등 계약이 성립한다.</p>
     *
     * @param request 처리할 요청 문맥
     * @param snapshot 최초 응답 계획 snapshot
     * @return replay 응답 계획
     */
    public CommitOutcome replayReceipt(ProviderOperationRequest request, JsonNode snapshot) {
        int status = snapshot.path("status").asInt(200);
        byte[] body = snapshot.path("body").toString().getBytes(StandardCharsets.UTF_8);
        evidenceRecorder.append(AppendEvidenceCommand
                .of(request.runId(), EvidenceType.PROVIDER_OPERATION_MATCHED, EvidenceSource.EMULATOR)
                .withCorrelation(null, request.operation().request().resourceType(), request.resourceId())
                .withAttribute("operation", request.operationKey())
                .withAttribute("replay", "true"));
        return new CommitOutcome(status, body, Duration.ZERO, false);
    }

    /**
     * 응답 장애 적용 사실을 SCENARIO_PLANNED 기원으로 Evidence에 기록한다.
     */
    private void appendFaultEvidence(ProviderOperationRequest request, ProviderResource resource,
                                     String faultType, int exposedStatus) {
        evidenceRecorder.append(AppendEvidenceCommand
                .of(request.runId(), EvidenceType.PROVIDER_RESPONSE_FAULT_APPLIED, EvidenceSource.EMULATOR)
                .withCorrelation(request.expectation().stepId(), resource.resourceType(), resource.resourceId())
                .withAttribute("operation", request.operationKey())
                .withAttribute("faultType", faultType)
                .withAttribute("exposedStatus", String.valueOf(exposedStatus))
                .withAttribute("faultOrigin", "SCENARIO_PLANNED"));
        metricsService.providerFault(faultType);
    }

    /**
     * 성공 응답 template을 resource·generated·clock 문맥으로 렌더링한다.
     */
    private JsonNode renderSuccessBody(ProviderOperationRequest request, ProviderResource resource,
                                       String transactionId) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("resource.id", resource.resourceId());
        context.put("resource.state", resource.canonicalState());
        context.put("resource.visibleState", resource.visibleState());
        context.put("resource.version", resource.version());
        resource.attributes().forEach((key, value) ->
                context.put("resource.attributes." + key, value));
        context.put("generated.transactionId", transactionId);
        context.put("generated.eventId", deterministicEventId(request));
        context.put("clock.isoInstant", clockProvider.now().toString());
        request.pathVariables().forEach((key, value) -> context.put("request.path." + key, value));
        request.headers().forEach((key, value) -> context.put("request.header." + key, value));

        JsonNode template = request.operation().successResponse().body();
        return templateRenderer.render(template, context);
    }

    /**
     * seed 기반 결정론 transactionId를 생성한다.
     */
    private String deterministicTransactionId(ProviderOperationRequest request) {
        long value = seedGenerator.generateLong(request.seed(), "generated",
                "transactionId/" + request.operation().request().resourceType() + "/" + request.resourceId());
        return "txn-" + Long.toUnsignedString(value, 36);
    }

    /**
     * seed 기반 결정론 eventId를 생성한다.
     */
    private String deterministicEventId(ProviderOperationRequest request) {
        long value = seedGenerator.generateLong(request.seed(), "generated",
                "eventId/" + request.operationKey() + "/" + request.resourceId());
        return "evt-" + Long.toUnsignedString(value, 36);
    }

    /**
     * behavior의 responseFault.delay를 Duration으로 해석한다.
     */
    private Duration parseDelay(JsonNode behavior) {
        JsonNode delayNode = behavior.path("responseFault").path("delay");
        if (!delayNode.isTextual()) {
            return Duration.ZERO;
        }
        return DslDurations.parse(delayNode.asText());
    }
}
