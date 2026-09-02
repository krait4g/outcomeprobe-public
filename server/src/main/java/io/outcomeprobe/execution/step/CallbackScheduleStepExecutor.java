package io.outcomeprobe.execution.step;

import io.outcomeprobe.delivery.CallbackDelivery;
import io.outcomeprobe.delivery.CallbackDeliveryRepository;
import io.outcomeprobe.delivery.CallbackEvent;
import io.outcomeprobe.delivery.DeliveryRequestSnapshotHash;
import io.outcomeprobe.delivery.DeliveryStatus;
import io.outcomeprobe.emulator.TemplateRenderer;
import io.outcomeprobe.evidence.AppendEvidenceCommand;
import io.outcomeprobe.evidence.EvidenceRecorder;
import io.outcomeprobe.evidence.EvidenceSource;
import io.outcomeprobe.evidence.EvidenceType;
import io.outcomeprobe.execution.CompiledRunPlan;
import io.outcomeprobe.execution.StepExecutionContext;
import io.outcomeprobe.execution.StepExecutionResult;
import io.outcomeprobe.execution.StepExecutor;
import io.outcomeprobe.execution.StepType;
import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.foundation.CanonicalJson;
import io.outcomeprobe.foundation.ProblemCode;
import io.outcomeprobe.foundation.OptimisticStateTransitionException;
import io.outcomeprobe.foundation.PostgresqlTimestamp;
import io.outcomeprobe.foundation.SeedGenerator;
import io.outcomeprobe.profile.TargetProfile;
import io.outcomeprobe.profile.TargetProfileSafeSnapshot;
import io.outcomeprobe.scenario.ScenarioMapper;
import io.outcomeprobe.scenario.contract.ProviderContract;
import io.outcomeprobe.scenario.definition.DslDurations;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * callback.schedule Step을 실행해 Callback 사건과 전달 occurrence를 계획한다.
 *
 * <p>책임: Scenario의 지연·중복·역순·OMIT 계획을 실제 callback_event·
 * callback_delivery 행으로 구체화한다. 실제 전송은 DeliveryWorker가 담당한다.</p>
 * <p>핵심 불변식: 전달 계획은 seed 기반으로 확정돼 같은 Scenario·seed에서 같은
 * 순서·시각 offset을 만든다. duplicate는 같은 eventId·payload를 가진 여러
 * occurrence다.</p>
 */
@Component
public class CallbackScheduleStepExecutor implements StepExecutor {

    private final CallbackDeliveryRepository deliveryRepository;
    private final ScenarioMapper scenarioMapper;
    private final TemplateRenderer templateRenderer;
    private final EvidenceRecorder evidenceRecorder;
    private final ClockProvider clockProvider;
    private final SeedGenerator seedGenerator;
    private final ObjectMapper objectMapper;

    /**
     * 전달 계획 생성에 필요한 협업 객체를 주입한다.
     *
     * @param deliveryRepository 전달 저장소
     * @param scenarioMapper Contract 변환기
     * @param templateRenderer payload template 렌더러
     * @param evidenceRecorder 증거 기록기
     * @param clockProvider 계획 시각 제공자
     * @param seedGenerator 결정론 jitter 생성기
     * @param objectMapper JSON 변환 mapper
     */
    public CallbackScheduleStepExecutor(CallbackDeliveryRepository deliveryRepository,
                                        ScenarioMapper scenarioMapper,
                                        TemplateRenderer templateRenderer,
                                        EvidenceRecorder evidenceRecorder,
                                        ClockProvider clockProvider,
                                        SeedGenerator seedGenerator,
                                        ObjectMapper objectMapper) {
        this.deliveryRepository = deliveryRepository;
        this.scenarioMapper = scenarioMapper;
        this.templateRenderer = templateRenderer;
        this.evidenceRecorder = evidenceRecorder;
        this.clockProvider = clockProvider;
        this.seedGenerator = seedGenerator;
        this.objectMapper = objectMapper;
    }

