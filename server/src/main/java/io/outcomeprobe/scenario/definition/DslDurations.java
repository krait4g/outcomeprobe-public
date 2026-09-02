package io.outcomeprobe.scenario.definition;

import io.outcomeprobe.foundation.ProblemCode;
import io.outcomeprobe.scenario.ArtifactImportException;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DSL의 사람 읽는 duration 문자열을 {@link Duration}으로 변환한다.
 *
 * <p>책임: {@code 30s}, {@code 500ms}, {@code 10m} 같은 표기를 통일되게 해석해
 * Scenario timeout·delay·interval이 일관된 단위를 갖게 한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: ISO-8601 duration(P0DT1S)은 지원하지 않는다.
 * DSL은 짧은 표기만 허용한다.</p>
 */
public final class DslDurations {

    private static final Pattern PATTERN = Pattern.compile("^(\\d+)(ms|s|m|h)$");

    /**
     * 정적 도구 클래스의 인스턴스화를 막는다.
     */
    private DslDurations() {
    }

    /**
     * duration 문자열을 해석한다.
     *
     * @param text {@code 500ms}, {@code 30s}, {@code 5m}, {@code 1h} 형식 문자열
     * @return 해석된 Duration
     * @throws ArtifactImportException 형식이 잘못되면 발생한다
     */
    public static Duration parse(String text) {
        if (text == null) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, null,
                    "duration 값이 필요합니다.");
        }
        Matcher matcher = PATTERN.matcher(text.trim());
        if (!matcher.matches()) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, null,
                    "duration은 ms·s·m·h 단위의 숫자 문자열이어야 합니다: " + text);
        }
        long amount = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2)) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            default -> Duration.ofHours(amount);
        };
    }
}
