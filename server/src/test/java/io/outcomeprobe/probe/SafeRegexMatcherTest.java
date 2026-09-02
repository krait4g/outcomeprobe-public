package io.outcomeprobe.probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

/**
 * {@link SafeRegexMatcher}의 길이·시간 제한과 호출 스레드 실행 계약을 검증한다.
 *
 * <p>검출 가능한 결함: backtracking 정규식이 timeout 뒤에도 Worker를 점유하거나,
 * 별도 실행 스레드를 남기거나, 과도한 패턴 구조가 Java 정규식 컴파일러에 전달되는 문제.</p>
 */
class SafeRegexMatcherTest {

    /** 일반적인 전체 문자열 정규식은 기존 assertion 의미를 유지해야 한다. */
    @Test
    void matchesOrdinaryRegexWithoutChangingSemantics() {
        assertThat(SafeRegexMatcher.matches("^TS\\d{8}$", "TS00000042")).isTrue();
        assertThat(SafeRegexMatcher.matches("^DR\\d{8}$", "CAM00000030")).isFalse();
    }

    /**
     * backtracking 패턴은 가상 deadline을 넘는 즉시 같은 호출 스레드에서 종료돼야 한다.
     */
    @Test
    void terminatesBacktrackingAtDeadlineWithoutStartingAnotherThread() {
        Thread caller = Thread.currentThread();
        AtomicBoolean foreignThreadObserved = new AtomicBoolean(false);
        AtomicLong virtualNanos = new AtomicLong();
        LongSupplier tickingClock = () -> {
            if (Thread.currentThread() != caller) {
                foreignThreadObserved.set(true);
            }
            return virtualNanos.getAndAdd(10L);
        };

        SafeRegexMatcher.Outcome outcome = SafeRegexMatcher.evaluate(
                "^(a+)+$", "a".repeat(128) + "!", 25L, tickingClock);

        assertThat(outcome).isEqualTo(SafeRegexMatcher.Outcome.TIMED_OUT);
        assertThat(foreignThreadObserved).isFalse();
    }

    /** 긴 패턴과 과도한 그룹 중첩은 컴파일 전에 거부해야 한다. */
    @Test
    void rejectsOversizedAndDeeplyNestedPatternsBeforeCompilation() {
        String oversized = "a".repeat(1025);
        String deeplyNested = "(".repeat(33) + "a" + ")".repeat(33);

        assertThat(SafeRegexMatcher.evaluate(
                oversized, oversized, Long.MAX_VALUE, System::nanoTime))
                .isEqualTo(SafeRegexMatcher.Outcome.REJECTED);
        assertThat(SafeRegexMatcher.evaluate(
                deeplyNested, "a", Long.MAX_VALUE, System::nanoTime))
                .isEqualTo(SafeRegexMatcher.Outcome.REJECTED);
    }
}
