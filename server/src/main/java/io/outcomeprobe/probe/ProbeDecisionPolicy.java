package io.outcomeprobe.probe;

import io.outcomeprobe.foundation.ProblemCode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Probe 관측·assertion·deadline을 여섯 가지 표준 결과로 분류한다.
 *
 * <p>책임: 연결·일시 HTTP·assertion mismatch는 재시도하고, 보안 거부와 반복
 * 불가능 응답은 즉시 오류로 중단하는 순수 판정 규칙을 제공한다.</p>
 * <p>핵심 불변식: deadline은 재시도 가능한 결과에만 적용된다. 영구 오류를
 * deadline 실패로 덮어쓰지 않아 원인을 기계적으로 구분할 수 있다.</p>
 */
public final class ProbeDecisionPolicy {

    private static final Set<String> SECURITY_FAILURE_CODES = Set.of(
            "TARGET_PATH_NOT_ALLOWED",
            "TARGET_METHOD_NOT_ALLOWED",
            "TARGET_URI_INVALID",
            "TARGET_HOST_NOT_ALLOWED",
            "TARGET_HEADER_INVALID");
    private static final Set<Integer> DEFAULT_RETRY_STATUSES = defaultStatuses();

    /** 상태 없는 순수 판정기를 생성한다. */
    public ProbeDecisionPolicy() {
    }

    /**
     * 한 attempt의 관측 결과와 수렴 상태를 표준 결과로 분류한다.
     *
     * @param observation HTTP 관측 결과
     * @param assertionsPassed 모든 assertion 통과 여부
     * @param stabilitySatisfied 연속 성공 횟수 충족 여부
     * @param deadlineReached 응답 관측 시점에 deadline에 도달했는지 여부
     * @return DB와 Evidence에 함께 저장할 Probe 결과 분류
     */
    public ProbeDecision decide(ProbeObservation observation,
                                boolean assertionsPassed,
                                boolean stabilitySatisfied,
                                boolean deadlineReached) {
        return decide(observation, assertionsPassed, stabilitySatisfied, deadlineReached,
                DEFAULT_RETRY_STATUSES);
    }

    /**
     * Scenario가 기본 집합의 부분집합으로 좁힌 HTTP retry 상태를 적용해 분류한다.
     *
     * @param observation HTTP 관측 결과
     * @param assertionsPassed 모든 assertion 통과 여부
     * @param stabilitySatisfied 연속 성공 횟수 충족 여부
     * @param deadlineReached 응답 관측 시점 deadline 도달 여부
     * @param retryStatuses 이번 Step이 허용한 일시 HTTP 상태 부분집합
     * @return 표준 Probe 결과 분류
     */
    public ProbeDecision decide(ProbeObservation observation,
                                boolean assertionsPassed,
                                boolean stabilitySatisfied,
                                boolean deadlineReached,
                                Set<Integer> retryStatuses) {
        ProbeDecision preliminary = classifyBeforeDeadline(
                observation, assertionsPassed, stabilitySatisfied, retryStatuses);
        // deadline 이후 도착한 성공 응답도 기한 내 수렴이 아니므로 PASS로 바꾸지 않는다.
        // 단, 보안·영구 응답 오류는 deadline 실패로 덮지 않아 실제 원인을 보존한다.
        if (deadlineReached && !preliminary.isPermanentError()) {
            return ProbeDecision.DEADLINE_EXCEEDED;
        }
        return preliminary;
    }

    /**
     * 사용자 retryOnStatus가 기본 retry 상태를 확대하지 않는 부분집합인지 검증한다.
     *
     * @param requested Scenario에 명시된 HTTP 상태 목록
     * @return 중복이 제거된 불변 부분집합
     * @throws IllegalArgumentException 기본 retry 집합 밖의 상태가 포함되면 발생한다
     */
    public Set<Integer> narrowRetryStatuses(List<Integer> requested) {
        LinkedHashSet<Integer> narrowed = new LinkedHashSet<>(requested);
        if (!DEFAULT_RETRY_STATUSES.containsAll(narrowed)) {
            throw new IllegalArgumentException(
                    "retryOnStatus는 기본 재시도 상태의 부분집합이어야 합니다.");
        }
        return Set.copyOf(narrowed);
    }

    /**
     * 명세 기본 HTTP retry 상태의 불변 집합을 반환한다.
     *
     * @return 404·409·425·429·500~599 집합
     */
    public Set<Integer> defaultRetryStatuses() {
        return DEFAULT_RETRY_STATUSES;
    }

