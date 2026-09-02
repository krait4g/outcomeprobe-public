package io.outcomeprobe.probe;

import static org.assertj.core.api.Assertions.assertThat;

import io.outcomeprobe.evidence.AppendEvidenceCommand;
import io.outcomeprobe.evidence.EvidenceType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Probe 요청 의도·중단·응답 Evidence의 필드 계약과 원문 비노출을 검증한다.
 *
 * <p>검증 불변식: 요청 의도와 중단 Evidence는 관측하지 않은 응답을 만들지 않고,
 * 응답 Evidence의 payload에는 표준 판정·해시·redaction된 assertion만 남긴다.</p>
 */
class ProbeAttemptEvidenceFactoryTest {

    /** 표준 payload와 속성을 만들고 Probe에 계획 logical time을 넣지 않는다. */
    @Test
    void createsSafeObservedAttemptEvidence() throws Exception {
        Instant requestedAt = Instant.parse("2026-08-06T01:02:03.103456Z");
        ProbeAttemptIntent intent = new ProbeAttemptIntent(
                "attempt-3", "run-1", "step-1", 3, requestedAt);

        AppendEvidenceCommand command = ProbeAttemptEvidenceFactory.attempted(
                intent, "/state/1");
        JsonNode payload = new ObjectMapper().readTree(command.payloadSummary());

        assertThat(command.type()).isEqualTo(EvidenceType.PROBE_ATTEMPTED);
        assertThat(command.logicalTimeMs()).isNull();
        assertThat(command.observedAt()).isEqualTo(requestedAt);
        assertThat(command.bodyHash()).isNull();
        assertThat(command.attributes()).containsEntry("attemptNumber", "3")
                .containsEntry("path", "/state/1")
                .containsEntry("requestedAt", requestedAt.toString())
                .containsEntry("transmissionState", "PLANNED")
                .doesNotContainKeys("status", "retryDecision", "failureCode");
        assertThat(payload.path("requestedAt").asText())
                .isEqualTo(requestedAt.toString());
        assertThat(payload.path("transmissionState").asText()).isEqualTo("PLANNED");
        assertThat(payload.has("respondedAt")).isFalse();
        assertThat(payload.has("responseHash")).isFalse();
        assertThat(payload.has("assertions")).isFalse();
        assertThat(command.payloadSummary()).doesNotContain("secret");
    }

    /** 복구 Evidence는 결과 미확정 사실만 기록하고 응답 관측값을 만들지 않는다. */
    @Test
    void recordsInterruptedAttemptWithoutInventingResponseFacts() throws Exception {
        Instant requestedAt = Instant.parse("2026-08-06T01:02:03.103456Z");
        ProbeAttemptIntent intent = new ProbeAttemptIntent(
                "attempt-3", "run-1", "step-1", 3, requestedAt);

        AppendEvidenceCommand command = ProbeAttemptEvidenceFactory.interrupted(
                intent, "/state/1?credential=must-not-leak");
        JsonNode payload = new ObjectMapper().readTree(command.payloadSummary());

        assertThat(command.type()).isEqualTo(EvidenceType.PROBE_ATTEMPT_INTERRUPTED);
        assertThat(command.logicalTimeMs()).isNull();
        assertThat(command.observedAt()).isNull();
        assertThat(command.bodyHash()).isNull();
        assertThat(command.attributes())
                .containsEntry("attemptNumber", "3")
                .containsEntry("path", "/state/1")
                .containsEntry("requestedAt", requestedAt.toString())
                .containsEntry("transmissionState", "UNKNOWN")
                .containsEntry("retryDecision", "PENDING")
                .containsEntry("failureCode", "PROBE_RESULT_NOT_COMMITTED")
                .containsEntry("bodyHashUnavailableReason", "RESULT_NOT_COMMITTED")
                .containsEntry("pathQueryOrFragmentRedacted", "true")
                .doesNotContainKeys("status", "statusCode", "respondedAt", "bodyHash");
        assertThat(payload.properties().stream().map(entry -> entry.getKey()).toList())
                .containsExactlyInAnyOrder(
                "attemptNumber", "path", "requestedAt", "transmissionState",
                "retryDecision", "failureCode", "bodyHashUnavailableReason");
        assertThat(payload.path("transmissionState").asText()).isEqualTo("UNKNOWN");
        assertThat(payload.path("retryDecision").asText()).isEqualTo("PENDING");
        assertThat(payload.path("failureCode").asText())
                .isEqualTo("PROBE_RESULT_NOT_COMMITTED");
        assertThat(payload.path("bodyHashUnavailableReason").asText())
                .isEqualTo("RESULT_NOT_COMMITTED");
        assertThat(payload.has("respondedAt")).isFalse();
        assertThat(payload.has("status")).isFalse();
        assertThat(payload.has("statusCode")).isFalse();
        assertThat(payload.has("bodyHash")).isFalse();
        assertThat(payload.has("assertions")).isFalse();
        assertThat(command.payloadSummary()).doesNotContain("must-not-leak");
    }

