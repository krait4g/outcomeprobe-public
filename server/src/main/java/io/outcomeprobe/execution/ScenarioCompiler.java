package io.outcomeprobe.execution;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import io.outcomeprobe.foundation.ProblemCode;
import io.outcomeprobe.scenario.ArtifactImportException;
import io.outcomeprobe.scenario.ArtifactSecretPolicy;
import io.outcomeprobe.scenario.contract.ProviderContract;
import io.outcomeprobe.scenario.definition.ScenarioDefinition;
import io.outcomeprobe.profile.TargetProfileSafeSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scenario 정의와 계약을 실행 가능한 immutable 계획으로 컴파일한다.
 *
 * <p>책임: 변수 해석, template 치환, step 유형 검증, alias·timeout 고정을
 * Run 시작 전에 완료해 실행 중 결정론이 흔들리지 않게 한다.</p>
 * <p>핵심 불변식: 같은 Artifact snapshot과 seed, 변수는 같은 계획을 만든다.
 * 전역 난수나 현재 시각 분기는 계획 내용에 개입하지 않는다.</p>
 */
public class ScenarioCompiler {

    private static final Pattern VARIABLE_TOKEN = Pattern.compile("\\$\\{([^}]+)}");
    private static final int MAX_VARIABLES = 100;
    private static final int MAX_VARIABLE_KEY_BYTES = 128;
    private static final int MAX_VARIABLE_VALUE_BYTES = 256 * 1024;
    private static final ObjectMapper VARIABLE_MAPPER = new ObjectMapper();
    private static final Set<StepType> SUPPORTED_IN_M1 = Set.of(
            StepType.TARGET_HTTP, StepType.PROVIDER_SEED, StepType.PROVIDER_EXPECT,
            StepType.CALLBACK_SCHEDULE, StepType.EVIDENCE_AWAIT, StepType.PROBE_EVENTUALLY,
            StepType.EVIDENCE_ASSERT, StepType.PROVIDER_ASSERT, StepType.RUN_WAIT);

    /**
     * 기본 생성자다. 컴파일은 주입 상태 없이 수행된다.
     */
    public ScenarioCompiler() {
    }

