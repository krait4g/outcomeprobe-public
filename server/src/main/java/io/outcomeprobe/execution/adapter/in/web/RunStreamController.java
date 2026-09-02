package io.outcomeprobe.execution.adapter.in.web;

import io.outcomeprobe.evidence.EvidenceEvent;
import io.outcomeprobe.execution.RunEngine;
import io.outcomeprobe.execution.ScenarioRun;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Run 진행 상황을 Server-Sent Events로 스트리밍한다.
 *
 * <p>책임: UI가 Timeline을 실시간으로 갱신하게 한다. 스트림이 끊겨도 Run 실행에는
 * 영향이 없으며,클라이언트는 Last-Event-ID(sequence)로 이어 받을 수 있다.</p>
 * <p>핵심 불변식: 이 스트림은 Evidence를 복사해 전달할 뿐 Run 상태를 바꾸지 않는다.
 * 연결 손실은 실행 엔진과 무관하다.</p>
 */
@RestController
@RequestMapping("/api/v1/runs/{runId}/stream")
public class RunStreamController {

    private static final Logger log = LoggerFactory.getLogger(RunStreamController.class);
    private static final long STREAM_TIMEOUT_MILLIS = 11 * 60_000L;

    private final RunEngine runEngine;
    private final ExecutorService streamExecutor;

    /**
     * Run 조회용 엔진을 주입한다.
     *
     * @param runEngine 실행 엔진
     */
    @Autowired
    public RunStreamController(RunEngine runEngine) {
        this(runEngine, Executors.newVirtualThreadPerTaskExecutor());
    }

    /** 단위 테스트가 worker 수명 주기를 관찰할 수 있도록 executor를 명시 주입한다. */
    RunStreamController(RunEngine runEngine, ExecutorService streamExecutor) {
        this.runEngine = runEngine;
        this.streamExecutor = streamExecutor;
    }

    /**
     * Evidence와 Run 상태를 SSE로 전송한다.
     *
     * <p>Last-Event-ID 이후의 Evidence만 전송해 재연결 시 누락을 줄인다.</p>
     *
     * @param runId Run 식별자
     * @param lastEventId 재연결 시 마지막으로 받은 sequence
     * @return 존재하면 유한 timeout SSE emitter, 없으면 404
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(
            @PathVariable String runId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        if (runEngine.findRun(runId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        long afterSequence = parseSequence(lastEventId);
        AtomicReference<Future<?>> worker = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean();
        Runnable cleanup = () -> {
            closed.set(true);
            Future<?> task = worker.get();
            if (task != null && !task.isDone()) {
                task.cancel(true);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            cleanup.run();
            emitter.complete();
        });
        emitter.onError(ignored -> cleanup.run());
        Future<?> submitted = streamExecutor.submit(() -> pump(emitter, runId, afterSequence));
        worker.set(submitted);
        if (closed.get()) {
            submitted.cancel(true);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    /**
     * SSE 구독자에게 새 Evidence와 Run 상태를 스트리밍한다.
     * Last-Event-ID 이후만 전송하고, heartbeat로 유휴 연결을 유지한다.
     */
    private void pump(SseEmitter emitter, String runId, long afterSequence) {
        long cursor = afterSequence;
        try {
            while (true) {
                final long current = cursor;
                List<EvidenceEvent> events = runEngine.timeline(runId).stream()
                        .filter(event -> event.sequence() > current)
                        .toList();
                for (EvidenceEvent event : events) {
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(event.sequence()))
                            .name("evidence-appended")
                            .data(toJson(event), MediaType.APPLICATION_JSON));
                    cursor = event.sequence();
                }

                Optional<ScenarioRun> run = runEngine.findRun(runId);
                if (run.isEmpty()) {
                    emitter.complete();
                    return;
                }
                if (run.get().status().isTerminal()) {
                    ObjectNode status = JsonNodeFactory.instance.objectNode();
                    status.put("runId", runId);
                    status.put("status", run.get().status().name());
                    emitter.send(SseEmitter.event()
                            .name("run-status")
                            .data(status, MediaType.APPLICATION_JSON));
                    emitter.complete();
                    return;
                }

                // heartbeat로 중간의 유휴 연결 끊김을 줄인다.
                emitter.send(SseEmitter.event().name("heartbeat").data("ping"));
                Thread.sleep(5_000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (Exception e) {
            log.debug("SSE 스트림 연결이 종료됐습니다.");
            emitter.completeWithError(e);
        }
    }

    /** application 종료 시 남은 SSE worker를 interrupt해 executor residue를 남기지 않는다. */
    @PreDestroy
    public void close() {
        streamExecutor.shutdownNow();
    }

    /**
     * Evidence를 SSE payload JSON으로 변환한다.
     */
    private ObjectNode toJson(EvidenceEvent event) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("sequence", event.sequence());
        node.put("type", event.type().name());
        node.put("source", event.source().name());
        if (event.stepId() != null) {
            node.put("stepId", event.stepId());
        }
        if (event.correlationId() != null) {
            node.put("correlationId", event.correlationId());
        }
        if (event.resourceType() != null) {
            node.put("resourceType", event.resourceType());
        }
        if (event.resourceId() != null) {
            node.put("resourceId", event.resourceId());
        }
        if (event.logicalTimeMs() != null) {
            node.put("logicalTimeMs", event.logicalTimeMs());
        }
        if (event.payloadSummary() != null) {
            node.put("payloadSummary", event.payloadSummary());
        }
        if (event.bodyHash() != null) {
            node.put("bodyHash", event.bodyHash());
        }
        node.put("redactionApplied", event.redactionApplied());
        node.put("observedAt", event.observedAt().toString());
        node.putPOJO("attributes", event.attributes());
        return node;
    }

    /**
     * Last-Event-ID를 sequence 숫자로 해석한다. 잘못된 값은 0으로 취급한다.
     */
    private long parseSequence(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
