package io.outcomeprobe.probe;

import io.outcomeprobe.evidence.AppendEvidenceCommand;
import io.outcomeprobe.evidence.EvidenceSource;
import io.outcomeprobe.evidence.EvidenceType;
import io.outcomeprobe.foundation.HashValue;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Probe attempt를 원문 없는 표준 Evidence payload로 변환한다.
 *
 * <p>책임: 요청 시작 Evidence와 응답·판정 Evidence를 분리하고, DB attempt와
 * {@code PROBE_RESPONSE_RECEIVED}가 같은 판정·assertion 결과를 공유하도록 만든다.</p>
 * <p>핵심 불변식: 이 factory는 ProbeAttempt에 존재하는 해시와 redaction된 assertion만
 * 사용하며 응답 body 원문을 받을 수 있는 인수가 없다.</p>
 */
public final class ProbeAttemptEvidenceFactory {

    private static final int MAX_PAYLOAD_SUMMARY_BYTES = 16 * 1024;
    private static final String TRANSMISSION_PLANNED = "PLANNED";
    private static final String TRANSMISSION_UNKNOWN = "UNKNOWN";
    private static final String RETRY_PENDING = "PENDING";
    private static final String RESULT_NOT_COMMITTED = "RESULT_NOT_COMMITTED";
    private static final String PROBE_RESULT_NOT_COMMITTED = "PROBE_RESULT_NOT_COMMITTED";

    /** 인스턴스 생성을 막는다. */
    private ProbeAttemptEvidenceFactory() {
    }

    /**
     * HTTP 전송 전 확정된 요청 의도만 PROBE_ATTEMPTED 증거로 만든다.
     *
     * <p>전송 성공 여부는 아직 알 수 없으므로 {@code transmissionState=PLANNED}만 기록한다.
     * 응답 상태·본문 해시·assertion 결과는 이 단계에서 생성하지 않는다.</p>
     *
     * @param intent 영속화된 Probe 요청 의도
     * @param path Target Profile의 요청 경로
     * @return 요청 시각과 계획 상태만 포함한 Evidence 명령
     */
    public static AppendEvidenceCommand attempted(ProbeAttemptIntent intent, String path) {
        String safePath = safePath(path);
        ObjectNode requestPayload = JsonNodeFactory.instance.objectNode();
        requestPayload.put("attemptNumber", intent.attemptNumber());
        requestPayload.put("path", safePath);
        requestPayload.put("requestedAt", intent.requestedAt().toString());
        requestPayload.put("transmissionState", TRANSMISSION_PLANNED);
        AppendEvidenceCommand command = AppendEvidenceCommand
                .of(intent.runId(), EvidenceType.PROBE_ATTEMPTED, EvidenceSource.PROBE)
                .withCorrelation(intent.stepId(), null, null)
                .withAttribute("attemptNumber", String.valueOf(intent.attemptNumber()))
                .withAttribute("path", safePath)
                .withAttribute("requestedAt", intent.requestedAt().toString())
                .withAttribute("transmissionState", TRANSMISSION_PLANNED)
                .withPayload(requestPayload.toString(), null, false)
                .withObservedAt(intent.requestedAt());
        return markPathRedaction(command, path, safePath);
    }

    /**
     * 응답 결과가 영속화되기 전에 중단된 요청 의도를 보수적인 복구 증거로 만든다.
     *
     * <p>실제 전송 여부를 알 수 없으므로 전송 상태는 {@code UNKNOWN}, 재시도 결정은
     * {@code PENDING}으로 고정한다. 응답 시각·HTTP 상태·본문 해시·assertion 결과는
     * 관측하지 않은 값이므로 만들지 않는다.</p>
     *
     * @param intent 결과가 확정되지 않은 Probe 요청 의도
     * @param path Target Profile의 요청 경로
     * @return 결과 미확정 사실만 허용 목록으로 기록한 Evidence 명령
     */
    public static AppendEvidenceCommand interrupted(ProbeAttemptIntent intent, String path) {
        String safePath = safePath(path);
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("attemptNumber", intent.attemptNumber());
        payload.put("path", safePath);
        payload.put("requestedAt", intent.requestedAt().toString());
        payload.put("transmissionState", TRANSMISSION_UNKNOWN);
        payload.put("retryDecision", RETRY_PENDING);
        payload.put("failureCode", PROBE_RESULT_NOT_COMMITTED);
        payload.put("bodyHashUnavailableReason", RESULT_NOT_COMMITTED);
        AppendEvidenceCommand command = AppendEvidenceCommand
                .of(intent.runId(), EvidenceType.PROBE_ATTEMPT_INTERRUPTED,
                        EvidenceSource.PROBE)
                .withCorrelation(intent.stepId(), null, null)
                .withAttribute("attemptNumber", String.valueOf(intent.attemptNumber()))
                .withAttribute("path", safePath)
                .withAttribute("requestedAt", intent.requestedAt().toString())
                .withAttribute("transmissionState", TRANSMISSION_UNKNOWN)
                .withAttribute("retryDecision", RETRY_PENDING)
                .withAttribute("failureCode", PROBE_RESULT_NOT_COMMITTED)
                .withAttribute("bodyHashUnavailableReason", RESULT_NOT_COMMITTED)
                .withPayload(payload.toString(), null, false);
        return markPathRedaction(command, path, safePath);
    }

