package io.outcomeprobe.scenario;

import io.outcomeprobe.foundation.ProblemCode;

/**
 * Artifact 해석·검증·import 실패를 나타내는 예외다.
 *
 * <p>책임: 실패 원인을 {@link ProblemCode}와 필드 경로로 구조화해 API가
 * problem+json으로 기계 판독 가능한 오류를 반환하게 한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: 이 예외는 Scenario가 의도한 업무 실패가 아니라
 * 사용자 입력 오류다. 따라서 Run의 FAILED가 아니라 요청 시점의 4xx로 보고된다.</p>
 */
public class ArtifactImportException extends RuntimeException {

    private final ProblemCode problemCode;
    private final String field;

    /**
     * 문제 코드와 필드 경로를 담아 예외를 생성한다.
     *
     * @param problemCode 보고할 문제 코드
     * @param field 문제가 된 필드 경로, 없으면 null
     * @param message 사람이 읽는 한국어 설명
     */
    public ArtifactImportException(ProblemCode problemCode, String field, String message) {
        super(message);
        this.problemCode = problemCode;
        this.field = field;
    }

    /**
     * 문제 코드를 반환한다.
     *
     * @return 문제 코드
     */
    public ProblemCode problemCode() {
        return problemCode;
    }

    /**
     * 문제가 된 필드 경로를 반환한다.
     *
     * @return 필드 경로, 없으면 null
     */
    public String field() {
        return field;
    }
}
