package io.outcomeprobe.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** pgjdbc 반올림 경계에서도 저장 전 시각 정규화가 결정론적인지 검증한다. */
class PostgresqlTimestampTest {

    /** nanosecond 잔여 499·500·999를 모두 같은 microsecond 절삭 규칙으로 고정한다. */
    @Test
    void truncatesAllDriverRoundingBoundariesBeforePersistence() {
        for (String input : java.util.List.of(
                "2026-01-01T00:00:00.123456499Z",
                "2026-01-01T00:00:00.123456500Z",
                "2026-01-01T00:00:00.123456999Z")) {
            assertThat(PostgresqlTimestamp.normalize(Instant.parse(input)))
                    .isEqualTo(Instant.parse("2026-01-01T00:00:00.123456Z"));
        }
    }
}
