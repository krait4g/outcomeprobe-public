package io.outcomeprobe.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Evidence logical time과 observed time 명령 의미를 검증한다.
 *
 * <p>검증 불변식: 기존 factory 호출은 두 시각을 지정하지 않고, 계획 사건만
 * 0 이상의 logical time을 가지며 외부 관측은 실제 observedAt을 선택할 수 있다.</p>
 */
class AppendEvidenceCommandTimingTest {

    /** 기존 호출은 logical·observed 값을 비워 저장 adapter의 호환 기본값을 사용한다. */
    @Test
    void keepsExistingFactoryTimingOptional() {
        AppendEvidenceCommand command = AppendEvidenceCommand.of(
                "run-1", EvidenceType.RUN_QUEUED, EvidenceSource.ENGINE);

        assertThat(command.logicalTimeMs()).isNull();
        assertThat(command.observedAt()).isNull();
    }

    /** 계획 상대 시간과 실제 관측 시각을 서로 독립적으로 지정할 수 있어야 한다. */
    @Test
    void carriesLogicalAndObservedTimeIndependently() {
        Instant observedAt = Instant.parse("2026-08-06T10:00:00.123456789Z");
        AppendEvidenceCommand command = AppendEvidenceCommand.of(
                        "run-1", EvidenceType.CALLBACK_SCHEDULED, EvidenceSource.DELIVERY)
                .withLogicalTimeMs(250)
                .withObservedAt(observedAt);

        assertThat(command.logicalTimeMs()).isEqualTo(250L);
        assertThat(command.observedAt()).isEqualTo(observedAt);
    }

    /** Run 시작 전을 뜻하는 음수 logical time은 거부해야 한다. */
    @Test
    void rejectsNegativeLogicalTime() {
        AppendEvidenceCommand command = AppendEvidenceCommand.of(
                "run-1", EvidenceType.RUN_QUEUED, EvidenceSource.ENGINE);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> command.withLogicalTimeMs(-1));
    }
}
