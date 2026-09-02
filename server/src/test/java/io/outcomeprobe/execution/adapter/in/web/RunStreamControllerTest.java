package io.outcomeprobe.execution.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.outcomeprobe.execution.RunEngine;
import io.outcomeprobe.execution.RunStatus;
import io.outcomeprobe.execution.ScenarioRun;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * SSE unknown-run과 terminal worker 종료 경계를 검증한다.
 *
 * <p>검출 가능한 결함: 없는 Run이 heartbeat loop를 영구 생성하거나 terminal 전송 뒤
 * worker가 executor에 남는 문제다.</p>
 */
class RunStreamControllerTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(1);
    private final RunEngine runEngine = mock(RunEngine.class);
    private final RunStreamController controller = new RunStreamController(runEngine, executor);

    /** worker executor를 테스트마다 강제 종료한다. */
    @AfterEach
    void closeExecutor() {
        controller.close();
    }

    /** 존재하지 않는 Run은 worker 생성 없이 동기 404로 끝난다. */
    @Test
    void unknownRunReturns404WithoutWorker() {
        when(runEngine.findRun("missing-run")).thenReturn(Optional.empty());

        assertThat(controller.stream("missing-run", null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ThreadPoolExecutor) executor).getTaskCount()).isZero();
    }

    /** terminal Run은 status 전송 뒤 worker를 유한 시간 안에 회수한다. */
    @Test
    void terminalRunCompletesWorker() {
        ScenarioRun run = mock(ScenarioRun.class);
        when(run.status()).thenReturn(RunStatus.PASSED);
        when(runEngine.findRun("terminal-run")).thenReturn(Optional.of(run));
        when(runEngine.timeline("terminal-run")).thenReturn(List.of());

        assertThat(controller.stream("terminal-run", null).getStatusCode()).isEqualTo(HttpStatus.OK);

        verify(runEngine, timeout(2_000)).timeline("terminal-run");
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(((ThreadPoolExecutor) executor).getActiveCount()).isZero());
    }
}
