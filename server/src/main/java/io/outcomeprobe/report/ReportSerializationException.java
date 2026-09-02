package io.outcomeprobe.report;

/**
 * report 표현을 안전하게 직렬화할 수 없음을 나타낸다.
 *
 * <p>책임: HTML 같은 report 표현 계층이 빈 대체 문서로 성공하지 않고 원인 예외를
 * 상위 경계에 전달하게 한다. 외부 응답에는 이 예외의 고정 메시지만 사용한다.</p>
 */
public class ReportSerializationException extends RuntimeException {

    /**
     * 안전한 메시지와 내부 원인을 분리해 예외를 생성한다.
     *
     * @param safeMessage 외부로 노출해도 되는 고정 메시지
     * @param cause 내부 진단용 원인, 없으면 null
     */
    public ReportSerializationException(String safeMessage, Throwable cause) {
        super(safeMessage, cause);
    }
}