    /**
     * Scenario within과 상위 Step deadline 중 더 이른 실제 deadline을 계산한다.
     *
     * @param started Probe 최초 대기 시작 시각
     * @param within Scenario에 설정된 수렴 허용 기간
     * @param stepDeadline RunEngine이 계산한 상위 Step deadline, 없으면 null
     * @return 두 제한 중 더 이른 시각
     */
    public Instant effectiveDeadline(Instant started, Duration within, Instant stepDeadline) {
        Instant configured = started.plus(within);
        if (stepDeadline != null && stepDeadline.isBefore(configured)) {
            return stepDeadline;
        }
        return configured;
    }

    /**
     * 영구 오류 분류를 공개 ProblemCode로 변환한다.
     *
     * @param observation 영구 오류로 판정된 관측 결과
     * @return Step과 Run에 보고할 고정 문제 코드
     */
    public ProblemCode permanentProblemCode(ProbeObservation observation) {
        String failureCode = observation.failureCode();
        if (failureCode == null) {
            return ProblemCode.TARGET_RESPONSE_UNEXPECTED;
        }
        return switch (failureCode) {
            case "TARGET_PATH_NOT_ALLOWED" -> ProblemCode.TARGET_PATH_NOT_ALLOWED;
            case "TARGET_METHOD_NOT_ALLOWED" -> ProblemCode.TARGET_METHOD_NOT_ALLOWED;
            case "TARGET_URI_INVALID" -> ProblemCode.TARGET_URI_INVALID;
            case "TARGET_HOST_NOT_ALLOWED" -> ProblemCode.TARGET_HOST_NOT_ALLOWED;
            case "TARGET_HEADER_INVALID" -> ProblemCode.TARGET_HEADER_INVALID;
            case "SCENARIO_SCHEMA_INVALID" -> ProblemCode.SCENARIO_SCHEMA_INVALID;
            case "ARTIFACT_SNAPSHOT_CORRUPTED" -> ProblemCode.ARTIFACT_SNAPSHOT_CORRUPTED;
            case "TARGET_TLS_INVALID" -> ProblemCode.TARGET_TLS_INVALID;
            case "TARGET_RESPONSE_TOO_LARGE" -> ProblemCode.TARGET_RESPONSE_TOO_LARGE;
            case "PROBE_RESPONSE_INVALID_JSON" -> ProblemCode.PROBE_RESPONSE_INVALID_JSON;
            case "INTERRUPTED" -> ProblemCode.TARGET_CONNECTION_FAILED;
            default -> ProblemCode.TARGET_RESPONSE_UNEXPECTED;
        };
    }

    /**
     * attempt에 저장할 실패 원인을 원문 없이 계산한다.
     *
     * @param observation HTTP 관측 결과
     * @param assertionsPassed assertion 전체 통과 여부
     * @return 고정 실패 코드, 실패 원인이 없으면 null
     */
    public String failureCode(ProbeObservation observation, boolean assertionsPassed) {
        if (observation.failureCode() != null) {
            return observation.failureCode();
        }
        if (observation.statusCode() < 200 || observation.statusCode() >= 300) {
            return "TARGET_RESPONSE_UNEXPECTED";
        }
        if (!assertionsPassed) {
            return "PROBE_ASSERTION_FAILED";
        }
        return null;
    }

    /**
     * deadline을 적용하기 전 보안·응답·수렴 우선순위로 결과를 분류한다.
     */
    private ProbeDecision classifyBeforeDeadline(ProbeObservation observation,
                                                  boolean assertionsPassed,
                                                  boolean stabilitySatisfied,
                                                  Set<Integer> retryStatuses) {
        String failureCode = observation.failureCode();
        if (failureCode != null && SECURITY_FAILURE_CODES.contains(failureCode)) {
            return ProbeDecision.SECURITY_REJECTED;
        }
        if ("TARGET_RESPONSE_TOO_LARGE".equals(failureCode)) {
            return ProbeDecision.INVALID_RESPONSE;
        }
        if (retryStatuses.contains(observation.statusCode())) {
            return ProbeDecision.PENDING;
        }
        if ("TARGET_CONNECTION_FAILED".equals(failureCode)) {
            return ProbeDecision.TARGET_UNREACHABLE;
        }
        if (failureCode != null) {
            return ProbeDecision.INVALID_RESPONSE;
        }
        if (observation.statusCode() < 200 || observation.statusCode() >= 300) {
            return ProbeDecision.INVALID_RESPONSE;
        }
        if (!assertionsPassed || !stabilitySatisfied) {
            return ProbeDecision.PENDING;
        }
        return ProbeDecision.CONVERGED;
    }

    /**
     * 기본 retry 상태 집합을 결정론적으로 만든다.
     */
    private static Set<Integer> defaultStatuses() {
        LinkedHashSet<Integer> statuses = new LinkedHashSet<>(List.of(404, 409, 425, 429));
        IntStream.rangeClosed(500, 599).forEach(statuses::add);
        return Set.copyOf(statuses);
    }
}
