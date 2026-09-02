package io.outcomeprobe.foundation;

/**
 * Run·Step·Evidence처럼 영속화되는 객체의 고유 식별자를 생성하는 포트이다.
 *
 * <p>책임: 도메인 코드가 전역 난수에 직접 의존하지 않게 막고, 테스트에서는
 * 예측 가능한 결정론적 생성기로 대체한다.</p>
 * <p>핵심 불변식: 동일 프로세스 안에서 생성된 ID는 충돌하지 않는다. 서로 다른 Run 간의
 * 결정론적 재현은 이 포트가 아니라 {@link SeedGenerator}의 책임이다.</p>
 */
public interface IdGenerator {

    /**
     * 새로운 고유 식별자를 문자열로 반환한다.
     *
     * @return 영속화에 사용할 수 있는 고유 식별자
     */
    String nextId();
}
