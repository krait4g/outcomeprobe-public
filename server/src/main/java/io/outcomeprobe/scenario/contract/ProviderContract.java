package io.outcomeprobe.scenario.contract;

import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * Provider Emulator가 흉내 낼 외부 제공자 API·상태·Callback 계약이다.
 *
 * <p>책임: Emulator ingress가 요청을 operation에 매칭하고, Provider truth 전이를
 * 검증하며, 응답 template과 Callback payload를 렌더링하는 기준을 제공한다.</p>
 * <p>핵심 불변식: 이 계약에는 secret 값이 들어갈 수 없다. 서명 secret은 오직
 * secretRef 이름만 보유한다. 또한 임의 script·정규식 치환 코드를 허용하지 않는다.</p>
 *
 * @param emulatorAlias Target이 base URL로 사용하는 alias
 * @param resourceTypes resource 유형별 상태 기계 정의
 * @param operations operation key별 요청·전이·응답 정의
 * @param callbacks callback key별 event payload·서명 정의
 */
public record ProviderContract(
        String emulatorAlias,
        Map<String, ResourceTypeSpec> resourceTypes,
        Map<String, OperationSpec> operations,
        Map<String, CallbackSpec> callbacks) {

    /**
     * resource 유형 하나의 canonical 상태 기계 정의다.
     *
     * @param initialStates seed가 허용하는 초기 상태 목록
     * @param terminalStates terminal 상태 목록
     * @param allowedTransitions 상태에서 상태로 허용되는 전이 목록
     */
    public record ResourceTypeSpec(
            List<String> initialStates,
            List<String> terminalStates,
            Map<String, List<String>> allowedTransitions) {
    }

    /**
     * Provider operation 하나의 요청 매칭·상태 전이·응답 정의다.
     *
     * @param request HTTP 요청 매칭 규칙
     * @param transition 허용 전이와 committed 상태
     * @param successResponse 정상 응답 template
     */
    public record OperationSpec(
            RequestSpec request,
            TransitionSpec transition,
            SuccessResponseSpec successResponse) {
    }

    /**
     * operation HTTP 요청의 매칭·추출 규칙이다.
     *
     * @param method HTTP method
     * @param path 경로 template, 예: /v1/payments/{paymentId}/confirm
     * @param resourceType 이 operation이 다루는 resource 유형
     * @param resourceId resource ID 추출 규칙
     * @param idempotencyKey 멱등 키 추출 규칙, 없으면 null
     * @param bodyAssertions body 필수 필드 검증 규칙, 없으면 빈 목록
     */
    public record RequestSpec(
            String method,
            String path,
            String resourceType,
            ResourceRefSpec resourceId,
            IdempotencyKeySpec idempotencyKey,
            List<BodyAssertionSpec> bodyAssertions) {
    }

    /**
     * path·header·body에서 값을 추출하는 규칙이다.
     *
     * @param source 추출 위치(PATH, HEADER, BODY)
     * @param expression 위치별 표현식(path 변수명, header명, jsonPath)
     */
    public record ResourceRefSpec(String source, String expression) {
    }

    /**
     * idempotency key 추출 규칙이다.
     *
     * @param source 추출 위치(HEADER 등)
     * @param expression header 이름 또는 jsonPath
     * @param required 필수 여부, null이면 false로 취급
     */
    public record IdempotencyKeySpec(String source, String expression, Boolean required) {

        /**
         * 이 키가 필수 요구되는지 반환한다.
         *
         * @return 필수이면 true
         */
        public boolean isRequired() {
            return Boolean.TRUE.equals(required);
        }
    }

    /**
     * 요청 body의 필수 필드 존재 검증 규칙이다.
     *
     * @param jsonPath 검증할 JSONPath
     * @param required 필수 여부
     */
    public record BodyAssertionSpec(String jsonPath, Boolean required) {
    }

    /**
     * operation이 수행하는 상태 전이 정의다.
     *
     * @param from 전이가 허용되는 현재 상태 목록
     * @param intermediate 선택적 중간 상태, 없으면 null
     * @param committed 최종 commit 상태
     */
    public record TransitionSpec(List<String> from, String intermediate, String committed) {
    }

    /**
     * 정상 응답 template 정의다.
     *
     * @param status HTTP 상태 코드
     * @param body 허용 변수 token을 포함할 수 있는 JSON template
     */
    public record SuccessResponseSpec(int status, JsonNode body) {
    }

    /**
     * Callback event payload와 서명 계약이다.
     *
     * @param eventType payload에 표시되는 event 유형
     * @param resourceType 관련 resource 유형
     * @param versionField payload 내 resource version JSONPath
     * @param body event payload JSON template
     * @param signature 서명 알고리즘·header·secretRef, 없으면 null
     */
    public record CallbackSpec(
            String eventType,
            String resourceType,
            String versionField,
            JsonNode body,
            SignatureSpec signature) {
    }

    /**
     * Callback 서명 계약이다.
     *
     * @param algorithm 서명 알고리즘(NONE, HMAC_SHA256)
     * @param header 서명을 실어 보내는 header 이름
     * @param secretRef secret 참조 이름(원문 금지)
     * @param encoding 서명 인코딩(HEX_LOWER 등)
     * @param signedContent 서명 대상(RAW_BODY)
     */
    public record SignatureSpec(
            String algorithm,
            String header,
            String secretRef,
            String encoding,
            String signedContent) {
    }
}
