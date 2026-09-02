package io.outcomeprobe.scenario;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Artifact를 JSON Schema로 구조 검증한다.
 *
 * <p>책임: envelope·metadata·spec의 기본 구조를 JSON Schema로 검증해, 프로그램
 * 검증보다 앞서 구조 오류를 field path와 함께 보고한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: JSON Schema는 구조만 검증한다. 참조 존재·
 * 상태 전이 유효성 같은 의미 검증은 ScenarioMapper가 담당한다. line/column은
 * YAML 파싱 오류에서만 제공된다.</p>
 */
@Service
public class SchemaValidationService {

    private final JsonSchema scenarioSchema;
    private final JsonSchema contractSchema;
    private final JsonSchema profileSchema;

    /**
     * classpath의 JSON Schema를 로드한다.
     */
    public SchemaValidationService() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        this.scenarioSchema = factory.getSchema(openResource("schemas/scenario.json"));
        this.contractSchema = factory.getSchema(openResource("schemas/provider-contract.json"));
        this.profileSchema = factory.getSchema(openResource("schemas/target-profile.json"));
    }

    /**
     * Artifact 전체 문서를 종류별 Schema로 검증한다.
     *
     * @param kind Artifact 종류
     * @param document 검증할 전체 문서(envelope 포함) JSON
     * @throws ArtifactImportException 구조 검증 실패 시 field path와 함께 발생한다
     */
    public void validate(ArtifactKind kind, JsonNode document) {
        JsonSchema schema = switch (kind) {
            case SCENARIO -> scenarioSchema;
            case PROVIDER_CONTRACT -> contractSchema;
            case TARGET_PROFILE -> profileSchema;
            default -> null;
        };
        if (schema == null) {
            return;
        }
        Set<ValidationMessage> errors = schema.validate(toNetworkntNode(document));
        if (!errors.isEmpty()) {
            List<String> messages = new ArrayList<>();
            String firstField = null;
            for (ValidationMessage error : errors) {
                String field = error.getInstanceLocation() == null
                        ? null : error.getInstanceLocation().toString();
                if (firstField == null) {
                    firstField = field;
                }
                messages.add((field == null ? "" : field + ": ") + error.getMessage());
            }
            throw new ArtifactImportException(
                    io.outcomeprobe.foundation.ProblemCode.SCENARIO_SCHEMA_INVALID,
                    firstField,
                    "JSON Schema 검증 실패: " + String.join("; ", messages));
        }
    }

    /**
     * classpath의 Schema 리소스를 InputStream으로 연다.
     */
    private InputStream openResource(String path) {
        try {
            return new ClassPathResource(path).getInputStream();
        } catch (Exception e) {
            throw new IllegalStateException("JSON Schema 리소스를 읽을 수 없습니다: " + path, e);
        }
    }

    /**
     * tools.jackson 문서를 networknt가 사용하는 com.fasterxml 문서로 변환한다.
     */
    private com.fasterxml.jackson.databind.JsonNode toNetworkntNode(JsonNode document) {
        // networknt는 com.fasterxml Jackson을 사용하므로 문자열로 변환해 다시 파싱한다.
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(document.toString());
        } catch (Exception e) {
            throw new IllegalStateException("문서 변환에 실패했습니다.", e);
        }
    }
}
