package io.outcomeprobe.emulator;

import java.util.Optional;

/**
 * Emulator alias별 활성 Run 독점을 관리하는 포트다.
 *
 * <p>책임: Target이 고정 base URL을 사용하는 현실을 반영해, 같은 alias에서
 * 동시에 두 Run이 Provider truth를 섞지 못하게 한다.</p>
 * <p>핵심 불변식: 활성 alias는 DB 부분 unique index로 보호되며 JVM-local lock에
 * 의존하지 않는다. 따라서 다중 Worker·재시작 상황에서도 독점이 유지된다.</p>
 */
public interface AliasLockPort {

    /**
     * alias 독점을 시도한다.
     *
     * <p>이미 활성 Run이 있으면 false를 반환한다. 이 실패는 재시도 가능한
     * 경쟁 상태이며 호출자는 PROVIDER_ALIAS_BUSY 보고 또는 대기 정책을 선택한다.</p>
     *
     * @param alias 독점할 alias
     * @param runId 독점하려는 Run 식별자
     * @param context Emulator가 사용할 Run 문맥 snapshot
     * @return 획득 성공 여부
     */
    boolean tryAcquire(String alias, String runId, EmulatorRunContext context);

    /**
     * alias 독점을 해제한다.
     *
     * <p>Run terminal 전환 시 호출한다. 이미 해제됐거나 다른 Run 소유면 무시한다.</p>
     *
     * @param alias 해제할 alias
     * @param runId 소유 Run 식별자
     */
    void release(String alias, String runId);

    /**
     * alias에 연결된 활성 Run 문맥을 조회한다.
     *
     * @param alias 조회할 alias
     * @return 활성 문맥, 없으면 empty
     */
    Optional<EmulatorRunContext> activeContext(String alias);
}
