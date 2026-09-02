package io.outcomeprobe.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.outcomeprobe.foundation.CanonicalJson;
import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.foundation.ProblemCode;
import io.outcomeprobe.scenario.ArtifactImportException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Run 변경 요청 idempotency의 응답 유실·충돌 경계를 단위 검증한다.
 *
 * <p>검출 가능한 결함: 같은 intent 재시도가 두 Run을 만들거나, 같은 key의 다른 payload가
 * 최초 결과를 재사용하는 무결성 오류다.</p>
 */
class RunRequestIdempotencyServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");
    private static final String KEY = "web-intent-key-0123456789";
    private static final String RUN_ID = "1d46928b-f1a0-4ccd-a143-c4b0bacb03cd";

    private final RunEngine runEngine = mock(RunEngine.class);
    private final RunRequestIdempotencyPort port = mock(RunRequestIdempotencyPort.class);
    private final ClockProvider clockProvider = mock(ClockProvider.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private RunRequestIdempotencyService service;

    /** 공통 clock과 application service를 준비한다. */
    @BeforeEach
    void setUp() {
        when(clockProvider.now()).thenReturn(NOW);
        service = new RunRequestIdempotencyService(
                runEngine, port, clockProvider, objectMapper);
    }

    /** 서버 commit 뒤 응답이 유실되어 같은 key로 재요청해도 정확히 한 Run을 반환한다. */
    @Test
    void responseLossRetryReturnsExactSameRun() {
        CreateRunRequest request = request(42001L);
        String requestHash = CanonicalJson.sha256(objectMapper, request);
        when(port.lock(eq("CREATE"), anyString()))
                .thenReturn(new RunRequestIdempotencyPort.Entry(requestHash, null))
                .thenReturn(new RunRequestIdempotencyPort.Entry(requestHash, RUN_ID));
        when(runEngine.createRun(request)).thenReturn(RUN_ID);

        assertThat(service.createRun(request, KEY)).isEqualTo(RUN_ID);
        assertThat(service.createRun(request, KEY)).isEqualTo(RUN_ID);

        verify(runEngine).createRun(request);
        verify(port).bindRun(eq("CREATE"), anyString(), eq(RUN_ID));
    }

    /** 같은 key를 다른 payload에 재사용하면 409용 문제 코드로 거부하고 Run을 만들지 않는다. */
    @Test
    void sameKeyDifferentPayloadConflicts() {
        CreateRunRequest original = request(42001L);
        CreateRunRequest changed = request(42002L);
        when(port.lock(eq("CREATE"), anyString()))
                .thenReturn(new RunRequestIdempotencyPort.Entry(
                        CanonicalJson.sha256(objectMapper, original), RUN_ID));

        assertThatThrownBy(() -> service.createRun(changed, KEY))
                .isInstanceOfSatisfying(ArtifactImportException.class,
                        exception -> assertThat(exception.problemCode())
                                .isEqualTo(ProblemCode.RUN_IDEMPOTENCY_KEY_REUSED));
        verify(runEngine, never()).createRun(changed);
    }

    /** header injection과 지나치게 짧은 key를 DB 접근 전에 거부한다. */
    @Test
    void invalidKeyFailsBeforeLedgerAccess() {
        assertThatThrownBy(() -> service.createRun(request(42001L), "bad\nkey"))
                .isInstanceOf(ArtifactImportException.class);
        verify(port, never()).reserve(anyString(), anyString(), anyString(), eq(NOW));
    }

    /** 테스트할 결정론적 Run 생성 요청을 만든다. */
    private CreateRunRequest request(Long seed) {
        return new CreateRunRequest(
                new CreateRunRequest.ScenarioRef("payment-timeout-after-commit", "1.0.0"),
                "resilient-consumer", seed, Map.of("paymentId", "pay-idem"), null);
    }
}