    /**
     * 실제 응답·연결 실패 관측 시각과 안전 payload를 가진 PROBE_RESPONSE_RECEIVED를 만든다.
     *
     * @param attempt 저장할 Probe attempt
     * @param path 요청한 Profile 상대 경로
     * @param redactionApplied assertion redaction 또는 크기 제한 적용 여부
     * @return respondedAt을 observed time으로 가진 응답 Evidence 명령
     */
    public static AppendEvidenceCommand responseReceived(ProbeAttempt attempt, String path,
                                                         boolean redactionApplied) {
        String safePath = safePath(path);
        ObjectNode payload = boundedPayload(attempt);
        boolean effectiveRedaction = redactionApplied
                || payload.path("payloadTruncated").asBoolean(false)
                || !safePath.equals(path);
        AppendEvidenceCommand command = AppendEvidenceCommand
                .of(attempt.runId(), EvidenceType.PROBE_RESPONSE_RECEIVED, EvidenceSource.PROBE)
                .withCorrelation(attempt.stepId(), null, null)
                .withAttribute("attemptNumber", String.valueOf(attempt.attemptNumber()))
                .withAttribute("status", String.valueOf(attempt.statusCode()))
                .withAttribute("retryDecision", attempt.decision().name())
                .withAttribute("path", safePath)
                .withPayload(payload.toString(), attempt.responseHash(), effectiveRedaction)
                .withObservedAt(attempt.respondedAt());
        command = markPathRedaction(command, path, safePath);
        if (attempt.failureCode() != null) {
            command = command.withAttribute("failureCode", attempt.failureCode());
        }
        if (attempt.responseHash() == null) {
            command = command.withAttribute("bodyHashUnavailableReason",
                    bodyHashUnavailableReason(attempt));
        }
        return command;
    }

    /** 완전한 응답 hash가 없는 이유를 모호한 null 대신 고정 코드로 설명한다. */
    private static String bodyHashUnavailableReason(ProbeAttempt attempt) {
        return switch (String.valueOf(attempt.failureCode())) {
            case "TARGET_RESPONSE_TOO_LARGE" -> "BODY_TOO_LARGE";
            case "TARGET_REDIRECT_REJECTED" -> "REDIRECT_BODY_NOT_READ";
            default -> attempt.statusCode() == 0 ? "NO_HTTP_RESPONSE" : "BODY_NOT_AVAILABLE";
        };
    }

    /** query와 fragment 값을 Evidence에 저장하지 않고 상대 path만 남긴다. */
    private static String safePath(String path) {
        int query = path.indexOf('?');
        int fragment = path.indexOf('#');
        int boundary = query < 0 ? fragment
                : fragment < 0 ? query : Math.min(query, fragment);
        return boundary < 0 ? path : path.substring(0, boundary);
    }

    /** path 일부를 생략했음을 원문 없는 속성으로 표시한다. */
    private static AppendEvidenceCommand markPathRedaction(
            AppendEvidenceCommand command, String original, String safe) {
        return safe.equals(original)
                ? command : command.withAttribute("pathQueryOrFragmentRedacted", "true");
    }

    /**
     * deadline 문제 상세에도 재사용할 수 있는 원문 없는 attempt 요약을 만든다.
     *
     * @param attempt 요약할 Probe attempt
     * @return 표준 필드를 가진 안전한 JSON 노드
     */
    public static JsonNode safeDetail(ProbeAttempt attempt) {
        return boundedPayload(attempt);
    }