    /**
     * 이 실행기가 담당하는 Step 유형을 반환한다.
     *
     * @return CALLBACK_SCHEDULE
     */
    @Override
    public StepType supports() {
        return StepType.CALLBACK_SCHEDULE;
    }

    /**
     * 사건·전달 occurrence를 계획하고 Evidence를 남긴다.
     *
     * <p>왜 즉시 완료인가: 전달 행 생성은 DB insert만으로 끝나고 실제 전송은
     * Worker가 due 시각에 처리한다. Step은 계획 생성만 책임진다.</p>
     *
     * @param context 실행 문맥
     * @return 완료 또는 오류 결과
     */
    @Override
    public StepExecutionResult execute(StepExecutionContext context) {
        // Callback 계획과 Step·Run CAS가 같은 트랜잭션에서 확정되도록 DB-only 계산 전체를 지연한다.
        return new StepExecutionResult.Transactional(() -> materialize(context));
    }

    /**
     * Callback 사건과 occurrence를 계산해 현재 트랜잭션에 저장한다.
     *
     * <p>중간 저장 실패를 잡지 않아 coordinator가 앞선 insert와 Evidence를 모두
     * rollback하도록 보존한다.</p>
     *
     * @param context 고정 Snapshot과 현재 Step을 포함한 실행 문맥
     * @return 계획 생성이 끝난 Step 결과
     */
    private StepExecutionResult materialize(StepExecutionContext context) {
        String runId = context.run().id();
        if (!deliveryRepository.lockRunForDeliveryMutation(runId)) {
            throw new OptimisticStateTransitionException(
                    "Callback 계획의 부모 Run 잠금에 실패했습니다: " + runId);
        }

        JsonNode detail = context.step().inputSnapshot();
        String callbackKey = detail.path("callback").asText(null);
        if (callbackKey == null) {
            return new StepExecutionResult.Errored(ProblemCode.SCENARIO_SCHEMA_INVALID,
                    "callback.schedule step에 callback이 없습니다.");
        }
        String resourceType = detail.path("resource").path("type").asText(null);
        String resourceId = detail.path("resource").path("id").asText(null);

        ProviderContract contract = loadContract(context);
        if (contract == null) {
            return new StepExecutionResult.Errored(ProblemCode.ARTIFACT_SNAPSHOT_CORRUPTED,
                    "Run에 ProviderContract snapshot이 없습니다.");
        }
        ProviderContract.CallbackSpec callbackSpec = contract.callbacks() == null
                ? null : contract.callbacks().get(callbackKey);
        if (callbackSpec == null) {
            return new StepExecutionResult.Errored(ProblemCode.SCENARIO_UNKNOWN_CALLBACK,
                    "Contract에 정의되지 않은 callback입니다: " + callbackKey);
        }

        TargetProfileSafeSnapshot profileSnapshot = context.plan().targetProfileSnapshot();
        if (profileSnapshot == null || profileSnapshot.profile() == null) {
            return new StepExecutionResult.Errored(ProblemCode.ARTIFACT_SNAPSHOT_CORRUPTED,
                    "Run에 Target Profile snapshot이 없습니다.");
        }
        TargetProfile profile = profileSnapshot.profile();
        String path = profile.callbackPaths().getOrDefault(callbackKey, "/webhooks/" + callbackKey);

        Instant now = PostgresqlTimestamp.normalize(clockProvider.now());
        long seed = context.plan().seed();
        JsonNode topDelivery = detail.path("delivery");
        JsonNode eventsNode = detail.get("events");

        List<JsonNode> events = new ArrayList<>();
        if (eventsNode != null && eventsNode.isArray() && !eventsNode.isEmpty()) {
            eventsNode.forEach(events::add);
        } else {
            // events 배열이 없으면 상위 delivery만으로 단일 사건을 구성한다.
            events.add(objectMapper.createObjectNode());
        }

        // reorderGroup 사건은 seed 기반 jitter로 순서를 확정한다. 같은 seed는 같은 순서를 만든다.
        List<long[]> offsets = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            JsonNode event = events.get(i);
            JsonNode deliveryNode = event.path("delivery");
            String group = deliveryNode.path("reorderGroup").asText(
                    topDelivery.path("reorderGroup").asText(""));
            long offsetMs;
            if (!group.isEmpty()) {
                long maxJitter = deliveryNode.path("jitter").path("max").isTextual()
                        ? DslDurations.parse(deliveryNode.path("jitter").path("max").asText()).toMillis()
                        : topDelivery.path("jitter").path("max").isTextual()
                                ? DslDurations.parse(topDelivery.path("jitter").path("max").asText()).toMillis()
                                : 500L;
                offsetMs = seedGenerator.generateLong(seed, "delivery/reorder/" + group,
                        "event-" + i + "-" + event.path("eventId").asText(String.valueOf(i)));
                offsetMs = Math.floorMod(offsetMs, Math.max(1, maxJitter));
            } else {
                offsetMs = delayMillis(deliveryNode, topDelivery);
            }
            offsets.add(new long[]{offsetMs});
        }

