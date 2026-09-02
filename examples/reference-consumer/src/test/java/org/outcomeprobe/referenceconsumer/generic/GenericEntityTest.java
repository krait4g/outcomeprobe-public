package org.outcomeprobe.referenceconsumer.generic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 복원력 Generic entity의 중복·version guard 계약을 검증한다.
 */
class GenericEntityTest {

    /**
     * 낮은 version 이벤트는 최신 상태와 처리 횟수를 회귀시키지 않아야 한다.
     */
    @Test
    void olderVersionPreservesLatestState() {
        GenericEntity current = GenericEntity.initial("entity-1")
                .applyGuarded("event-v3", "UPDATED", 3)
                .orElseThrow();

        GenericEntity guarded = current.applyGuarded("event-v1", "STALE", 1)
                .orElseThrow();

        assertThat(guarded.state()).isEqualTo("UPDATED");
        assertThat(guarded.version()).isEqualTo(3);
        assertThat(guarded.processedEventCount()).isEqualTo(1);
        assertThat(guarded.processedEventIds()).containsExactlyInAnyOrder(
                "event-v3", "event-v1");
    }
}
