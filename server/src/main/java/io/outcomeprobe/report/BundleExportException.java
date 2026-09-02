package io.outcomeprobe.report;

import io.outcomeprobe.foundation.ProblemCode;

/**
 * strict Run Bundle을 안전하게 완성할 수 없음을 나타낸다.
 *
 * <p>책임: legacy·손상 snapshot 거부와 내부 직렬화 실패를 고정 ProblemCode로
 * 전달하면서, 원인 예외의 raw message가 Control API에 노출되지 않게 한다.</p>
 */
public class BundleExportException extends RuntimeException {

    private final ProblemCode problemCode;

    /**
     * 안전한 외부 메시지와 내부 원인을 분리해 예외를 생성한다.
     *
     * @param problemCode 외부에 보고할 문제 코드
     * @param safeMessage secret 또는 저장 원문이 없는 안전 메시지
     * @param cause 내부 진단용 원인, 없으면 null
     */
    public BundleExportException(ProblemCode problemCode, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.problemCode = problemCode;
    }

    /**
     * 외부에 보고할 문제 코드를 반환한다.
     *
     * @return 문제 코드
     */
    public ProblemCode problemCode() {
        return problemCode;
    }
}
