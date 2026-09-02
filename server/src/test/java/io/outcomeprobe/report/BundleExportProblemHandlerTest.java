package io.outcomeprobe.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.outcomeprobe.foundation.ProblemCode;
import io.outcomeprobe.interfaceapi.GlobalProblemHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * strict Bundle 예외가 일관된 HTTP problem으로 매핑되는지 검증한다.
 */
class BundleExportProblemHandlerTest {

    private final GlobalProblemHandler handler = new GlobalProblemHandler();

    /** legacy·손상 snapshot은 현재 Run 상태와의 충돌인 409로 보고해야 한다. */
    @Test
    @DisplayName("snapshot 손상 Bundle 오류는 409와 고정 문제 코드로 매핑한다")
    void corruptedSnapshotMapsToConflict() {
        BundleExportException exception = new BundleExportException(
                ProblemCode.ARTIFACT_SNAPSHOT_CORRUPTED,
                "Run Bundle을 생성할 수 없습니다.", new IllegalStateException("raw-secret"));

        ProblemDetail problem = handler.handleBundleExport(exception);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("ARTIFACT_SNAPSHOT_CORRUPTED");
        assertThat(problem.getProperties()).containsEntry(
                "code", "ARTIFACT_SNAPSHOT_CORRUPTED");
        assertThat(problem.getDetail()).doesNotContain("raw-secret");
    }

    /** 직렬화 실패는 사용자의 입력 오류가 아니라 500 내부 오류로 보고해야 한다. */
    @Test
    @DisplayName("Bundle 직렬화 오류는 500과 내부 오류 코드로 매핑한다")
    void serializationFailureMapsToInternalError() {
        BundleExportException exception = new BundleExportException(
                ProblemCode.INTERNAL_EXECUTION_ERROR,
                "Run Bundle 생성 중 내부 오류가 발생했습니다.", null);

        ProblemDetail problem = handler.handleBundleExport(exception);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getProperties()).containsEntry(
                "code", "INTERNAL_EXECUTION_ERROR");
    }
}
