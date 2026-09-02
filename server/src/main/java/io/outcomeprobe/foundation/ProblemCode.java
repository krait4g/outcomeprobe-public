package io.outcomeprobe.foundation;

/**
 * OutcomeProbe가 사용자와 CI에 보고하는 실패·오류 코드 사전이다.
 *
 * <p>책임: Scenario 해석 오류, Target·Provider·Delivery·Probe 실패, 내부 오류를
 * 기계가 읽을 수 있는 고정 코드 vocabularly로 통일한다. Report와 problem+json 응답은
 * 이 enum 값만 사용한다.</p>
 * <p>핵심 불변식: 재시도 가능한 일시 오류, Scenario가 의도한 실패, OutcomeProbe 자체 결함은
 * 서로 다른 코드로 구분한다. 특히 {@code FAILED} Run과 {@code ERROR} Run을 나누는 판단은
 * 이 코드 체계에 기반한다.</p>
 */
public enum ProblemCode {

    // Scenario 정의와 해석 단계의 오류다. 사용자 입력 수정으로 해결 가능하다.
    SCENARIO_SCHEMA_INVALID,
    ARTIFACT_KIND_NOT_SUPPORTED,
    SCENARIO_REFERENCE_NOT_FOUND,
    SCENARIO_UNKNOWN_OPERATION,
    SCENARIO_UNKNOWN_CALLBACK,
    SCENARIO_VARIABLE_UNRESOLVED,
    SCENARIO_TIMEOUT_EXCEEDED,
    STEP_TIMEOUT_EXCEEDED,
    SCENARIO_EXPECTATION_AMBIGUOUS,

    // Target 연결과 응답 단계의 오류다.
    TARGET_PROFILE_NOT_FOUND,
    TARGET_SECRET_UNRESOLVED,
    TARGET_PATH_NOT_ALLOWED,
    TARGET_METHOD_NOT_ALLOWED,
    TARGET_URI_INVALID,
    TARGET_HOST_NOT_ALLOWED,
    TARGET_HEADER_INVALID,
    TARGET_TLS_INVALID,
    TARGET_CONNECTION_FAILED,
    TARGET_RESPONSE_UNEXPECTED,
    TARGET_RESPONSE_TOO_LARGE,

    // Provider Emulator에서 발생하는 계약 위반과 기대 불충족이다.
    PROVIDER_ALIAS_BUSY,
    PROVIDER_OPERATION_UNEXPECTED,
    PROVIDER_REQUEST_MISMATCH,
    PROVIDER_STATE_TRANSITION_INVALID,
    PROVIDER_IDEMPOTENCY_KEY_REUSED,
    PROVIDER_EXPECTATION_UNSATISFIED,
    PROVIDER_EXPECTATION_AMBIGUOUS,

    // Callback 전달 단계의 오류다.
    CALLBACK_TARGET_NOT_ALLOWED,
    CALLBACK_SIGNATURE_CONFIGURATION_INVALID,
    CALLBACK_DELIVERY_RETRY_EXHAUSTED,
    CALLBACK_RESPONSE_UNEXPECTED,

    // State Probe와 assertion 단계의 오류다.
    PROBE_DEADLINE_EXCEEDED,
    PROBE_ASSERTION_FAILED,
    PROBE_RESPONSE_INVALID_JSON,
    EVIDENCE_ASSERTION_FAILED,

    // OutcomeProbe 자체의 내부 오류다. Run은 FAILED가 아니라 ERROR로 끝난다.
    RUN_LEASE_LOST,
    RUN_IDEMPOTENCY_KEY_REUSED,
    DATABASE_CONFLICT,
    ARTIFACT_SNAPSHOT_CORRUPTED,
    INTERNAL_EXECUTION_ERROR
}