        int totalOccurrences = 0;
        for (int i = 0; i < events.size(); i++) {
            JsonNode event = events.get(i);
            JsonNode deliveryNode = event.path("delivery");
            String mode = deliveryNode.path("mode").asText(topDelivery.path("mode").asText("SEND"));
            long eventOffsetMs = offsets.get(i)[0];
            long plannedOffsetMs = plannedLogicalTimeMs(
                    context.plan(), context.step().ordinal(), eventOffsetMs);

            // distinctEvents는 동일 상태의 별개 Event를 여러 개 만든다.
            // duplicates가 같은 eventId의 반복 전달인 것과 달리, 각각 고유 eventId를 가진다.
            int distinctCount = Math.max(1, deliveryNode.path("distinctEvents").asInt(
                    topDelivery.path("distinctEvents").asInt(1)));

            for (int distinct = 0; distinct < distinctCount; distinct++) {
                String baseEventId = event.path("eventId").isTextual()
                        ? event.path("eventId").asText()
                        : deterministicEventId(seed, callbackKey, resourceId, i);
                String eventId = distinctCount > 1
                        ? baseEventId + "-" + (distinct + 1)
                        : baseEventId;

                JsonNode payload = renderPayload(callbackSpec, event, resourceType, resourceId, eventId);
                String payloadHash = CanonicalJson.sha256(objectMapper, payload);

                CallbackEvent savedEvent = deliveryRepository.saveEvent(new CallbackEvent(
                        null, context.run().id(), callbackKey, eventId,
                        callbackSpec.eventType() == null ? callbackKey.toUpperCase(java.util.Locale.ROOT)
                                : callbackSpec.eventType(),
                        resourceType, resourceId,
                        event.path("version").isIntegralNumber() ? event.path("version").asLong() : null,
                        payload, payloadHash));

                evidenceRecorder.append(AppendEvidenceCommand
                        .of(context.run().id(), EvidenceType.CALLBACK_SCHEDULED, EvidenceSource.ENGINE)
                        .withCorrelation(context.step().stepId(), resourceType, resourceId)
                        .withAttribute("callback", callbackKey)
                        .withAttribute("eventId", eventId)
                        .withLogicalTimeMs(plannedOffsetMs)
                        .withObservedAt(now));

                if ("OMIT".equalsIgnoreCase(mode)) {
                    // OMIT은 전송하지 않지만 행과 Evidence를 남겨 의도적 생략을 보고한다.
                    deliveryRepository.saveDelivery(new CallbackDelivery(
                            null, context.run().id(), savedEvent.id(), 1, DeliveryStatus.OMITTED,
                            now, now, 0, 1, null, null, null, requestSnapshot(
                                    context, savedEvent.id(), callbackKey, eventId,
                                    savedEvent.payloadHash(),
                                    1, 1, now, path,
                                    callbackSpec, deliveryNode, topDelivery),
                            0, now));
                    evidenceRecorder.append(AppendEvidenceCommand
                            .of(context.run().id(), EvidenceType.CALLBACK_OMITTED_BY_SCENARIO, EvidenceSource.ENGINE)
                            .withCorrelation(context.step().stepId(), resourceType, resourceId)
                            .withAttribute("callback", callbackKey)
                            .withAttribute("eventId", eventId)
                            .withAttribute("faultOrigin", "SCENARIO_PLANNED")
                            .withLogicalTimeMs(plannedOffsetMs)
                            .withObservedAt(now));
                    totalOccurrences++;
                    continue;
                }

                int duplicates = Math.max(1, deliveryNode.path("duplicates").asInt(
                        topDelivery.path("duplicates").asInt(1)));
                long duplicateInterval = deliveryNode.path("duplicateInterval").isTextual()
                        ? DslDurations.parse(deliveryNode.path("duplicateInterval").asText()).toMillis()
                        : topDelivery.path("duplicateInterval").isTextual()
                                ? DslDurations.parse(topDelivery.path("duplicateInterval").asText()).toMillis()
                                : 0L;
                long baseOffset = eventOffsetMs;
                int maxAttempts = deliveryNode.path("retry").path("maxAttempts").asInt(
                        topDelivery.path("retry").path("maxAttempts").asInt(1));

                for (int occurrence = 1; occurrence <= duplicates; occurrence++) {
                    Instant scheduledAt = now.plusMillis(baseOffset + (occurrence - 1) * duplicateInterval);
                    deliveryRepository.saveDelivery(new CallbackDelivery(
                            null, context.run().id(), savedEvent.id(), occurrence,
                            DeliveryStatus.SCHEDULED, scheduledAt, scheduledAt, 0, maxAttempts,
                            null, null, null, requestSnapshot(context, savedEvent.id(), callbackKey,
                                    eventId, savedEvent.payloadHash(), occurrence, maxAttempts, scheduledAt, path,
                                    callbackSpec, deliveryNode, topDelivery), 0, null));
                    totalOccurrences++;
                }
            }
        }

