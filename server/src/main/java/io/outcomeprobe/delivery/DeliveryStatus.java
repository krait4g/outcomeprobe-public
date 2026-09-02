package io.outcomeprobe.delivery;

/**
 * Callback 전달 occurrence의 생명주기 상태다.
 *
 * <p>책임: 지연·중복·재시도·누락이 섞인 전달 과정을 상태 vocabulary로 고정해
 * Worker lease와 Evidence 판정이 공유하게 한다.</p>
 * <p>핵심 불변식: DEAD는 확정된 재시도 불가 실패, UNKNOWN은 외부 전송 결과를 확정할 수
 * 없는 최종 상태다. OMITTED는 전송하지 않았다는 의도적 결과이며 실패가 아니다.</p>
 */
public enum DeliveryStatus {

    // 전송 예정 시각을 기다린다.
    SCHEDULED,

    // Worker가 lease를 획득했다.
    LEASED,

    // HTTP 전송을 시도 중이다.
    ATTEMPTING,

    // Target이 성공 상태 코드로 응답했다.
    SUCCEEDED,

    // 재시도 가능 실패 후 다음 시도를 기다린다.
    RETRY_WAIT,

    // Scenario가 의도적으로 전송하지 않았다.
    OMITTED,

    // Run 취소로 전송하지 않는다.
    CANCELLED,

    // terminal Run에서 lease가 만료돼 실제 외부 전송 결과를 확정할 수 없다.
    UNKNOWN,

    // 재시도 한계 소진 또는 재시도 불가 실패로 중단됐다.
    DEAD;

    /**
     * 이 상태가 더 이상 변하지 않는 최종 상태인지 반환한다.
     *
     * @return 최종 상태면 true
     */
    public boolean isFinal() {
        return this == SUCCEEDED || this == OMITTED || this == CANCELLED
                || this == UNKNOWN || this == DEAD;
    }
}
