package io.outcomeprobe.scenario;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.core.StreamReadFeature;
import io.outcomeprobe.foundation.HashValue;
import io.outcomeprobe.foundation.ProblemCode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * YAML 문자열을 검증된 {@link ArtifactEnvelope}로 해석한다.
 *
 * <p>책임: DSL import의 첫 관문으로서 YAML syntax, apiVersion, kind, metadata 형식,
 * secret literal 존재 여부를 검사한다. 이후 참조·업무 검증은 각 종류별 validator가 맡는다.</p>
 * <p>핵심 불변식: 이 파서는 임의 script·shell 필드를 해석하지 않는다. YAML의
 * 선언적 구조만 읽는다.</p>
 */
public class ArtifactParser {

    /**
     * 현재 지원하는 DSL API 버전이다.
     */
    public static final String SUPPORTED_API_VERSION = "outcomeprobe.io/v1alpha1";

    private static final long MAX_FILE_SIZE = 512L * 1024L;
    private final ObjectMapper yamlMapper;
    private final SchemaValidationService schemaValidationService;

    /**
     * YAML 해석에 사용할 mapper와 Schema 검증기를 준비한다.
     *
     * @param schemaValidationService JSON Schema 구조 검증기
     */
    public ArtifactParser(SchemaValidationService schemaValidationService) {
        this.yamlMapper = new ObjectMapper(YAMLFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build());
        this.schemaValidationService = schemaValidationService;
    }

    /**
     * 원본 YAML을 envelope로 해석하고 공통 검증을 수행한다.
     *
     * <p>왜 먼저 검증하는가: 잘못된 문서를 저장 전에 거부해야 Run snapshot과
     * content hash 계약이 손상되지 않는다.</p>
     *
     * @param rawYaml 원본 YAML 문자열
     * @return 검증된 envelope
     * @throws ArtifactImportException syntax·형식·secret literal 검증 실패 시 발생한다
     */
    public ArtifactEnvelope parse(String rawYaml) {
        if (rawYaml == null || rawYaml.isBlank()) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, null,
                    "Artifact 문서가 비어 있습니다.");
        }
        if (rawYaml.length() > MAX_FILE_SIZE) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, null,
                    "Artifact 문서가 최대 크기 512KB를 초과합니다.");
        }
        ArtifactSecretPolicy.validateRawYamlText(rawYaml);

        JsonNode root;
        try {
            root = yamlMapper.readTree(rawYaml);
        } catch (Exception e) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, null,
                    "YAML 해석에 실패했습니다: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, null,
                    "Artifact 문서는 최상위 객체여야 합니다.");
        }

        String apiVersion = text(root, "apiVersion", "apiVersion");
        if (!SUPPORTED_API_VERSION.equals(apiVersion)) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, "apiVersion",
                    "지원하지 않는 apiVersion입니다: " + apiVersion);
        }

        String kindText = text(root, "kind", "kind");
        ArtifactKind kind;
        try {
            kind = ArtifactKind.valueOf(toEnumName(kindText));
        } catch (IllegalArgumentException e) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, "kind",
                    "알 수 없는 kind입니다: " + kindText);
        }

        // 종류별 JSON Schema로 문서 구조를 검증한다. field path와 함께 보고된다.
        schemaValidationService.validate(kind, root);

        JsonNode metadataNode = root.get("metadata");
        if (metadataNode == null || !metadataNode.isObject()) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, "metadata",
                    "metadata 객체가 필요합니다.");
        }
        ArtifactMetadata metadata = new ArtifactMetadata(
                text(metadataNode, "name", "metadata.name"),
                optionalText(metadataNode, "displayName", nameDefault(kind, text(metadataNode, "name", "metadata.name"))),
                text(metadataNode, "version", "metadata.version"),
                stringMap(metadataNode.get("labels")));

        JsonNode spec = root.get("spec");
        if (spec == null || !spec.isObject()) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, "spec",
                    "spec 객체가 필요합니다.");
        }

        // spec뿐 아니라 metadata label도 snapshot에 포함될 수 있으므로 문서 전체를 검사한다.
        ArtifactSecretPolicy.validateSafeDocument(root);

        return new ArtifactEnvelope(apiVersion, kind, metadata, spec, rawYaml, HashValue.sha256(rawYaml));
    }

    /**
     * kind 문자열을 enum 이름으로 변환한다.
     */
    private String toEnumName(String kindText) {
        StringBuilder builder = new StringBuilder();
        for (String part : kindText.split("(?<=[a-z])(?=[A-Z])")) {
            if (!builder.isEmpty()) {
                builder.append('_');
            }
            builder.append(part.toUpperCase(Locale.ROOT));
        }
        return builder.toString();
    }

    /**
     * displayName 생략 시 기본값을 계산한다.
     */
    private String nameDefault(ArtifactKind kind, String name) {
        return name;
    }

    /**
     * 필수 텍스트 필드를 읽고 없으면 schema 오류를 던진다.
     */
    private String text(JsonNode node, String field, String path) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, path,
                    path + " 필드가 필요합니다.");
        }
        return value.asText();
    }

    /**
     * 선택 텍스트 필드를 읽고 없으면 기본값을 반환한다.
     */
    private String optionalText(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            return defaultValue;
        }
        return value.asText();
    }

    /**
     * label 노드를 문자열 map으로 변환·검증한다.
     */
    private Map<String, String> stringMap(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return result;
        }
        if (!node.isObject()) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, "metadata.labels",
                    "labels는 문자열 key/value 객체여야 합니다.");
        }
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (!entry.getValue().isTextual()) {
                throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID,
                        "metadata.labels." + entry.getKey(), "label 값은 문자열이어야 합니다.");
            }
            result.put(entry.getKey(), entry.getValue().asText());
        }
        return result;
    }

}
