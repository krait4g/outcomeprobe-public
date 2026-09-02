package io.outcomeprobe.profile;

import io.outcomeprobe.foundation.ProblemCode;
import io.outcomeprobe.scenario.ArtifactImportException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 검증된 Target Profile spec을 실행 시 사용하는 불변 도메인 모델로 변환한다.
 *
 * <p>책임: Artifact 조회 경계와 Run snapshot 무결성 경계가 완전히 같은 변환 규칙을
 * 사용하도록 단일 의미 매핑을 제공한다. 외부에서 이해하기 쉬운 한계는 JSON Schema
 * 검증 자체를 수행하지 않으며, 호출자가 전달한 spec의 알려진 필드만 안전한 실행
 * 모델로 옮긴다는 점이다.</p>
 */
@Component
public class TargetProfileSpecMapper {

    /**
     * Profile 이름과 spec을 실행 모델로 변환한다.
     *
     * <p>정식 {@code spec.allowedHosts}·{@code spec.allowedPorts} 배열이 있으면 이를
     * 우선하고, 기존 Artifact 호환을 위해서만 {@code spec.networkPolicy} 아래 배열을
     * fallback으로 읽는다. 이 우선순위는 조회와 snapshot 재검증에서 동일해야 한다.</p>
     *
     * @param name Artifact metadata에 고정된 Profile 이름
     * @param spec import 시 검증된 Target Profile spec
     * @return secret 원문을 포함하지 않는 실행용 Target Profile
     * @throws ArtifactImportException spec 구조 또는 base URI가 실행에 안전하지 않으면 발생한다
     */
    public TargetProfile toProfile(String name, JsonNode spec) {
        if (spec == null || !spec.isObject()) {
            throw new ArtifactImportException(ProblemCode.TARGET_PROFILE_NOT_FOUND,
                    "spec", "TargetProfile spec이 손상되었습니다: " + name);
        }
        String baseUri = spec.path("baseUri").asText(null);
        if (!TargetProfileUriPolicy.isSafe(baseUri)) {
            throw new ArtifactImportException(ProblemCode.TARGET_PROFILE_NOT_FOUND,
                    "spec.baseUri", "TargetProfile baseUri가 안전한 HTTP(S) URI가 아닙니다.");
        }

        // 정식 필드가 올바른 배열이면 이를 고정하고, 없거나 배열이 아닌 legacy 입력만 fallback한다.
        JsonNode hostsNode = spec.get("allowedHosts");
        if (hostsNode == null || !hostsNode.isArray()) {
            hostsNode = spec.path("networkPolicy").get("allowedHosts");
        }
        JsonNode portsNode = spec.get("allowedPorts");
        if (portsNode == null || !portsNode.isArray()) {
            portsNode = spec.path("networkPolicy").get("allowedPorts");
        }
        return new TargetProfile(
                name,
                baseUri,
                stringList(spec.get("allowedPaths")),
                stringList(spec.get("allowedMethods")),
                stringList(hostsNode),
                intList(portsNode),
                stringMap(spec.get("defaultHeaders")),
                secretRefMap(spec.get("secretHeaders")),
                stringMap(spec.get("callbackPaths")),
                stringList(spec.path("redaction").get("headers")),
                stringList(spec.path("redaction").get("jsonPaths")));
    }

    /** JSON 배열을 정수 목록으로 복사해 호출자 tree와 실행 모델을 분리한다. */
    private List<Integer> intList(JsonNode node) {
        List<Integer> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(element -> result.add(element.asInt()));
        }
        return List.copyOf(result);
    }

    /** JSON 배열을 문자열 목록으로 복사해 호출자 tree와 실행 모델을 분리한다. */
    private List<String> stringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(element -> result.add(element.asText()));
        }
        return List.copyOf(result);
    }

    /** JSON 객체의 문자열 값을 삽입 순서를 보존한 뒤 불변 map으로 만든다. */
    private Map<String, String> stringMap(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.properties().forEach(entry -> result.put(entry.getKey(), entry.getValue().asText()));
        }
        return Map.copyOf(result);
    }

    /** secret 원문이 아니라 각 header가 가리키는 secretRef 이름만 실행 모델에 옮긴다. */
    private Map<String, String> secretRefMap(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.properties().forEach(entry ->
                    result.put(entry.getKey(), entry.getValue().path("secretRef").asText()));
        }
        return Map.copyOf(result);
    }
}