    /** 응답 Evidence는 respondedAt을 관측 시각으로 사용하고 logical time을 비워야 한다. */
    @Test
    void recordsResponseAtActualRespondedTime() {
        Instant respondedAt = Instant.parse("2026-08-06T01:02:03Z");
        ProbeAttempt attempt = new ProbeAttempt(null, "run-1", "step-1", 1,
                respondedAt.minusMillis(5), respondedAt, 503, "b".repeat(64), List.of(),
                ProbeDecision.PENDING, "TARGET_RESPONSE_UNEXPECTED");

        AppendEvidenceCommand command = ProbeAttemptEvidenceFactory.responseReceived(
                attempt, "/state/1", false);

        assertThat(command.observedAt()).isEqualTo(respondedAt);
        assertThat(command.logicalTimeMs()).isNull();
        assertThat(command.attributes()).containsEntry("failureCode",
                "TARGET_RESPONSE_UNEXPECTED");
    }

    /** query·fragment 값은 요청/응답 Evidence와 Report용 속성에 남지 않아야 한다. */
    @Test
    void redactsQueryAndFragmentFromEvidencePath() {
        ProbeAttempt attempt = new ProbeAttempt(null, "run-1", "step-1", 1,
                Instant.EPOCH, Instant.EPOCH, 200, "b".repeat(64), List.of(),
                ProbeDecision.CONVERGED, null);
        ProbeAttemptIntent intent = new ProbeAttemptIntent(
                "attempt-1", "run-1", "step-1", 1, Instant.EPOCH);
        String unsafePath = "/state/1?access_token=must-not-leak#fragment";

        AppendEvidenceCommand request = ProbeAttemptEvidenceFactory.attempted(
                intent, unsafePath);
        AppendEvidenceCommand response = ProbeAttemptEvidenceFactory.responseReceived(
                attempt, unsafePath, false);

        assertThat(request.attributes()).containsEntry("path", "/state/1")
                .containsEntry("pathQueryOrFragmentRedacted", "true");
        assertThat(response.attributes()).containsEntry("path", "/state/1")
                .containsEntry("pathQueryOrFragmentRedacted", "true");
        assertThat(request.payloadSummary()).doesNotContain("must-not-leak", "fragment");
        assertThat(response.redactionApplied()).isTrue();
    }

    /** 과대 body의 완전한 hash를 만들 수 없으면 누락 이유를 명시해야 한다. */
    @Test
    void explainsUnavailableHashForOversizedBody() {
        ProbeAttempt attempt = new ProbeAttempt(null, "run-1", "step-1", 1,
                Instant.EPOCH, Instant.EPOCH, 200, null, List.of(),
                ProbeDecision.INVALID_RESPONSE, "TARGET_RESPONSE_TOO_LARGE");

        AppendEvidenceCommand response = ProbeAttemptEvidenceFactory.responseReceived(
                attempt, "/state/1", false);

        assertThat(response.bodyHash()).isNull();
        assertThat(response.attributes())
                .containsEntry("bodyHashUnavailableReason", "BODY_TOO_LARGE");
    }

    /** 16KiB를 넘는 payloadSummary는 assertion 원문 없이 hash marker로 축약해야 한다. */
    @Test
    void boundsPayloadSummaryWithoutKeepingLargeAssertionText() throws Exception {
        String largePath = "$." + "secret-path-".repeat(2000);
        ProbeAttempt attempt = new ProbeAttempt(null, "run-1", "step-1", 1,
                Instant.EPOCH, Instant.EPOCH, 200, "c".repeat(64),
                List.of(new AssertionEvaluator.AssertionOutcome(
                        largePath, AssertionOperator.EQUALS, "safe", "safe", true)),
                ProbeDecision.CONVERGED, null);

        AppendEvidenceCommand command = ProbeAttemptEvidenceFactory.responseReceived(
                attempt, "/state/1", false);
        JsonNode payload = new ObjectMapper().readTree(command.payloadSummary());
        JsonNode databaseAssertions = ProbeAttemptEvidenceFactory.safeAssertions(attempt);

        assertThat(command.payloadSummary().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(16 * 1024);
        assertThat(databaseAssertions.toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(16 * 1024);
        assertThat(payload.path("payloadTruncated").asBoolean()).isTrue();
        assertThat(databaseAssertions.path("payloadTruncated").asBoolean()).isTrue();
        assertThat(command.redactionApplied()).isTrue();
        assertThat(payload.path("payloadHash").asText()).hasSize(64);
        assertThat(payload.path("assertions")).hasSize(1);
        assertThat(payload.path("assertions").get(0).path("operator").asText())
                .isEqualTo("EQUALS");
        assertThat(payload.path("assertions").get(0).path("passed").asBoolean()).isTrue();
        assertThat(payload.path("assertions").get(0).path("jsonPath").asText())
                .startsWith("[HASH bytes=");
        assertThat(command.payloadSummary()).doesNotContain("secret-path-");
    }
}
