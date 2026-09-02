package io.outcomeprobe.probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Probe 결과 여섯 분류와 retry·deadline 우선순위를 검증한다.
 *
 * <p>검증 불변식: 보안·크기 오류는 즉시 중단하고, 일시 HTTP와 연결 실패,
 * assertion mismatch만 deadline 전 재시도한다.</p>
 */
class ProbeDecisionPolicyTest {

    private final ProbeDecisionPolicy policy = new ProbeDecisionPolicy();

    /** 허용되지 않은 path는 deadline과 무관하게 SECURITY_REJECTED여야 한다. */
    @Test
    void rejectsSecurityFailureImmediately() {
        ProbeDecision decision = policy.decide(
                observation(0, "TARGET_PATH_NOT_ALLOWED"), false, false, false);

        assertThat(decision).isEqualTo(ProbeDecision.SECURITY_REJECTED);
        assertThat(decision.shouldRetry()).isFalse();
    }

    /** body 크기 초과는 상태 코드가 500이어도 transient로 덮지 않아야 한다. */
    @Test
    void keepsResponseTooLargePermanentBeforeTransientStatus() {
        ProbeDecision decision = policy.decide(
                observation(500, "TARGET_RESPONSE_TOO_LARGE"), false, false, false);

        assertThat(decision).isEqualTo(ProbeDecision.INVALID_RESPONSE);
    }

    /** 일시 HTTP 상태는 body가 비JSON이어도 deadline 전 PENDING이어야 한다. */
    @Test
    void retriesTransientHttpBeforeInvalidJsonClassification() {
        ProbeDecision decision = policy.decide(
                observation(503, "PROBE_RESPONSE_INVALID_JSON"), false, false, false);

        assertThat(decision).isEqualTo(ProbeDecision.PENDING);
        assertThat(decision.shouldRetry()).isTrue();
    }

    /** 연결 실패는 deadline 전 TARGET_UNREACHABLE, deadline에서는 실패로 끝나야 한다. */
    @Test
    void turnsConnectionRetryIntoDeadlineExceeded() {
        ProbeObservation observation = observation(0, "TARGET_CONNECTION_FAILED");

        assertThat(policy.decide(observation, false, false, false))
                .isEqualTo(ProbeDecision.TARGET_UNREACHABLE);
        assertThat(policy.decide(observation, false, false, true))
                .isEqualTo(ProbeDecision.DEADLINE_EXCEEDED);
    }

    /** 정상 JSON의 assertion mismatch는 재시도하고 stability까지 충족하면 수렴한다. */
    @Test
    void separatesAssertionPendingFromConverged() {
        ProbeObservation observation = new ProbeObservation(
                Instant.EPOCH, Instant.EPOCH, 200, "a".repeat(64),
                JsonNodeFactory.instance.objectNode(), null);

        assertThat(policy.decide(observation, false, false, false))
                .isEqualTo(ProbeDecision.PENDING);
        assertThat(policy.decide(observation, true, false, false))
                .isEqualTo(ProbeDecision.PENDING);
        assertThat(policy.decide(observation, true, true, false))
                .isEqualTo(ProbeDecision.CONVERGED);
    }

    /** deadline 뒤의 성공 응답은 기한 내 수렴으로 오판하지 않아야 한다. */
    @Test
    void rejectsLateSuccessfulResponse() {
        ProbeObservation observation = new ProbeObservation(
                Instant.EPOCH, Instant.EPOCH, 200, "a".repeat(64),
                JsonNodeFactory.instance.objectNode(), null);

        assertThat(policy.decide(observation, true, true, true))
                .isEqualTo(ProbeDecision.DEADLINE_EXCEEDED);
    }

    /** interrupt는 일반 연결 재시도로 취급하지 않고 영구 중단 분류가 되어야 한다. */
    @Test
    void doesNotRetryInterruptedRequest() {
        ProbeDecision decision = policy.decide(
                observation(0, "INTERRUPTED"), false, false, false);

        assertThat(decision).isEqualTo(ProbeDecision.INVALID_RESPONSE);
        assertThat(decision.shouldRetry()).isFalse();
    }

    /** TLS profile 실패는 영구 응답 분류와 전용 ProblemCode를 유지해야 한다. */
    @Test
    void classifiesTlsFailureAsPermanent() {
        ProbeObservation observation = observation(0, "TARGET_TLS_INVALID");

        assertThat(policy.decide(observation, false, false, false))
                .isEqualTo(ProbeDecision.INVALID_RESPONSE);
        assertThat(policy.permanentProblemCode(observation))
                .isEqualTo(io.outcomeprobe.foundation.ProblemCode.TARGET_TLS_INVALID);
    }

    /** Header preflight의 원래 schema 문제 코드는 일반 응답 오류로 바뀌면 안 된다. */
    @Test
    void preservesHeaderPreflightProblemCode() {
        ProbeObservation observation = observation(0, "SCENARIO_SCHEMA_INVALID");

        assertThat(policy.decide(observation, false, false, false))
                .isEqualTo(ProbeDecision.INVALID_RESPONSE);
        assertThat(policy.permanentProblemCode(observation))
                .isEqualTo(io.outcomeprobe.foundation.ProblemCode.SCENARIO_SCHEMA_INVALID);
    }

    /** 상위 Step deadline과 within 계산값 중 더 이른 시각을 선택해야 한다. */
    @Test
    void usesEarlierEffectiveDeadline() {
        Instant started = Instant.parse("2026-08-06T00:00:00Z");

        assertThat(policy.effectiveDeadline(started, Duration.ofSeconds(30),
                started.plusSeconds(10))).isEqualTo(started.plusSeconds(10));
        assertThat(policy.effectiveDeadline(started, Duration.ofSeconds(5),
                started.plusSeconds(10))).isEqualTo(started.plusSeconds(5));
    }

    /** retryOnStatus는 기본 집합의 부분집합만 허용하고 확대 요청은 거부해야 한다. */
    @Test
    void onlyAllowsRetryStatusNarrowing() {
        assertThat(policy.narrowRetryStatuses(List.of(404, 503)))
                .isEqualTo(Set.of(404, 503));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.narrowRetryStatuses(List.of(200, 503)));
    }

    /** 고정 시각과 실패 코드로 HTTP 관측 fixture를 만든다. */
    private ProbeObservation observation(int statusCode, String failureCode) {
        return new ProbeObservation(Instant.EPOCH, Instant.EPOCH, statusCode,
                null, null, failureCode);
    }
}
