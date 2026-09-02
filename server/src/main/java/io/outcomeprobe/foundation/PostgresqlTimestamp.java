package io.outcomeprobe.foundation;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** PostgreSQL {@code timestamptz}의 microsecond 정밀도에 실행 시각을 맞춘다. */
public final class PostgresqlTimestamp {

    /** 인스턴스 생성을 막는다. */
    private PostgresqlTimestamp() {
    }

    /**
     * pgjdbc의 nanosecond 반올림이 row와 JSON binding을 다르게 만들지 않도록 먼저 절삭한다.
     *
     * @param value 저장과 hash binding에 함께 사용할 시각
     * @return microsecond 정밀도로 정규화한 시각
     */
    public static Instant normalize(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
    }
}
