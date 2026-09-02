package io.outcomeprobe.probe;

import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 사용자 제공 assertion 정규식을 제한된 자원 안에서 전체 문자열과 매칭한다.
 *
 * <p>책임: 패턴·입력 길이를 먼저 제한하고 Java 정규식 엔진이 입력을 읽을 때마다
 * 단조 시간 예산을 검사해 backtracking 실행을 호출 스레드 안에서 종료한다.</p>
 * <p>핵심 불변식: 별도 Worker나 취소 불가능한 {@code Future}를 만들지 않는다.
 * 따라서 timeout 뒤에도 정규식 스레드가 남아 실행되는 누수가 없다.</p>
 */
final class SafeRegexMatcher {

    private static final int MAX_INPUT_LENGTH = 4096;
    private static final int MAX_PATTERN_LENGTH = 1024;
    private static final int MAX_GROUP_NESTING = 32;
    private static final long EXECUTION_TIMEOUT_NANOS = Duration.ofMillis(50).toNanos();

    /** 인스턴스 생성을 막는다. */
    private SafeRegexMatcher() {
    }

    /**
     * 운영 기본 제한으로 정규식 전체 매칭을 수행한다.
     *
     * @param regex 사용자 제공 정규식
     * @param input assertion 실제 문자열
     * @return 제한 안에서 전체 문자열이 일치하면 {@code true}
     */
    static boolean matches(String regex, String input) {
        return evaluate(regex, input, EXECUTION_TIMEOUT_NANOS, System::nanoTime) == Outcome.MATCHED;
    }

    /**
     * 주어진 단조 시계와 시간 예산으로 평가 결과를 구한다.
     *
     * <p>패키지 범위 진입점은 실제 대기 없이 timeout 회귀를 검증하기 위한 것이다.</p>
     *
     * @param regex 사용자 제공 정규식
     * @param input assertion 실제 문자열
     * @param timeoutNanos 허용 실행시간
     * @param nanoTime 단조 나노초 시계
     * @return 일치·불일치·거부·시간초과 결과
     */
    static Outcome evaluate(String regex, String input, long timeoutNanos, LongSupplier nanoTime) {
        if (regex == null || input == null || nanoTime == null || timeoutNanos <= 0
                || input.length() > MAX_INPUT_LENGTH
                || !hasBoundedPatternStructure(regex)) {
            return Outcome.REJECTED;
        }

        EvaluationDeadline deadline = new EvaluationDeadline(nanoTime, timeoutNanos);
        try {
            Pattern pattern = Pattern.compile(regex);
            if (deadline.expired()) {
                return Outcome.TIMED_OUT;
            }
            boolean matched = pattern.matcher(new DeadlineCharSequence(input, deadline)).matches();
            return deadline.expired()
                    ? Outcome.TIMED_OUT
                    : matched ? Outcome.MATCHED : Outcome.NOT_MATCHED;
        } catch (PatternSyntaxException exception) {
            return Outcome.REJECTED;
        } catch (RegexEvaluationTimedOut exception) {
            return Outcome.TIMED_OUT;
        }
    }

    /**
     * 컴파일 전에 패턴 길이와 괄호 중첩 깊이를 제한한다.
     */
    private static boolean hasBoundedPatternStructure(String regex) {
        if (regex.length() > MAX_PATTERN_LENGTH) {
            return false;
        }
        int nesting = 0;
        boolean escaped = false;
        boolean characterClass = false;
        boolean quoted = false;
        for (int index = 0; index < regex.length(); index++) {
            char current = regex.charAt(index);
            if (quoted) {
                if (current == '\\' && index + 1 < regex.length()
                        && regex.charAt(index + 1) == 'E') {
                    quoted = false;
                    index++;
                }
                continue;
            }
            if (escaped) {
                if (!characterClass && current == 'Q') {
                    quoted = true;
                }
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '[' && !characterClass) {
                characterClass = true;
                continue;
            }
            if (current == ']' && characterClass) {
                characterClass = false;
                continue;
            }
            if (characterClass) {
                continue;
            }
            if (current == '(' && ++nesting > MAX_GROUP_NESTING) {
                return false;
            }
            if (current == ')' && nesting > 0) {
                nesting--;
            }
        }
        return true;
    }

