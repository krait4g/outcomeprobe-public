package io.outcomeprobe.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@link HashValue}의 형식 검증과 SHA-256 계산 규칙을 검증한다.
 *
 * <p>검출 가능한 결함: 대문자·잘못된 길이 해시의 수용, 인코딩이 다른 문자열에서
 * 해시가 달라지는 문제, null 입력 수용.</p>
 */
class HashValueTest {

    /**
     * 잘못된 형식의 해시 문자열은 생성 단계에서 거부돼야 한다.
     *
     * <p>artifact content hash 비교가 잘못된 형식으로 느슨해지는 것을 막는다.</p>
     */
    @Test
    void rejectsInvalidHashFormat() {
        assertThatThrownBy(() -> new HashValue("not-a-hash"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HashValue(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HashValue("ABC" + "0".repeat(61)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 같은 UTF-8 문자열은 항상 같은 해시를 만들어야 content hash 안정성이 유지된다.
     */
    @Test
    void producesStableHashForSameText() {
        HashValue first = HashValue.sha256("outcomeprobe");
        HashValue second = HashValue.sha256("outcomeprobe");

        assertThat(first).isEqualTo(second);
        assertThat(first.hexValue()).hasSize(64);
    }

    /**
     * 서로 다른 입력은 서로 다른 해시를 만들어야 artifact 충돌 검사가 의미를 가진다.
     */
    @Test
    void producesDifferentHashForDifferentText() {
        assertThat(HashValue.sha256("scenario-a")).isNotEqualTo(HashValue.sha256("scenario-b"));
    }
}
