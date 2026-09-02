package io.outcomeprobe.execution;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import io.outcomeprobe.profile.TargetProfileSafeSnapshot;

/**
 * Run 시작 시점에 고정되는 immutable 실행 계획 전체다.
 *
 * <p>책임: seed 기반 변수·step 입력을 Run 시작 전에 확정해, 원본 Scenario가
 * 나중에 수정돼도 이 계획으로 실행된 Run의 결과가 재현 가능하게 한다.</p>
 * <p>핵심 불변식: secret 값은 이 계획에 저장하지 않는다. secretRef 이름만
 * snapshot되며 실제 값은 실행 시 resolver가 조회한다.</p>
 *
 * @param runId Run 식별자
 * @param scenarioName snapshot scenario 이름
 * @param scenarioVersion snapshot scenario 버전
 * @param scenarioHash snapshot scenario content hash
 * @param contractName snapshot contract 이름
 * @param contractVersion snapshot contract 버전
 * @param emulatorAlias 이 Run이 독점할 Emulator alias
 * @param targetProfileName 선택된 Target Profile 이름
 * @param seed 확정된 결정론 seed
 * @param timeoutMs Run 전체 제한 시간(밀리초)
 * @param createdAt 계획 생성 시각
 * @param variables 해석 완료된 변수 집합
 * @param steps 순서가 고정된 컴파일 step 목록
 * @param planSchemaVersion compiled plan 구조 버전
 * @param scenarioSnapshot comment-free safe YAML과 safe spec을 포함한 Scenario snapshot
 * @param contractSnapshot comment-free safe YAML과 safe spec을 포함한 ProviderContract snapshot
 * @param targetProfileSnapshot secret 원문을 제외한 TargetProfile snapshot
 * @param snapshotSetHash 세 snapshot과 plan schema version을 함께 묶은 SHA-256
 */
public record CompiledRunPlan(
        String runId,
        String scenarioName,
        String scenarioVersion,
        String scenarioHash,
        String contractName,
        String contractVersion,
        String emulatorAlias,
        String targetProfileName,
        long seed,
        long timeoutMs,
        Instant createdAt,
        Map<String, Object> variables,
        List<CompiledStep> steps,
        int planSchemaVersion,
        ArtifactSnapshot scenarioSnapshot,
        ArtifactSnapshot contractSnapshot,
        TargetProfileSafeSnapshot targetProfileSnapshot,
        String snapshotSetHash) {

    /** 현재 작성하는 compiled plan JSON schema version이다. */
    public static final int CURRENT_SCHEMA_VERSION = 2;

    /**
     * Collection과 JSON tree를 방어적으로 고정해 메모리 내 plan 변조를 차단한다.
     */
    public CompiledRunPlan {
        variables = freezeMap(variables);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /**
     * v1 plan을 역직렬화하거나 오래된 테스트 fixture를 읽기 위한 호환 생성자다.
     * 신규 Run 생성 코드에서는 사용하지 않는다.
     */
    public CompiledRunPlan(
            String runId,
            String scenarioName,
            String scenarioVersion,
            String scenarioHash,
            String contractName,
            String contractVersion,
            String emulatorAlias,
            String targetProfileName,
            long seed,
            long timeoutMs,
            Instant createdAt,
            Map<String, Object> variables,
            List<CompiledStep> steps) {
        this(runId, scenarioName, scenarioVersion, scenarioHash, contractName, contractVersion,
                emulatorAlias, targetProfileName, seed, timeoutMs, createdAt, variables, steps,
                1, null, null, null, null);
    }

    /**
     * 세 Artifact의 v2 snapshot이 모두 존재하는지 반환한다.
     *
     * @return 완전한 v2 snapshot이면 true
     */
    public boolean hasCompleteSnapshots() {
        return scenarioSnapshot != null && contractSnapshot != null && targetProfileSnapshot != null;
    }

    /**
     * 변수 snapshot의 mutable tree를 공유하지 않는 복사본을 반환한다.
     *
     * @return immutable 변수 map
     */
    @Override
    public Map<String, Object> variables() {
        return freezeMap(variables);
    }

    /**
     * Map 값을 재귀적으로 immutable snapshot으로 만든다.
     *
     * @param source 원본 map
     * @return immutable deep copy
     */
    private static Map<String, Object> freezeMap(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, freezeValue(value)));
        return java.util.Collections.unmodifiableMap(result);
    }

    /**
     * 지원되는 변수 값을 재귀적으로 복사한다.
     *
     * @param value 원본 변수 값
     * @return immutable 값 또는 JSON tree 복사본
     */
    private static Object freezeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = new java.util.LinkedHashMap<>();
            map.forEach((key, nested) -> converted.put(String.valueOf(key), freezeValue(nested)));
            return java.util.Collections.unmodifiableMap(converted);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(CompiledRunPlan::freezeValue).toList();
        }
        if (value instanceof JsonNode node) {
            return node.deepCopy();
        }
        return value;
    }

    /**
     * 컴파일된 Step 하나를 표현한다.
     *
     * <p>detail JSON은 template 변수가 전부 해석된 상태이며, Step 실행기가
     * 유형별로 해석한다.</p>
     *
     * @param id Step 식별자
     * @param type Step 유형
     * @param ordinal 실행 순서
     * @param detail 해석 완료된 Step 상세 JSON
     */
    public record CompiledStep(String id, StepType type, int ordinal, JsonNode detail) {

        /**
         * Step 입력 tree를 생성 시 복사한다.
         */
        public CompiledStep {
            detail = detail == null ? null : detail.deepCopy();
        }

        /**
         * Step 입력 tree의 독립 복사본을 반환한다.
         *
         * @return Step 상세 복사본
         */
        @Override
        public JsonNode detail() {
            return detail == null ? null : detail.deepCopy();
        }
    }
}
