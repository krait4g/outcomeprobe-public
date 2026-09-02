package io.outcomeprobe.emulator;

import io.outcomeprobe.scenario.contract.ProviderContract;

/**
 * 활성 alias에 연결된 Run의 Emulator 실행 문맥이다.
 *
 * <p>책임: Emulator ingress가 scenario_run 테이블을 직접 조회하지 않고도
 * 어느 Run의 어느 계약으로 요청을 처리할지 알게 한다.</p>
 * <p>핵심 불변식: 이 문맥은 alias lock 획득 시점의 snapshot이다. Run 도중
 * 계약이 바뀌지 않으므로 ingress 처리 동안 안정적이다.</p>
 *
 * @param runId 활성 Run 식별자
 * @param alias Emulator alias
 * @param seed 결정론 생성에 사용하는 Run seed
 * @param contract 이 Run이 사용하는 Provider 계약 snapshot
 */
public record EmulatorRunContext(
        String runId,
        String alias,
        long seed,
        ProviderContract contract) {
}
