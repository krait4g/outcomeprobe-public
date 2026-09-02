package io.outcomeprobe.evidence;

import java.util.Map;

/**
 * Evidence 조회·판정에 사용할 조건을 표현한다.
 *
 * <p>책임: evidence.await, evidence.assert, report 요약이 동일한 조건 모델로
 * Timeline을 검색하게 한다.</p>
 * <p>핵심 불변식: attributes 조건은 전부 일치(all-of) 의미다. 부분 일치나 정규식은
 * 허용하지 않아 판정이 결정론적으로 유지된다.</p>
 *
 * @param type 요구 사건 유형
 * @param attributes 전부 일치해야 하는 문자열 속성 조건, 비어 있으면 유형만 본다
 */
public record EvidenceMatcher(EvidenceType type, Map<String, String> attributes) {

    /**
     * 유형만 요구하는 조건을 만든다.
     *
     * @param type 요구 사건 유형
     * @return 속성 조건이 없는 matcher
     */
    public static EvidenceMatcher ofType(EvidenceType type) {
        return new EvidenceMatcher(type, Map.of());
    }
}