    /**
     * Scenario와 계약을 컴파일해 Run 계획을 생성한다.
     *
     * @param runId Run 식별자
     * @param scenarioSnapshot snapshot Scenario spec·identity·hash
     * @param scenario 검증된 Scenario 정의
     * @param contractSnapshot snapshot ProviderContract spec·identity·hash
     * @param contract 참조 Provider 계약
     * @param targetProfileSnapshot 실행에 사용할 Target Profile safe snapshot
     * @param seedOverride Run 생성 시 지정한 seed, 없으면 Scenario 기본 seed
     * @param variableOverrides Run 생성 시 지정한 변수 override
     * @param createdAt 계획 생성 시각
     * @return immutable 실행 계획
     * @throws ArtifactImportException 미지원 step·미해결 변수가 있으면 발생한다
     */
    public CompiledRunPlan compile(String runId, ArtifactSnapshot scenarioSnapshot,
                                   ScenarioDefinition scenario,
                                   ArtifactSnapshot contractSnapshot,
                                   ProviderContract contract,
                                   TargetProfileSafeSnapshot targetProfileSnapshot,
                                   Long seedOverride, Map<String, Object> variableOverrides,
                                   Instant createdAt) {
        long seed = seedOverride != null ? seedOverride
                : scenario.seed() != null ? scenario.seed() : 0L;

        Map<String, Object> variables = new LinkedHashMap<>(scenario.variables());
        if (variableOverrides != null) {
            variables.putAll(variableOverrides);
        }
        validateVariables(variables);

        Map<String, Object> resolution = new LinkedHashMap<>();
        variables.forEach((key, value) -> resolution.put("variables." + key, value));
        resolution.put("run.id", runId);
        resolution.put("run.seed", seed);

        List<CompiledRunPlan.CompiledStep> steps = new java.util.ArrayList<>();
        List<ScenarioDefinition.StepSpec> specs = scenario.steps();
        for (int i = 0; i < specs.size(); i++) {
            ScenarioDefinition.StepSpec spec = specs.get(i);
            StepType type;
            try {
                type = StepType.fromDsl(spec.type());
            } catch (IllegalArgumentException e) {
                throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID,
                        "spec.steps[" + i + "].type", e.getMessage());
            }
            if (!SUPPORTED_IN_M1.contains(type)) {
                throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID,
                        "spec.steps[" + i + "].type",
                        "아직 지원하지 않는 step type입니다: " + spec.type());
            }
            JsonNode resolved = resolveTemplates(spec.detail(), resolution);
            ArtifactSecretPolicy.validateResolvedInput(resolved, "spec.steps[" + i + "]");
            steps.add(new CompiledRunPlan.CompiledStep(spec.id(), type, i, resolved));
        }

        return new CompiledRunPlan(
                runId,
                scenarioSnapshot.name(),
                scenarioSnapshot.version(),
                scenarioSnapshot.sourceContentHash(),
                contractSnapshot.name(),
                contractSnapshot.version(),
                contract.emulatorAlias(),
                targetProfileSnapshot.name(),
                seed,
                scenario.timeout().toMillis(),
                createdAt,
                Map.copyOf(variables),
                List.copyOf(steps),
                CompiledRunPlan.CURRENT_SCHEMA_VERSION,
                scenarioSnapshot,
                contractSnapshot,
                targetProfileSnapshot,
                RunSnapshotIntegrity.snapshotSetHash(
                        CompiledRunPlan.CURRENT_SCHEMA_VERSION,
                        scenarioSnapshot,
                        contractSnapshot,
                        targetProfileSnapshot));
    }

    /**
     * 저장될 변수 snapshot의 개수·key·개별 값 크기와 credential 정책을 검증한다.
     *
     * <p>Scenario 기본값과 Run override를 합친 뒤 검사해야 override가 새 key를 추가해
     * 100개 상한을 우회하거나, 사용되지 않는 민감 값이 plan에 그대로 남는 것을 막을 수 있다.</p>
     *
     * @param variables compiled plan에 저장할 최종 변수 집합
     * @throws ArtifactImportException 변수 제한 또는 secret 비저장 정책을 위반하면 발생한다
     */
    private void validateVariables(Map<String, Object> variables) {
        if (variables.size() > MAX_VARIABLES) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID,
                    "variables", "변수는 최대 100개까지 사용할 수 있습니다.");
        }
        ObjectNode variableTree = JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String key = entry.getKey();
            validateVariableKey(key);
            JsonNode value = variableNode(entry.getValue(), key);
            if (value.toString().getBytes(StandardCharsets.UTF_8).length
                    > MAX_VARIABLE_VALUE_BYTES) {
                throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID,
                        "variables." + key, "개별 변수 값은 UTF-8 JSON 기준 256KiB 이하여야 합니다.");
            }
            variableTree.set(key, value);
        }
        ArtifactSecretPolicy.validateResolvedInput(variableTree, "variables");
    }

    /**
     * 변수 key가 비어 있지 않고 UTF-8 128바이트 상한과 제어문자 금지를 지키는지 검사한다.
     *
     * @param key 검사할 변수 key
     * @throws ArtifactImportException key가 template 식별자로 안전하게 저장될 수 없으면 발생한다
     */
    private void validateVariableKey(String key) {
        if (key == null || key.isBlank()
                || key.getBytes(StandardCharsets.UTF_8).length > MAX_VARIABLE_KEY_BYTES
                || key.codePoints().anyMatch(Character::isISOControl)) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID,
                    "variables", "변수 key는 비어 있지 않은 UTF-8 128바이트 이하 문자열이어야 합니다.");
        }
    }

    /**
     * 외부 요청에서 받은 변수 값을 정책 검사용 JSON tree로 안전하게 변환한다.
     *
     * @param value 변환할 변수 값
     * @param key 오류 경로에 사용할 변수 key
     * @return 독립된 JSON tree
     * @throws ArtifactImportException null 또는 JSON으로 표현할 수 없는 값이면 발생한다
     */
    private JsonNode variableNode(Object value, String key) {
        if (value == null) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID,
                    "variables." + key, "변수 값에는 null을 사용할 수 없습니다.");
        }
        try {
            JsonNode node = VARIABLE_MAPPER.valueToTree(value);
            if (node == null || node.isNull()) {
                throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID,
                        "variables." + key, "변수 값에는 null을 사용할 수 없습니다.");
            }
            return node;
        } catch (ArtifactImportException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID,
                    "variables." + key, "변수 값을 안전한 JSON으로 변환할 수 없습니다.");
        }
    }

    /**
     * JSON 트리를 재귀 순회하며 허용된 변수 token을 치환한다.
     */
    private JsonNode resolveTemplates(JsonNode node, Map<String, Object> resolution) {
        if (node == null || node.isNull()) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (node.isTextual()) {
            return resolveText(node.asText(), resolution);
        }
        if (node.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                result.set(entry.getKey(), resolveTemplates(entry.getValue(), resolution));
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            node.forEach(element -> result.add(resolveTemplates(element, resolution)));
            return result;
        }
        return node.deepCopy();
    }

    /**
     * 문자열의 token을 치환한다. 단일 token은 원본 값 타입을 보존한다.
     */
    private JsonNode resolveText(String text, Map<String, Object> resolution) {
        Matcher matcher = VARIABLE_TOKEN.matcher(text);
        // 단일 token 전체 치환은 값 타입을 보존한다.
        if (matcher.matches()) {
            Object value = resolution.get(matcher.group(1));
            if (value == null) {
                throw new ArtifactImportException(ProblemCode.SCENARIO_VARIABLE_UNRESOLVED,
                        matcher.group(1), "해결할 수 없는 변수 참조입니다: " + matcher.group());
            }
            if (value instanceof String string) {
                return JsonNodeFactory.instance.textNode(string);
            }
            if (value instanceof Long longValue) {
                return JsonNodeFactory.instance.numberNode(longValue);
            }
            if (value instanceof Integer intValue) {
                return JsonNodeFactory.instance.numberNode(intValue);
            }
            if (value instanceof Double doubleValue) {
                return JsonNodeFactory.instance.numberNode(doubleValue);
            }
            if (value instanceof Boolean boolValue) {
                return JsonNodeFactory.instance.booleanNode(boolValue);
            }
            return JsonNodeFactory.instance.textNode(String.valueOf(value));
        }
        matcher.reset();
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            Object value = resolution.get(matcher.group(1));
            if (value == null) {
                throw new ArtifactImportException(ProblemCode.SCENARIO_VARIABLE_UNRESOLVED,
                        matcher.group(1), "해결할 수 없는 변수 참조입니다: " + matcher.group());
            }
            matcher.appendReplacement(builder, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(builder);
        return JsonNodeFactory.instance.textNode(builder.toString());
    }
}
