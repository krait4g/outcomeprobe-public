package io.outcomeprobe.cli;

/**
 * CLI가 네트워크 요청 전에 발견한 안전한 설정 오류다.
 *
 * <p>책임: Control API token 누락·형식 오류처럼 사용자가 고칠 수 있는 문제를
 * 서버 장애와 구분한다. 핵심 불변식은 예외 메시지에 token 원문을 넣지 않는 것이다.</p>
 */
public final class CliConfigurationException extends RuntimeException {

    /**
     * 원문 credential이 없는 안전한 설명으로 설정 오류를 만든다.
     *
     * @param safeMessage credential 원문을 포함하지 않는 사용자용 설명
     */
    public CliConfigurationException(String safeMessage) {
        super(safeMessage);
    }
}
