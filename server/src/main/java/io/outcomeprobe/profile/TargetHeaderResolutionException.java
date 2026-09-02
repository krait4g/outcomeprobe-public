package io.outcomeprobe.profile;

import io.outcomeprobe.foundation.ProblemCode;

/** 요청 전 header 병합·검증 실패를 secret 없는 문제 코드로 전달한다. */
public final class TargetHeaderResolutionException extends RuntimeException {

    private final ProblemCode problemCode;

    /**
     * 문제 코드와 안전한 설명으로 예외를 만든다.
     *
     * @param problemCode 실행 결과에 사용할 문제 코드
     * @param message header 값이 포함되지 않은 설명
     */
    public TargetHeaderResolutionException(ProblemCode problemCode, String message) {
        super(message);
        this.problemCode = problemCode;
    }

    /**
     * 실행 결과에 사용할 문제 코드를 반환한다.
     *
     * @return 고정 문제 코드
     */
    public ProblemCode problemCode() {
        return problemCode;
    }
}
