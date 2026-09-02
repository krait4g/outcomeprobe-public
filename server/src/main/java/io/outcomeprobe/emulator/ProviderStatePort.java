package io.outcomeprobe.emulator;

import java.util.Map;
import java.util.Optional;

/**
 * Provider truth 자원의 상태를 조회·변경하는 포트다.
 *
 * <p>책임: seed로 자원을 생성하고, operation commit을 version CAS로 원자 반영하고,
 * 조회 시 stale view 계획을 적용한다.</p>
 * <p>핵심 불변식: 모든 상태 변경은 기대 version과 함께 요청되며, 충돌 시
 * false를 반환한다. synchronized나 JVM-local lock으로 직렬화하지 않는다.</p>
 */
public interface ProviderStatePort {

    /**
     * provider.seed Step이 새 자원을 생성한다.
     *
     * @param resource 생성할 자원 상태
     */
    void seed(ProviderResource resource);

    /**
     * 자원을 조회한다.
     *
     * @param runId Run 식별자
     * @param resourceType resource 유형
     * @param resourceId 자원 식별자
     * @return 존재하면 자원, 없으면 empty
     */
    Optional<ProviderResource> find(String runId, String resourceType, String resourceId);

    /**
     * 기대 version을 기준으로 commit 상태를 반영한다.
     *
     * <p>왜 CAS인가: 동일 자원의 동시 operation 중 정확히 하나만 commit돼야
     * Provider truth가 훼손되지 않는다.</p>
     *
     * @param runId Run 식별자
     * @param resourceType resource 유형
     * @param resourceId 자원 식별자
     * @param expectedVersion 읽은 시점의 version
     * @param newCanonicalState commit할 canonical 상태
     * @param newVisibleState 조회 노출 상태(stale 계획에 따라 옛 상태일 수 있음)
     * @param remainingStaleQueryAttempts stale 조회 남은 횟수
     * @param visibleAfter 시간 기준 stale 만료 시각, 없으면 null
     * @param attributes 병합할 속성
     * @return CAS 성공 여부
     */
    boolean commitTransition(String runId, String resourceType, String resourceId,
                             long expectedVersion, String newCanonicalState, String newVisibleState,
                             int remainingStaleQueryAttempts, java.time.Instant visibleAfter,
                             Map<String, Object> attributes);

    /**
     * 조회 요청을 반영해 stale attempt를 소모하고 최신 visible 상태를 반환한다.
     *
     * <p>stale 횟수가 0이 되는 순간 visibleState를 canonicalState로 수렴시킨다.</p>
     *
     * @param runId Run 식별자
     * @param resourceType resource 유형
     * @param resourceId 자원 식별자
     * @return 조회 후 자원 상태, 없으면 empty
     */
    Optional<ProviderResource> consumeQuery(String runId, String resourceType, String resourceId);
}
