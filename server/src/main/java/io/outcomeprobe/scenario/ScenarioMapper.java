package io.outcomeprobe.scenario;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.outcomeprobe.foundation.ProblemCode;
import io.outcomeprobe.scenario.contract.ProviderContract;
import io.outcomeprobe.scenario.definition.DslDurations;
import io.outcomeprobe.scenario.definition.ScenarioDefinition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Artifact envelope의 spec JSON을 타입 안전 도메인 모델로 변환하고 교차 검증한다.
 *
 * <p>책임: ProviderContract·ScenarioDefinition 해석과 Scenario-Contract 간
 * 참조·구조 검증을 한 곳에 모아 import 시점에 결함을 조기 검출한다.</p>
 * <p>핵심 불변식: Scenario는 Contract에 존재하지 않는 operation·callback을
 * 참조할 수 없고, absolute URL을 포함할 수 없으며, Step ID는 유일해야 한다.</p>
 */
public class ScenarioMapper {

    private static final int MAX_STEPS = 100;
    private static final int MIN_CALLBACK_ATTEMPTS = 1;
    private static final int MAX_CALLBACK_ATTEMPTS = 100;
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);
    private static final Pattern ABSOLUTE_URL_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://");
    private static final Pattern SAFE_CALLBACK_HEADER =
            Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$");
    private static final Pattern SAFE_SECRET_REFERENCE =
            Pattern.compile("^[a-z0-9](?:[-a-z0-9]{0,62}[a-z0-9])?$");
    private static final Set<String> RESERVED_CALLBACK_HEADERS = Set.of(
            "authorization", "connection", "content-length", "content-type", "cookie", "host",
            "idempotency-key", "transfer-encoding", "x-outcomeprobe-delivery-id",
            "x-outcomeprobe-delivery-occurrence", "x-outcomeprobe-event-id",
            "x-outcomeprobe-run-id");

    private final ObjectMapper objectMapper;

    /**
     * spec 변환에 사용할 mapper를 주입한다.
     *
     * @param objectMapper JSON 변환 mapper
     */
    public ScenarioMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * ProviderContract envelope spec을 도메인 모델로 변환하고 기본 검증을 수행한다.
     *
     * @param spec contract spec JSON
     * @return 검증된 Provider 계약
     * @throws ArtifactImportException 구조·상태 기계 검증 실패 시 발생한다
     */
    public ProviderContract toContract(JsonNode spec) {
        ProviderContract contract;
        try {
            contract = objectMapper.convertValue(spec, ProviderContract.class);
        } catch (IllegalArgumentException e) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, "spec",
                    "ProviderContract 구조 해석에 실패했습니다: " + e.getMessage());
        }
        if (contract.emulatorAlias() == null || contract.emulatorAlias().isBlank()) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, "spec.emulatorAlias",
                    "emulatorAlias가 필요합니다.");
        }
        if (contract.operations() == null || contract.operations().isEmpty()) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, "spec.operations",
                    "최소 하나의 operation이 필요합니다.");
        }
        validateCallbackSignatures(contract);
        return contract;
    }

    /**
     * Callback 서명 계약을 import 시점에 실행기가 지원하는 단일 의미로 제한한다.
     *
     * <p>지원하지 않는 encoding이나 signedContent를 저장한 뒤 Worker가 조용히 다른 방식으로
     * 서명하면 Target이 모든 Callback을 거부한다. 또한 transport·credential header와 서명
     * header가 충돌하면 원래 보안 경계가 덮어써질 수 있으므로 저장 전에 거부한다.</p>
     *
     * @param contract 구조 변환을 마친 Provider Contract
     */
    private void validateCallbackSignatures(ProviderContract contract) {
        if (contract.callbacks() == null) {
            return;
        }
        contract.callbacks().forEach((callbackName, callback) -> {
            ProviderContract.SignatureSpec signature = callback.signature();
            if (signature == null) {
                return;
            }
            String path = "spec.callbacks." + callbackName + ".signature";
            if (!"HMAC_SHA256".equals(signature.algorithm())) {
                throw invalidSignature(path + ".algorithm", "HMAC_SHA256만 지원합니다.");
            }
            if (signature.header() == null
                    || !SAFE_CALLBACK_HEADER.matcher(signature.header()).matches()
                    || RESERVED_CALLBACK_HEADERS.contains(
                            signature.header().toLowerCase(java.util.Locale.ROOT))) {
                throw invalidSignature(path + ".header",
                        "서명 전용의 안전한 HTTP header 이름이어야 합니다.");
            }
            if (signature.secretRef() == null
                    || !SAFE_SECRET_REFERENCE.matcher(signature.secretRef()).matches()) {
                throw invalidSignature(path + ".secretRef",
                        "소문자 kebab-case secretRef가 필요합니다.");
            }
            if (!"HEX_LOWER".equals(signature.encoding())) {
                throw invalidSignature(path + ".encoding", "HEX_LOWER만 지원합니다.");
            }
            if (!"RAW_BODY".equals(signature.signedContent())) {
                throw invalidSignature(path + ".signedContent", "RAW_BODY만 지원합니다.");
            }
        });
    }

    /** Callback 서명 검증 실패를 field가 보존된 공개 import 오류로 만든다. */
    private ArtifactImportException invalidSignature(String field, String message) {
        return new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, field, message);
    }

    /**
     * Scenario envelope spec을 도메인 모델로 변환하고 구조 검증을 수행한다.
     *
     * @param spec scenario spec JSON
     * @return 검증된 Scenario 정의
     * @throws ArtifactImportException 구조·제한·참조 검증 실패 시 발생한다
     */
    public ScenarioDefinition toScenario(JsonNode spec) {
        JsonNode contractRefNode = spec.get("contractRef");
        if (contractRefNode == null || !contractRefNode.isObject()) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_REFERENCE_NOT_FOUND, "spec.contractRef",
                    "contractRef가 필요합니다.");
        }
        ScenarioDefinition.ContractRef contractRef = new ScenarioDefinition.ContractRef(
                requiredText(contractRefNode, "name", "spec.contractRef.name"),
                requiredText(contractRefNode, "version", "spec.contractRef.version"));

        String targetProfileRef = spec.path("targetProfileRef").isTextual()
                ? spec.get("targetProfileRef").asText()
                : null;

        Long seed = spec.path("seed").isIntegralNumber() ? spec.get("seed").asLong() : null;

        Duration timeout = spec.path("timeout").isTextual()
                ? DslDurations.parse(spec.get("timeout").asText())
                : Duration.ofSeconds(30);
        if (timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_TIMEOUT_EXCEEDED, "spec.timeout",
                    "Run timeout 상한은 10분입니다.");
        }

        Map<String, Object> variables = new LinkedHashMap<>();
        JsonNode variablesNode = spec.get("variables");
        if (variablesNode != null && variablesNode.isObject()) {
            variablesNode.properties().forEach(entry -> variables.put(entry.getKey(),
                    toPlainValue(entry.getValue())));
        }

        JsonNode stepsNode = spec.get("steps");
        if (stepsNode == null || !stepsNode.isArray() || stepsNode.isEmpty()) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, "spec.steps",
                    "최소 하나의 step이 필요합니다.");
        }
        if (stepsNode.size() > MAX_STEPS) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, "spec.steps",
                    "step 수는 최대 100개입니다.");
        }

        List<ScenarioDefinition.StepSpec> steps = new ArrayList<>();
        Set<String> stepIds = new HashSet<>();
        for (int i = 0; i < stepsNode.size(); i++) {
            JsonNode stepNode = stepsNode.get(i);
            String id = requiredText(stepNode, "id", "spec.steps[" + i + "].id");
            String type = requiredText(stepNode, "type", "spec.steps[" + i + "].type");
            if (!stepIds.add(id)) {
                throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID,
                        "spec.steps[" + i + "].id", "중복된 step id입니다: " + id);
            }
            rejectAbsoluteUrls(stepNode, "spec.steps[" + i + "]");
            validateCallbackRetry(stepNode, type, "spec.steps[" + i + "]");
            steps.add(new ScenarioDefinition.StepSpec(id, type, stepNode));
        }

        return new ScenarioDefinition(contractRef, targetProfileRef, seed, timeout, variables, steps);
    }

    /**
     * Scenario가 Contract에 선언된 operation·callback만 참조하는지 검증한다.
     *
     * @param scenario 검증할 Scenario
     * @param contract 참조 대상 Contract
     * @throws ArtifactImportException 존재하지 않는 참조가 있으면 발생한다
     */
    public void validateReferences(ScenarioDefinition scenario, ProviderContract contract) {
        for (ScenarioDefinition.StepSpec step : scenario.steps()) {
            JsonNode detail = step.detail();
            if (detail.path("operation").isTextual()) {
                String operation = detail.get("operation").asText();
                if (!contract.operations().containsKey(operation)) {
                    throw new ArtifactImportException(ProblemCode.SCENARIO_UNKNOWN_OPERATION,
                            "spec.steps." + step.id() + ".operation",
                            "ProviderContract에 존재하지 않는 operation입니다: " + operation);
                }
            }
            if (detail.path("callback").isTextual()) {
                String callback = detail.get("callback").asText();
                if (contract.callbacks() == null || !contract.callbacks().containsKey(callback)) {
                    throw new ArtifactImportException(ProblemCode.SCENARIO_UNKNOWN_CALLBACK,
                            "spec.steps." + step.id() + ".callback",
                            "ProviderContract에 존재하지 않는 callback입니다: " + callback);
                }
            }
        }
    }

    /**
     * 트리에서 absolute URL 사용을 거부한다. Scenario는 상대 경로만 허용한다.
     */
    private void rejectAbsoluteUrls(JsonNode node, String path) {
        if (node.isTextual() && ABSOLUTE_URL_PATTERN.matcher(node.asText()).find()) {
            throw new ArtifactImportException(ProblemCode.TARGET_PATH_NOT_ALLOWED, path,
                    "Scenario에는 absolute URL을 쓸 수 없습니다. 상대 경로와 Target Profile을 사용하세요.");
        }
        if (node.isObject()) {
            node.properties().forEach(entry ->
                    rejectAbsoluteUrls(entry.getValue(), path + "." + entry.getKey()));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                rejectAbsoluteUrls(node.get(i), path + "[" + i + "]");
            }
        }
    }

    /**
     * callback.schedule의 상위·event별 retry 상한이 유효한 정수 범위인지 검증한다.
     *
     * <p>0 이하 값은 Delivery를 영구 SCHEDULED로 고착시키고 과도한 값은 외부 부작용과
     * 실행 시간을 무제한으로 키울 수 있으므로 import 시점에 거부한다.</p>
     *
     * @param stepNode 검증할 Step JSON
     * @param type Step type 문자열
     * @param path 오류에 사용할 Step 경로
     */
    private void validateCallbackRetry(JsonNode stepNode, String type, String path) {
        if (!"callback.schedule".equals(type)) {
            return;
        }
        validateMaxAttempts(stepNode.path("delivery").path("retry").get("maxAttempts"),
                path + ".delivery.retry.maxAttempts");
        JsonNode events = stepNode.get("events");
        if (events == null || !events.isArray()) {
            return;
        }
        for (int i = 0; i < events.size(); i++) {
            validateMaxAttempts(events.get(i).path("delivery").path("retry")
                            .get("maxAttempts"),
                    path + ".events[" + i + "].delivery.retry.maxAttempts");
        }
    }

    /**
     * 명시된 Callback attempt 상한을 1~100의 정수로 제한한다.
     *
     * @param node maxAttempts 값, 생략했으면 null
     * @param path 오류에 사용할 필드 경로
     */
    private void validateMaxAttempts(JsonNode node, String path) {
        if (node == null || node.isMissingNode()) {
            return;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()
                || node.intValue() < MIN_CALLBACK_ATTEMPTS
                || node.intValue() > MAX_CALLBACK_ATTEMPTS) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, path,
                    "callback retry.maxAttempts는 1 이상 100 이하의 정수여야 합니다.");
        }
    }

    /**
     * 필수 텍스트 필드를 읽고 없으면 schema 오류를 던진다.
     */
    private String requiredText(JsonNode node, String field, String path) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, path,
                    path + " 필드가 필요합니다.");
        }
        return value.asText();
    }

    /**
     * JSON 노드를 변수 값으로 사용할 일반 값으로 변환한다.
     */
    private Object toPlainValue(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.toString();
    }
}
