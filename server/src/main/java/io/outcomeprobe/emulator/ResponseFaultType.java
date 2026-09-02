package io.outcomeprobe.emulator;

/**
 * Provider operation의 응답에 주입할 장애 유형이다.
 *
 * <p>책임: Provider 내부 commit 여부와 Target이 관측하는 HTTP 결과를 독립 축으로
 * 분리해 모호한 연동 상황을 결정론적으로 재현한다.</p>
 * <p>핵심 불변식: fault는 Scenario가 계획한 의도적 실패다. OutcomeProbe 내부
 * 재시도·오류와 섞어 보고하지 않는다.</p>
 */
public enum ResponseFaultType {

    // 장애 없이 정상 응답을 반환한다.
    NONE,

    // commit 전에 지연을 두고 이후 정상 응답한다.
    DELAY_BEFORE_COMMIT,

    // commit 없이 지정 4xx/5xx를 반환한다.
    HTTP_ERROR_BEFORE_COMMIT,

    // commit 후 응답을 지연해 Target client timeout을 유도한다.
    TIMEOUT_AFTER_COMMIT,

    // commit 후 지정 5xx를 반환한다.
    HTTP_ERROR_AFTER_COMMIT
}