    /**
     * DB assertion_results와 Step output이 공유할 16KiB 이내 assertion envelope를 만든다.
     *
     * <p>전체 assertion JSON이 상한을 넘으면 원문 목록을 버리고 전체값 hash와
     * truncation 표지만 남긴다.</p>
     *
     * @param attempt redaction과 개별 값 상한이 적용된 Probe attempt
     * @return assertions 배열과 optional truncation 메타를 가진 안전 JSON
     */
    public static JsonNode safeAssertions(ProbeAttempt attempt) {
        ObjectNode envelope = JsonNodeFactory.instance.objectNode();
        envelope.set("assertions", assertionArray(attempt));
        byte[] bytes = envelope.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_PAYLOAD_SUMMARY_BYTES) {
            return envelope;
        }
        ObjectNode bounded = JsonNodeFactory.instance.objectNode();
        bounded.set("assertions", compactAssertionArray(attempt));
        bounded.put("payloadTruncated", true);
        bounded.put("payloadHash", HashValue.sha256(bytes).hexValue());
        return bounded;
    }

    /**
     * payloadSummary가 UTF-8 16KiB를 넘으면 assertion 원문 없이 hash marker로 축약한다.
     */
    private static ObjectNode boundedPayload(ProbeAttempt attempt) {
        ObjectNode full = payload(attempt);
        byte[] bytes = full.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_PAYLOAD_SUMMARY_BYTES) {
            return full;
        }
        ObjectNode bounded = JsonNodeFactory.instance.objectNode();
        bounded.put("attemptNumber", attempt.attemptNumber());
        bounded.put("requestedAt", attempt.requestedAt().toString());
        bounded.put("respondedAt", attempt.respondedAt().toString());
        bounded.put("statusCode", attempt.statusCode());
        if (attempt.responseHash() == null) {
            bounded.putNull("responseHash");
        } else {
            bounded.put("responseHash", attempt.responseHash());
        }
        JsonNode safeAssertions = safeAssertions(attempt);
        bounded.set("assertions", safeAssertions.path("assertions").deepCopy());
        bounded.put("retryDecision", attempt.decision().name());
        if (attempt.failureCode() == null) {
            bounded.putNull("failureCode");
        } else {
            bounded.put("failureCode", attempt.failureCode());
        }
        bounded.put("payloadTruncated", true);
        bounded.put("payloadHash", HashValue.sha256(bytes).hexValue());
        return bounded;
    }

    /**
     * Evidence 계약의 고정 필드를 순서대로 조립한다.
     */
    private static ObjectNode payload(ProbeAttempt attempt) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("attemptNumber", attempt.attemptNumber());
        payload.put("requestedAt", attempt.requestedAt().toString());
        payload.put("respondedAt", attempt.respondedAt().toString());
        payload.put("statusCode", attempt.statusCode());
        if (attempt.responseHash() == null) {
            payload.putNull("responseHash");
        } else {
            payload.put("responseHash", attempt.responseHash());
        }
        JsonNode safeAssertions = safeAssertions(attempt);
        payload.set("assertions", safeAssertions.path("assertions").deepCopy());
        if (safeAssertions.path("payloadTruncated").asBoolean(false)) {
            payload.put("payloadTruncated", true);
            payload.put("payloadHash", safeAssertions.path("payloadHash").asText());
        }
        payload.put("retryDecision", attempt.decision().name());
        if (attempt.failureCode() == null) {
            payload.putNull("failureCode");
        } else {
            payload.put("failureCode", attempt.failureCode());
        }
        return payload;
    }

    /**
     * attempt의 redaction된 assertion 결과를 계약 배열로 변환한다.
     */
    private static ArrayNode assertionArray(ProbeAttempt attempt) {
        ArrayNode assertions = JsonNodeFactory.instance.arrayNode();
        for (AssertionEvaluator.AssertionOutcome outcome : attempt.assertionOutcomes()) {
            ObjectNode assertion = assertions.addObject();
            assertion.put("jsonPath", outcome.jsonPath());
            assertion.put("operator", outcome.operator().name());
            if (outcome.expected() == null) {
                assertion.putNull("expected");
            } else {
                assertion.put("expected", outcome.expected());
            }
            if (outcome.actual() == null) {
                assertion.putNull("actual");
            } else {
                assertion.put("actual", outcome.actual());
            }
            assertion.put("passed", outcome.passed());
        }
        return assertions;
    }

    /** 큰 envelope에서도 각 assertion의 path·operator·passed와 값 hash를 보존한다. */
    private static ArrayNode compactAssertionArray(ProbeAttempt attempt) {
        ArrayNode assertions = JsonNodeFactory.instance.arrayNode();
        for (AssertionEvaluator.AssertionOutcome outcome : attempt.assertionOutcomes()) {
            ObjectNode assertion = assertions.addObject();
            assertion.put("jsonPath", hashMarker(outcome.jsonPath()));
            assertion.put("operator", outcome.operator().name());
            putCompactDiagnostic(assertion, "expected", outcome.expected());
            putCompactDiagnostic(assertion, "actual", outcome.actual());
            assertion.put("passed", outcome.passed());
        }
        return assertions;
    }

    /** nullable 진단값을 원문 없는 hash marker로 넣는다. */
    private static void putCompactDiagnostic(ObjectNode target, String field, String value) {
        if (value == null) {
            target.putNull(field);
        } else {
            target.put(field, hashMarker(value));
        }
    }

    /** UTF-8 값의 길이와 SHA-256만 가진 고정 marker를 만든다. */
    private static String hashMarker(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return "[HASH bytes=" + bytes.length + " sha256="
                + HashValue.sha256(bytes).hexValue() + "]";
    }
}
