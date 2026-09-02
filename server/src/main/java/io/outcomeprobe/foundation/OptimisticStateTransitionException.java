package io.outcomeprobe.foundation;

/**
 * optimistic CAS가 실패해 하나의 상태 변경 단위를 확정할 수 없음을 나타낸다.
 *
 * <p>책임: lease를 잃은 Worker의 늦은 쓰기를 정상 성공처럼 숨기지 않고 트랜잭션을
 * rollback시키는 신호를 제공한다. 핵심 불변식은 CAS 실패 뒤 Evidence나 연관 상태가
 * 부분적으로 commit되지 않는 것이다.</p>
 */
public class OptimisticStateTransitionException extends RuntimeException {

    /**
     * 충돌한 자원과 기대 version을 숨기지 않는 오류를 만든다.
     *
     * @param message secret을 포함하지 않는 충돌 설명
     */
    public OptimisticStateTransitionException(String message) {
        super(message);
    }
}
