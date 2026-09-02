package io.outcomeprobe.execution;

/**
 * 동시 실행 Run 수 상한 초과 시 발생하는 예외다.
 *
 * <p>책임: capacity 제한 거부를 ArtifactImportException(입력 오류)과 분리해
 * HTTP 429로 매핑할 수 있게 한다. Run 생성 접수 거부이며 입력 자체는 유효하다.</p>
 * <p>핵심 불변식: 이 예외는 재시도 가능한 일시적 상태(다른 Run 종료 후 재시도 성공)를
 * 나타내며, 영구 입력 오류와 혼동하지 않는다.</p>
 */
public class CapacityLimitExceededException extends RuntimeException {

    /**
     * 상한 초과 메시지를 담아 예외를 생성한다.
     *
     * @param message 초과 사유 설명
     */
    public CapacityLimitExceededException(String message) {
        super(message);
    }
}
