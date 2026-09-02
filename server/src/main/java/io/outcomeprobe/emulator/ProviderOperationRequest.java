package io.outcomeprobe.emulator;

import io.outcomeprobe.scenario.contract.ProviderContract;
import java.util.Map;

/**
 * Emulator가 처리할 Provider operation 요청의 전체 문맥이다.
 *
 * <p>책임: orchestration이 수집한 요청·매칭·자원 정보를 하나의 값으로 묶어
 * commit 트랜잭션 경계에 전달한다.</p>
 * <p>핵심 불변식: 이 요청에 담긴 body·header는 redaction 이전 원본이다.
 * matcher와 서명 검증은 항상 이 원본에 대해 수행된다.</p>
 *
 * @param runId 소속 Run 식별자
 * @param seed Run 결정론 seed
 * @param operationKey 매칭된 operation key
 * @param operation 매칭된 operation 계약
 * @param expectation 매칭된 expectation
 * @param resource 현재 Provider 자원 상태
 * @param resourceId 추출된 자원 식별자
 * @param headers 원본 요청 header
 * @param bodyText 원본 요청 body 문자열
 * @param pathVariables path template에서 추출한 변수
 * @param idempotencyKeyHash 멱등 키 해시, 없으면 null
 * @param requestHash 요청 body 해시
 */
public record ProviderOperationRequest(
        String runId,
        long seed,
        String operationKey,
        ProviderContract.OperationSpec operation,
        ExpectedInvocation expectation,
        ProviderResource resource,
        String resourceId,
        Map<String, String> headers,
        String bodyText,
        Map<String, String> pathVariables,
        String idempotencyKeyHash,
        String requestHash) {
}
