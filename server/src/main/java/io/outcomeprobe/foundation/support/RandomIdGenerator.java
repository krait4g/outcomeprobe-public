package io.outcomeprobe.foundation.support;

import io.outcomeprobe.foundation.IdGenerator;
import java.util.UUID;

/**
 * 무작위 UUID 기반 기본 {@link IdGenerator} 구현이다.
 *
 * <p>책임: Run·Step·Evidence 행의 surrogate key를 생성한다. 전역 난수 호출을
 * 이 구현 한 곳으로 모아 도메인 코드가 직접 UUID를 만들지 못하게 한다.</p>
 * <p>핵심 불변식: Scenario 재현에 필요한 결정론은 이 구현이 아니라
 * {@code SeedGenerator}가 담당한다. 따라서 이 생성기는 의도적으로 비결정론적이다.</p>
 */
public final class RandomIdGenerator implements IdGenerator {

    /**
     * 기본 생성자다. 상태 없이 매 호출마다 새로운 UUID를 만든다.
     */
    public RandomIdGenerator() {
    }

    /**
     * 새로운 무작위 UUID를 문자열로 반환한다.
     *
     * @return 소문자 하이픈 형식의 UUID 문자열
     */
    @Override
    public String nextId() {
        // 도메인 코드의 UUID.randomUUID 직접 호출을 금지하고 이 지점만 허용한다.
        return UUID.randomUUID().toString();
    }
}