        ObjectNode output = objectMapper.createObjectNode();
        output.put("occurrences", totalOccurrences);
        return new StepExecutionResult.Completed(output);
    }

    /**
     * Step이 참조하는 Provider 계약을 Run snapshot에서 복원한다.
     *
     * @param context 실행 문맥
     * @return snapshot 계약, 없으면 null
     */
    private ProviderContract loadContract(StepExecutionContext context) {
        if (context.plan().contractSnapshot() == null) {
            return null;
        }
        return scenarioMapper.toContract(context.plan().contractSnapshot().safeSpec());
    }

    /**
     * Contract의 callback body template을 event·resource 문맥으로 렌더링한다.
     */
    private JsonNode renderPayload(ProviderContract.CallbackSpec callbackSpec, JsonNode event,
                                   String resourceType, String resourceId, String eventId) {
        Map<String, Object> renderContext = new LinkedHashMap<>();
        renderContext.put("resource.id", resourceId);
        renderContext.put("resource.type", resourceType);
        renderContext.put("resource.state", event.path("state").asText(""));
        renderContext.put("resource.version", event.path("version").isIntegralNumber()
                ? event.path("version").asLong() : 0L);
        JsonNode attributes = event.get("attributes");
        if (attributes != null && attributes.isObject()) {
            attributes.properties().forEach(entry ->
                    renderContext.put("resource.attributes." + entry.getKey(),
                            toJavaValue(entry.getValue())));
        }
        renderContext.put("generated.eventId", eventId);
        renderContext.put("generated.transactionId", eventId);
        renderContext.put("clock.isoInstant", clockProvider.now().toString());
        JsonNode template = callbackSpec.body() == null
                ? objectMapper.createObjectNode() : callbackSpec.body();
        return templateRenderer.render(template, renderContext);
    }

    /**
     * event·상위 delivery 노드에서 지연 시간을 밀리초로 해석한다.
     */
    private long delayMillis(JsonNode deliveryNode, JsonNode topDelivery) {
        if (deliveryNode.path("delay").isTextual()) {
            return DslDurations.parse(deliveryNode.path("delay").asText()).toMillis();
        }
        if (topDelivery.path("delay").isTextual()) {
            return DslDurations.parse(topDelivery.path("delay").asText()).toMillis();
        }
        return 0L;
    }

    /**
     * seed 기반 결정론 eventId를 생성한다. 같은 seed는 같은 ID를 만든다.
     */
    private String deterministicEventId(long seed, String callbackKey, String resourceId, int index) {
        long value = seedGenerator.generateLong(seed, "callback/event",
                callbackKey + "/" + resourceId + "/" + index);
        return "evt-" + Long.toUnsignedString(value, 36);
    }

    /**
     * 전달 Worker가 사용할 요청 문맥(경로·서명·재시도 정책)을 JSON으로 조립한다.
     */
    private JsonNode requestSnapshot(StepExecutionContext context, String callbackEventId,
                                     String callbackKey, String eventId, String payloadHash,
                                     int occurrence, int maximumAttempts, Instant scheduledAt, String path,
                                     ProviderContract.CallbackSpec callbackSpec,
                                     JsonNode deliveryNode, JsonNode topDelivery) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("runId", context.run().id());
        snapshot.put("targetProfileName", context.run().targetProfileName());
        snapshot.put("planSchemaVersion", context.plan().planSchemaVersion());
        snapshot.put("runSnapshotSetHash", context.plan().snapshotSetHash());
        snapshot.put("scenarioSnapshotHash",
                context.plan().scenarioSnapshot().canonicalSnapshotHash());
        snapshot.put("contractSnapshotHash",
                context.plan().contractSnapshot().canonicalSnapshotHash());
        snapshot.put("targetProfileSnapshotHash",
                context.plan().targetProfileSnapshot().canonicalSnapshotHash());
        snapshot.set("targetProfileSnapshot",
                objectMapper.valueToTree(context.plan().targetProfileSnapshot()));
        snapshot.put("callbackKey", callbackKey);
        snapshot.put("eventId", eventId);
        snapshot.put("callbackEventId", callbackEventId);
        snapshot.put("payloadHash", payloadHash);
        snapshot.put("plannedOccurrence", occurrence);
        snapshot.put("maximumAttempts", maximumAttempts);
        snapshot.put("scheduledAt", scheduledAt.toString());
        snapshot.put("path", path);

        ProviderContract.SignatureSpec signature = callbackSpec.signature();
        if (signature != null) {
            snapshot.put("signatureAlgorithm", signature.algorithm() == null ? "NONE" : signature.algorithm());
            snapshot.put("signatureHeader", signature.header() == null
                    ? "X-OutcomeProbe-Signature" : signature.header());
            if (signature.secretRef() != null) {
                snapshot.put("secretRef", signature.secretRef());
            }
        } else {
            snapshot.put("signatureAlgorithm", "NONE");
        }
        String signatureFault = deliveryNode.path("signatureFault").asText(
                topDelivery.path("signatureFault").asText(""));
        if (!signatureFault.isEmpty()) {
            snapshot.put("signatureFault", signatureFault);
        }

        JsonNode successStatuses = firstPresent(deliveryNode.path("successStatuses"),
                topDelivery.path("successStatuses"));
        if (successStatuses != null) {
            snapshot.set("successStatuses", successStatuses);
        }
        JsonNode retryOnStatus = firstPresent(deliveryNode.path("retry").path("retryOnStatus"),
                topDelivery.path("retry").path("retryOnStatus"));
        if (retryOnStatus != null) {
            snapshot.set("retryOnStatus", retryOnStatus);
        } else {
            snapshot.set("retryOnStatus", objectMapper.readTree("[408,425,429,500,502,503,504]"));
        }
        snapshot.put("initialBackoffMs", retryConfig(deliveryNode, topDelivery, "initialBackoff", 200L));
        snapshot.put("multiplier", retryMultiplier(deliveryNode, topDelivery));
        snapshot.put("requestSnapshotHash",
                DeliveryRequestSnapshotHash.calculate(objectMapper, snapshot));
        return snapshot;
    }

    /**
     * 선행 run.wait의 결정론 지연과 event offset을 합쳐 Run 시작 상대 logical time을 만든다.
     *
     * @param plan 고정 compiled plan
     * @param currentOrdinal 현재 callback.schedule 순서
     * @param eventOffsetMs 현재 callback Step 안의 delay 또는 seed jitter
     * @return Run 시작 기준 계획 상대 밀리초
     */
    static long plannedLogicalTimeMs(CompiledRunPlan plan, int currentOrdinal,
                                     long eventOffsetMs) {
        long logicalTimeMs = eventOffsetMs;
        for (CompiledRunPlan.CompiledStep step : plan.steps()) {
            if (step.ordinal() >= currentOrdinal || step.type() != StepType.RUN_WAIT) {
                continue;
            }
            JsonNode detail = step.detail();
            String duration = detail.path("wait").isTextual()
                    ? detail.path("wait").asText()
                    : detail.path("duration").asText(null);
            if (duration == null) {
                throw new IllegalArgumentException("선행 run.wait에 duration이 없습니다.");
            }
            logicalTimeMs = Math.addExact(logicalTimeMs,
                    DslDurations.parse(duration).toMillis());
        }
        return logicalTimeMs;
    }

    /**
     * 재시도 backoff·한계 설정을 밀리초로 해석한다.
     */
    private long retryConfig(JsonNode deliveryNode, JsonNode topDelivery, String field, long defaultValue) {
        JsonNode node = deliveryNode.path("retry").path(field);
        if (node.isTextual()) {
            return DslDurations.parse(node.asText()).toMillis();
        }
        JsonNode topNode = topDelivery.path("retry").path(field);
        if (topNode.isTextual()) {
            return DslDurations.parse(topNode.asText()).toMillis();
        }
        return defaultValue;
    }

    /**
     * 재시도 backoff 배수를 해석한다.
     */
    private double retryMultiplier(JsonNode deliveryNode, JsonNode topDelivery) {
        if (deliveryNode.path("retry").path("multiplier").isNumber()) {
            return deliveryNode.path("retry").path("multiplier").asDouble();
        }
        if (topDelivery.path("retry").path("multiplier").isNumber()) {
            return topDelivery.path("retry").path("multiplier").asDouble();
        }
        return 2.0;
    }

    /**
     * event·상위 delivery 노드 중 존재하는 첫 노드를 고른다.
     */
    private JsonNode firstPresent(JsonNode first, JsonNode second) {
        if (first != null && !first.isMissingNode() && first.isArray() && !first.isEmpty()) {
            return first;
        }
        if (second != null && !second.isMissingNode() && second.isArray() && !second.isEmpty()) {
            return second;
        }
        return null;
    }

    /**
     * JsonNode 값을 Java 타입으로 변환해 template 렌더링 시 JSON 타입을 보존한다.
     *
     * <p>문자열은 String, 정수는 Long, 실수는 Double, boolean은 Boolean,
     * null은 null, 객체·배열은 JsonNode 그대로 반환한다.</p>
     */
    private Object toJavaValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isInt() || node.isLong()) {
            return node.asLong();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        // 객체·배열은 JsonNode 그대로 반환해 TemplateRenderer가 타입을 보존한다.
        return node;
    }
}
