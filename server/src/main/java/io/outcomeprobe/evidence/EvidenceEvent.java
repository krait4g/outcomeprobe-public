package io.outcomeprobe.evidence;

import java.time.Instant;
import java.util.Map;

/**
 * Run 중에 실제로 발생한 사실 하나를 나타내는 append-only Evidence다.
 *
 * <p>책임: 판정 근거를 디버그 로그가 아닌 구조화된 사건으로 보존한다. Timeline 조회,
 * evidence.await 대기, evidence.assert 횟수·순서 판정이 모두 이 객체를 소비한다.</p>
 * <p>핵심 불변식: Evidence는 생성 후 수정·삭제하지 않는다. sequence는 Run 안에서
 * 단조 증가하며 DB trigger로도 보호된다.</p>
 * <p>외부에서 오해하기 쉬운 한계: logicalTimeMs은 Scenario 계획상 상대 시간이고
 * observedAt은 실제 관측 시각이다. 같은 seed라도 observedAt은 실행마다 달라질 수 있으며
 * 결정론 주장은 logical plan과 순서에만 적용된다.</p>
 *
 * @param id Evidence 고유 식별자
 * @param runId 소속 Run 식별자
 * @param sequence Run 안 단조 증가 순서
 * @param logicalTimeMs 계획상 Run 시작 대비 상대 시간, 계획 사건이 아니면 null
 * @param observedAt 실제 관측 시각
 * @param type 사건 유형
 * @param source 사건 생산 주체
 * @param stepId 관련 Step 식별자, 없으면 null
 * @param correlationId 요청·전달 상관 식별자, 없으면 null
 * @param resourceType 관련 Provider resource 유형, 없으면 null
 * @param resourceId 관련 Provider resource 식별자, 없으면 null
 * @param attributes 기계 판정용 문자열 속성 집합
 * @param payloadSummary redaction 적용된 payload 요약 JSON 문자열, 없으면 null
 * @param bodyHash redaction 이전 원본 body 해시, 없으면 null
 * @param redactionApplied redaction 적용 여부
 * @param createdAt 저장 시각
 */
public record EvidenceEvent(
        String id,
        String runId,
        long sequence,
        Long logicalTimeMs,
        Instant observedAt,
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
        Instant createdAt) {
}
