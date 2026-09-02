package io.outcomeprobe.execution.adapter.in.web;

import io.outcomeprobe.execution.CreateRunRequest;
import io.outcomeprobe.execution.RunEngine;
import io.outcomeprobe.execution.RunRequestIdempotencyService;
import io.outcomeprobe.execution.ScenarioRun;
import io.outcomeprobe.foundation.ProblemCode;
import io.outcomeprobe.scenario.ArtifactImportException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Scenario Run 생성·조회·취소를 제공하는 Control API다.
 *
 * <p>책임: CLI와 UI가 동일한 Run 생명주기 API를 사용하게 한다. 실행 판단은
 * 전부 RunEngine에 위임하고 Controller는 직렬화만 담당한다.</p>
 * <p>핵심 불변식: Run 생성은 202 Accepted로 접수하며, terminal 여부는 별도
 * 조회로 확인한다. 생성 응답이 Run 완료를 의미하지 않는다.</p>
 */
@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    private final RunEngine runEngine;
    private final RunRequestIdempotencyService idempotencyService;

    /**
     * Run API 구현에 필요한 협업 객체를 주입한다.
     *
     * @param runEngine 실행 엔진
     * @param idempotencyService 응답 유실 재시도를 기존 Run에 수렴시키는 서비스
     */
    public RunController(RunEngine runEngine, RunRequestIdempotencyService idempotencyService) {
        this.runEngine = runEngine;
        this.idempotencyService = idempotencyService;
    }

    /**
     * 최근 Run 목록을 offset·limit 페이지네이션으로 조회한다.
     *
     * @param offset 건너뛸 건수 (0부터)
     * @param limit 최대 조회 건수 (1~200)
     * @return Run 요약 목록
     */
    @GetMapping
    public List<Map<String, Object>> list(
            @org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "0")
            int offset,
            @org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "50")
            int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        int safeOffset = Math.max(offset, 0);
        // offset+limit만큼 가져와 skip해야 2페이지 이후에도 결과가 남는다.
        // limit건만 가져와 skip하면 offset≥limit일 때 항상 빈 목록이 반환된다(OP-AUDIT-07-PAG-001).
        return runEngine.findRecentRuns(safeOffset + safeLimit).stream()
                .skip(safeOffset)
                .map(this::summary)
                .toList();
    }

    /**
     * Run 생성을 접수한다.
     *
     * @param request Run 생성 요청
     * @return 202와 Location header
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody CreateRunRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (request.scenarioRef() == null || request.scenarioRef().name() == null) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_REFERENCE_NOT_FOUND,
                    "scenarioRef", "scenarioRef.name이 필요합니다.");
        }
        String runId = idempotencyKey == null
                ? runEngine.createRun(request)
                : idempotencyService.createRun(request, idempotencyKey);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/runs/" + runId))
                .body(body);
    }

    /**
     * Run 상태 요약을 조회한다.
     *
     * @param runId Run 식별자
     * @return Run 요약 또는 404
     */
    @GetMapping("/{runId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String runId) {
        return runEngine.findRun(runId)
                .map(run -> ResponseEntity.ok(summary(run)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Run의 Step 실행 목록을 조회한다.
     *
     * @param runId Run 식별자
     * @return Step 요약 목록
     */
    @GetMapping("/{runId}/steps")
    public List<Map<String, Object>> steps(@PathVariable String runId) {
        return runEngine.findSteps(runId).stream()
                .map(step -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("stepId", step.stepId());
                    result.put("ordinal", step.ordinal());
                    result.put("type", step.stepType().name());
                    result.put("status", step.status().name());
                    result.put("attemptCount", step.attemptCount());
                    result.put("problemCode", step.problemCode());
                    return result;
                })
                .toList();
    }

    /**
     * Run의 Evidence Timeline을 조회한다.
     *
     * @param runId Run 식별자
     * @return sequence 정렬된 Evidence 요약 목록
     */
    @GetMapping("/{runId}/timeline")
    public List<Map<String, Object>> timeline(@PathVariable String runId) {
        return runEngine.timeline(runId).stream()
                .map(evidence -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("sequence", evidence.sequence());
                    result.put("type", evidence.type().name());
                    result.put("source", evidence.source().name());
                    result.put("stepId", evidence.stepId());
                    result.put("correlationId", evidence.correlationId());
                    result.put("resourceType", evidence.resourceType());
                    result.put("resourceId", evidence.resourceId());
                    result.put("attributes", evidence.attributes());
                    result.put("logicalTimeMs", evidence.logicalTimeMs());
                    result.put("payloadSummary", evidence.payloadSummary());
                    result.put("bodyHash", evidence.bodyHash());
                    result.put("redactionApplied", evidence.redactionApplied());
                    result.put("observedAt", evidence.observedAt().toString());
                    return result;
                })
                .toList();
    }

    /**
     * Run의 Evidence를 events 이름으로 조회한다.
     *
     * <p>timeline과 동일한 데이터를 반환하는 별칭이다. 명세 §16.5의 events
     * 엔드포인트 계약을 만족시킨다.</p>
     *
     * @param runId Run 식별자
     * @return sequence 정렬된 Evidence 요약 목록
     */
    @GetMapping("/{runId}/events")
    public List<Map<String, Object>> events(@PathVariable String runId) {
        return timeline(runId);
    }

    /**
     * 기존 Run과 동일한 조건으로 새 Run을 재실행한다.
     *
     * @param runId 재실행 기준 원본 Run 식별자
     * @return 202와 새 Run의 Location header
     */
    @PostMapping("/{runId}/rerun")
    public ResponseEntity<Map<String, Object>> rerun(
            @PathVariable String runId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        String newRunId = idempotencyKey == null
                ? runEngine.rerun(runId)
                : idempotencyService.rerun(runId, idempotencyKey);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", newRunId);
        body.put("sourceRunId", runId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/runs/" + newRunId))
                .body(body);
    }

    /**
     * Run 취소를 요청한다.
     *
     * <p>존재하지 않거나 이미 terminal인 Run은 404로 응답해 클라이언트 오류를 숨기지 않는다.</p>
     *
     * @param runId Run 식별자
     * @return 202(수락) 또는 404(대상 없음·이미 종료)
     */
    @PostMapping("/{runId}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String runId) {
        boolean accepted = runEngine.requestCancel(runId);
        if (!accepted) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("runId", runId, "error", "Run이 없거나 이미 종료되었습니다."));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("runId", runId, "requested", true));
    }

    /**
     * Run을 API 응답용 요약 map으로 변환한다. persistence 모델을 직접 노출하지 않는다.
     */
    private Map<String, Object> summary(ScenarioRun run) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", run.id());
        result.put("scenario", run.scenarioName() + "@" + run.scenarioVersion());
        result.put("targetProfile", run.targetProfileName());
        result.put("seed", run.seed());
        result.put("status", run.status().name());
        result.put("problemCode", run.problemCode());
        result.put("startedAt", run.startedAt() == null ? null : run.startedAt().toString());
        result.put("finishedAt", run.finishedAt() == null ? null : run.finishedAt().toString());
        result.put("createdAt", run.createdAt().toString());
        return result;
    }
}
