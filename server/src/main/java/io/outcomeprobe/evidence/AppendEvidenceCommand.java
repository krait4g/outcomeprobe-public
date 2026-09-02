package io.outcomeprobe.evidence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Evidence append 요청을 표현하는 불변 명령이다.
 *
 * <p>책임: 호출자가 필요한 필드만 채우고 나머지는 기본값으로 남길 수 있게 해
 * Evidence 기록 호출부를 짧고 일관되게 유지한다.</p>
 * <p>핵심 불변식: attributes는 문자열 값만 허용한다. 동적 구조가 필요한 내용은
 * payloadSummary JSON에 담는다.</p>
 *
 * @param runId 소속 Run 식별자
 * @param type 사건 유형
 * @param source 사건 생산 주체
 * @param stepId 관련 Step 식별자, 없으면 null
 * @param correlationId 상관 식별자, 없으면 null
 * @param resourceType 관련 resource 유형, 없으면 null
 * @param resourceId 관련 resource 식별자, 없으면 null
 * @param attributes 기계 판정용 문자열 속성
 * @param payloadSummary redaction된 payload 요약 JSON, 없으면 null
 * @param bodyHash 원본 body 해시, 없으면 null
 * @param redactionApplied redaction 적용 여부
 * @param logicalTimeMs Scenario 계획상 Run 시작 대비 상대 시각, 관측 사건이면 null
 * @param observedAt 실제 관측 시각, 저장 시각을 사용하려면 null
 */
public record AppendEvidenceCommand(
        String runId,
        EvidenceType type,
        EvidenceSource source,
        String stepId,
        String correlationId,
        String resourceType,
        String resourceId,
        Map<String, String> attributes,
        String payloadSummary,
        String bodyHash,
        boolean redactionApplied,
        Long logicalTimeMs,
        Instant observedAt) {

    /**
     * 최소 필수 필드로 append 명령을 만든다.
     *
     * @param runId 소속 Run 식별자
     * @param type 사건 유형
     * @param source 사건 생산 주체
     * @return 나머지 필드가 비어 있는 append 명령
     */
    public static AppendEvidenceCommand of(String runId, EvidenceType type, EvidenceSource source) {
        return new AppendEvidenceCommand(runId, type, source, null, null, null, null,
                Map.of(), null, null, false, null, null);
    }

    /**
     * 기계 판정용 속성을 추가한 새 명령을 반환한다.
     *
     * @param key 속성 이름
     * @param value 속성 값
     * @return 속성이 추가된 새 명령
     */
    public AppendEvidenceCommand withAttribute(String key, String value) {
        Map<String, String> extended = new LinkedHashMap<>(attributes);
        extended.put(key, value);
        return new AppendEvidenceCommand(runId, type, source, stepId, correlationId,
                resourceType, resourceId, Map.copyOf(extended), payloadSummary, bodyHash,
                redactionApplied, logicalTimeMs, observedAt);
    }

    /**
     * Step·resource 상관 정보를 한 번에 추가한 새 명령을 반환한다.
     *
     * @param newStepId 관련 Step 식별자
     * @param newResourceType 관련 resource 유형
     * @param newResourceId 관련 resource 식별자
     * @return 상관 정보가 채워진 새 명령
     */
    public AppendEvidenceCommand withCorrelation(String newStepId, String newResourceType, String newResourceId) {
        return new AppendEvidenceCommand(runId, type, source, newStepId, correlationId,
                newResourceType, newResourceId, attributes, payloadSummary, bodyHash,
                redactionApplied, logicalTimeMs, observedAt);
    }

    /**
     * payload 요약과 원본 해시를 추가한 새 명령을 반환한다.
     *
     * @param newPayloadSummary redaction된 payload 요약 JSON
     * @param newBodyHash 원본 body 해시
     * @param applied redaction 적용 여부
     * @return payload 정보가 채워진 새 명령
     */
    public AppendEvidenceCommand withPayload(String newPayloadSummary, String newBodyHash, boolean applied) {
        return new AppendEvidenceCommand(runId, type, source, stepId, correlationId,
                resourceType, resourceId, attributes, newPayloadSummary, newBodyHash, applied,
                logicalTimeMs, observedAt);
    }

    /**
     * 계획된 사건에만 사용할 Run 시작 대비 상대 시각을 지정한다.
     *
     * @param newLogicalTimeMs Run 시작부터의 계획 상대 밀리초
     * @return logical time이 지정된 새 명령
     */
    public AppendEvidenceCommand withLogicalTimeMs(long newLogicalTimeMs) {
        if (newLogicalTimeMs < 0) {
            throw new IllegalArgumentException("Evidence logical time은 0 이상이어야 합니다.");
        }
        return new AppendEvidenceCommand(runId, type, source, stepId, correlationId,
                resourceType, resourceId, attributes, payloadSummary, bodyHash, redactionApplied,
                newLogicalTimeMs, observedAt);
    }

    /**
     * 외부 응답처럼 실제로 사건을 관측한 시각을 지정한다.
     *
     * <p>지정하지 않은 기존 호출은 저장 시점의 clock을 사용해 이전 동작과 호환된다.</p>
     *
     * @param newObservedAt 실제 관측 시각
     * @return observed time이 지정된 새 명령
     */
    public AppendEvidenceCommand withObservedAt(Instant newObservedAt) {
        return new AppendEvidenceCommand(runId, type, source, stepId, correlationId,
                resourceType, resourceId, attributes, payloadSummary, bodyHash, redactionApplied,
                logicalTimeMs, newObservedAt);
    }
}
