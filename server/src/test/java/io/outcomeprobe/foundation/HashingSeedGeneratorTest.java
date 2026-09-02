package io.outcomeprobe.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.outcomeprobe.foundation.support.HashingSeedGenerator;
import org.junit.jupiter.api.Test;

/**
 * {@link HashingSeedGenerator}의 결정론 계약과 독립성 계약을 검증한다.
 *
 * <p>검출 가능한 결함: 같은 seed에서 값이 바뀌는 비결정론, Step 추가로 인접 값이
 * 밀리는 전역 Random 방식의 부작용, 음수 해시에서 범위가 음수로 나오는 문제.</p>
 */
class HashingSeedGeneratorTest {

    private final SeedGenerator generator = new HashingSeedGenerator();

    /**
     * 같은 seed·namespace·logicalKey는 실행 시점과 무관하게 같은 값을 반환해야 한다.
     */
    @Test
    void sameInputProducesSameValue() {
        long first = generator.generateLong(42001L, "payment/event", "paymentSucceeded/1");
        long second = generator.generateLong(42001L, "payment/event", "paymentSucceeded/1");

        assertThat(first).isEqualTo(second);
    }

    /**
     * logicalKey가 다르면 값도 달라야 개별 Event가 서로 다른 jitter·순서를 가질 수 있다.
     */
    @Test
    void differentLogicalKeyProducesDifferentValue() {
        long first = generator.generateLong(42001L, "payment/event", "paymentSucceeded/1");
        long second = generator.generateLong(42001L, "payment/event", "paymentSucceeded/2");

        assertThat(first).isNotEqualTo(second);
    }

    /**
     * 새로운 logicalKey를 추가해도 기존 key의 값은 변하지 않아야 Scenario 확장이
     * 기존 계획을 깨뜨리지 않는다.
     */
    @Test
    void insertingNewKeyDoesNotShiftExistingValues() {
        long before = generator.generateLong(73001L, "booking/delivery", "version-2");

        // 다른 key로 아무리 많은 값을 생성해도 기존 key 결과는 독립적으로 유지된다.
        for (int i = 0; i < 100; i++) {
            generator.generateLong(73001L, "booking/delivery", "filler-" + i);
        }

        assertThat(generator.generateLong(73001L, "booking/delivery", "version-2")).isEqualTo(before);
    }

    /**
     * bound 범위 결과는 항상 0 이상 bound 미만이어야 reorder index 계산이 안전하다.
     */
    @Test
    void intValueStaysWithinBound() {
        for (int i = 0; i < 200; i++) {
            int value = generator.generateInt(i, "probe/jitter", "attempt-" + i, 500);
            assertThat(value).isBetween(0, 499);
        }
    }

    /**
     * 0 이하 bound는 계약 위반이므로 즉시 거부해야 조용한 분배 오류를 막을 수 있다.
     */
    @Test
    void rejectsNonPositiveBound() {
        assertThatThrownBy(() -> generator.generateInt(1L, "ns", "key", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