    /** 제한 정규식 평가의 내부 결과다. */
    enum Outcome {
        MATCHED,
        NOT_MATCHED,
        REJECTED,
        TIMED_OUT
    }

    /**
     * 단조 시계 기준으로 하나의 정규식 평가 시간 예산을 추적한다.
     */
    private static final class EvaluationDeadline {

        private final LongSupplier nanoTime;
        private final long startedAt;
        private final long timeoutNanos;

        /**
         * 평가 시작 시각과 허용 시간을 고정한다.
         *
         * @param nanoTime 단조 나노초 시계
         * @param timeoutNanos 허용 실행시간
         */
        private EvaluationDeadline(LongSupplier nanoTime, long timeoutNanos) {
            this.nanoTime = nanoTime;
            this.startedAt = nanoTime.getAsLong();
            this.timeoutNanos = timeoutNanos;
        }

        /**
         * 현재 평가가 허용 시간을 소진했는지 확인한다.
         *
         * @return 제한시간을 넘겼으면 {@code true}
         */
        private boolean expired() {
            return nanoTime.getAsLong() - startedAt >= timeoutNanos;
        }

        /** 남은 시간이 없으면 정규식 평가를 즉시 종료한다. */
        private void verifyRemainingTime() {
            if (expired()) {
                throw RegexEvaluationTimedOut.INSTANCE;
            }
        }
    }

    /**
     * Java 정규식 엔진의 모든 입력 접근에 deadline 검사를 삽입한다.
     */
    private static final class DeadlineCharSequence implements CharSequence {

        private final CharSequence delegate;
        private final EvaluationDeadline deadline;

        /**
         * 원본 문자열과 공유 deadline을 결속한다.
         *
         * @param delegate 원본 문자열
         * @param deadline 공유 평가 기한
         */
        private DeadlineCharSequence(CharSequence delegate, EvaluationDeadline deadline) {
            this.delegate = delegate;
            this.deadline = deadline;
        }

        /**
         * 기한을 검사한 뒤 문자열 길이를 반환한다.
         *
         * @return 원본 문자열 길이
         */
        @Override
        public int length() {
            deadline.verifyRemainingTime();
            return delegate.length();
        }

        /**
         * 기한을 검사한 뒤 지정 위치 문자를 반환한다.
         *
         * @param index 문자 위치
         * @return 지정 위치 문자
         */
        @Override
        public char charAt(int index) {
            deadline.verifyRemainingTime();
            return delegate.charAt(index);
        }

        /**
         * 같은 기한을 공유하는 부분 문자열 view를 반환한다.
         *
         * @param start 시작 위치
         * @param end 종료 위치
         * @return 기한 검사가 유지되는 부분 문자열
         */
        @Override
        public CharSequence subSequence(int start, int end) {
            deadline.verifyRemainingTime();
            return new DeadlineCharSequence(delegate.subSequence(start, end), deadline);
        }

        /**
         * 기한을 검사한 뒤 원본 문자열을 반환한다.
         *
         * @return 원본 문자열 표현
         */
        @Override
        public String toString() {
            deadline.verifyRemainingTime();
            return delegate.toString();
        }
    }

    /**
     * stack trace 비용 없이 정규식 엔진을 탈출시키는 내부 제어 예외다.
     */
    private static final class RegexEvaluationTimedOut extends RuntimeException {

        private static final long serialVersionUID = 1L;
        private static final RegexEvaluationTimedOut INSTANCE = new RegexEvaluationTimedOut();

        /** stack trace를 만들지 않는 내부 timeout 예외를 생성한다. */
        private RegexEvaluationTimedOut() {
            super(null, null, false, false);
        }
    }
}
