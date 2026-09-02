package io.outcomeprobe.emulator;

import java.time.Duration;

/**
 * Provider commit 트랜잭션의 결과를 표현한다.
 *
 * <p>책임: 트랜잭션 내부에서 확정한 응답 계획과 fault 계획을 트랜잭션 밖
 * 응답 단계로 전달한다.</p>
 * <p>핵심 불변식: {@code conflict} 결과는 Provider 상태가 아무것도 바뀌지
 * 않았음을 의미한다. 호출자는 재조회 후 재시도하거나 충돌을 보고한다.</p>
 *
 * @param status Target에 노출할 HTTP 상태 코드
 * @param body 응답 body 바이트
 * @param responseDelay 응답 전 의도적 지연
 * @param conflict CAS 충돌로 commit이 무산됐는지 여부
 */
public record CommitOutcome(int status, byte[] body, Duration responseDelay, boolean conflict) {

    /**
     * CAS 충돌로 아무 상태도 바뀌지 않았음을 나타내는 결과를 만든다.
     *
     * @return 충돌 결과
     */
    public static CommitOutcome conflicted() {
        return new CommitOutcome(409, new byte[0], Duration.ZERO, true);
    }
}
