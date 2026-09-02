package io.outcomeprobe.emulator;

import tools.jackson.databind.JsonNode;
import java.util.Optional;

/**
 * Provider idempotency 영수증을 저장·조회하는 포트다.
 *
 * <p>책임: 같은 idempotency key 재호출이 Provider state를 중복 변경하지 않게
 * 최초 결과를 재사용할 근거를 제공한다.</p>
 * <p>핵심 불변식: 같은 key에 다른 request hash는 명시적 conflict다. 조용히
 * 덮어쓰지 않는다.</p>
 */
public interface InvocationReceiptPort {

    /**
     * 영수증을 조회한다.
     *
     * @param runId Run 식별자
     * @param operationKey operation key
     * @param idempotencyKeyHash 멱등 키 해시
     * @return 존재하면 최초 결과 snapshot, 없으면 empty
     */
    Optional<JsonNode> findResult(String runId, String operationKey, String idempotencyKeyHash);

    /**
     * 최초 commit 결과를 영수증으로 저장한다.
     *
     * @param runId Run 식별자
     * @param operationKey operation key
     * @param idempotencyKeyHash 멱등 키 해시
     * @param requestHash 요청 본문 해시
     * @param resourceType resource 유형
     * @param resourceId 자원 식별자
     * @param resultSnapshot 최초 응답 계획 snapshot
     */
    void save(String runId, String operationKey, String idempotencyKeyHash, String requestHash,
              String resourceType, String resourceId, JsonNode resultSnapshot);

    /**
     * 동일 key에 저장된 요청 해시를 조회한다.
     *
     * @param runId Run 식별자
     * @param operationKey operation key
     * @param idempotencyKeyHash 멱등 키 해시
     * @return 존재하면 요청 해시
     */
    Optional<String> findRequestHash(String runId, String operationKey, String idempotencyKeyHash);
}
