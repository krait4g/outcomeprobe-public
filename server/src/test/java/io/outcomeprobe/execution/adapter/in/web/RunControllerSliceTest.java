package io.outcomeprobe.execution.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.outcomeprobe.execution.CapacityLimitExceededException;
import io.outcomeprobe.execution.RunEngine;
import io.outcomeprobe.execution.RunRequestIdempotencyService;
import io.outcomeprobe.execution.RunStatus;
import io.outcomeprobe.execution.ScenarioRun;
import io.outcomeprobe.foundation.ProblemCode;
import io.outcomeprobe.interfaceapi.GlobalProblemHandler;
import io.outcomeprobe.scenario.ArtifactImportException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * RunController의 HTTP 계약·상태 코드·problem+json 매핑을 검증한다.
 *
 * <p>검출 가능한 결함: cancel 404 미반환, capacity 429 미매핑, 존재하지 않는
 * Run 조회 시 500 누출, scenarioRef 누락 시 400 미반환.</p>
 */
class RunControllerSliceTest {

    private MockMvc mockMvc;
    private final RunEngine runEngine = mock(RunEngine.class);
    private final RunRequestIdempotencyService idempotencyService =
            mock(RunRequestIdempotencyService.class);

    /**
     * standalone MockMvc에 Controller와 GlobalProblemHandler를 등록한다.
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RunController(runEngine, idempotencyService))
                .setControllerAdvice(new GlobalProblemHandler())
                .build();
    }

    /**
     * 테스트용 Run fixture를 생성한다.
     */
    private ScenarioRun run(String id, RunStatus status) {
        return new ScenarioRun(id, "payment-timeout", "1.0.0", "abc",
                "vulnerable", 42L, status, 0, null,
                null, null, null, null, 1, 1, false,
                null, null, Instant.now(), null, Instant.now());
    }

    /**
     * Run 생성 API 계약을 검증한다.
     */
    @Nested
    @DisplayName("POST /api/v1/runs")
    class CreateRun {

        /**
         * 유효한 요청은 202 + Location + runId를 반환한다.
         */
        @Test
        @DisplayName("유효 요청 → 202 + runId")
        void validRequestReturns202() throws Exception {
            when(runEngine.createRun(any())).thenReturn("new-run-id");

            mockMvc.perform(post("/api/v1/runs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"scenarioRef":{"name":"payment-timeout","version":"1.0.0"},
                                     "targetProfile":"vulnerable","seed":42,"variables":{}}
                                    """))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.runId").value("new-run-id"));
        }

        /**
         * Web intent key가 있으면 durable idempotency service의 run 식별자를 반환한다.
         */
        @Test
        @DisplayName("Idempotency-Key가 있으면 멱등 생성 경계 사용")
        void idempotencyKeyUsesDurableBoundary() throws Exception {
            when(idempotencyService.createRun(any(),
                    org.mockito.ArgumentMatchers.eq("web-intent-key-0123456789")))
                    .thenReturn("1d46928b-f1a0-4ccd-a143-c4b0bacb03cd");

            mockMvc.perform(post("/api/v1/runs")
                            .header("Idempotency-Key", "web-intent-key-0123456789")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"scenarioRef":{"name":"payment-timeout","version":"1.0.0"},
                                     "targetProfile":"vulnerable","seed":42,"variables":{}}
                                    """))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.runId")
                            .value("1d46928b-f1a0-4ccd-a143-c4b0bacb03cd"));
        }

        /**
         * scenarioRef.name 누락은 400을 반환한다.
         */
        @Test
        @DisplayName("scenarioRef.name 누락 → 400")
        void missingScenarioRefReturns400() throws Exception {
            mockMvc.perform(post("/api/v1/runs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"targetProfile":"vulnerable","seed":42}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        /**
         * capacity 초과 시 429를 반환한다.
         */
        @Test
        @DisplayName("capacity 초과 → 429")
        void capacityExceededReturns429() throws Exception {
            when(runEngine.createRun(any()))
                    .thenThrow(new CapacityLimitExceededException("상한 초과: 10"));

            mockMvc.perform(post("/api/v1/runs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"scenarioRef":{"name":"x","version":"1.0.0"},
                                     "targetProfile":"t","seed":1,"variables":{}}
                                    """))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("CAPACITY_LIMIT_EXCEEDED"));
        }

        /**
         * Scenario 미존재 시 400 + problem code를 반환한다.
         */
        @Test
        @DisplayName("Scenario 미존재 → 400 + SCENARIO_REFERENCE_NOT_FOUND")
        void scenarioNotFoundReturns400() throws Exception {
            when(runEngine.createRun(any()))
                    .thenThrow(new ArtifactImportException(
                            ProblemCode.SCENARIO_REFERENCE_NOT_FOUND, "scenarioRef", "없음"));

            mockMvc.perform(post("/api/v1/runs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"scenarioRef":{"name":"no-such","version":"1.0.0"},
                                     "targetProfile":"t","seed":1,"variables":{}}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("SCENARIO_REFERENCE_NOT_FOUND"));
        }
    }

    /**
     * Run 조회·취소 API 계약을 검증한다.
     */
    @Nested
    @DisplayName("GET/POST /api/v1/runs/{runId}")
    class GetAndCancel {

        /**
         * 존재하는 Run 조회는 200 + 요약을 반환한다.
         */
        @Test
        @DisplayName("존재하는 Run → 200 + 요약")
        void existingRunReturns200() throws Exception {
            when(runEngine.findRun("run-1")).thenReturn(Optional.of(run("run-1", RunStatus.PASSED)));

            mockMvc.perform(get("/api/v1/runs/run-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.runId").value("run-1"))
                    .andExpect(jsonPath("$.status").value("PASSED"));
        }

        /**
         * 존재하지 않는 Run 조회는 404를 반환한다.
         */
        @Test
        @DisplayName("미존재 Run → 404")
        void missingRunReturns404() throws Exception {
            when(runEngine.findRun("no-such")).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/v1/runs/no-such"))
                    .andExpect(status().isNotFound());
        }

        /**
         * 활성 Run 취소는 202 + requested:true를 반환한다.
         */
        @Test
        @DisplayName("활성 Run 취소 → 202 + requested:true")
        void cancelActiveRunReturns202() throws Exception {
            when(runEngine.requestCancel("run-1")).thenReturn(true);

            mockMvc.perform(post("/api/v1/runs/run-1/cancel"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.requested").value(true));
        }

        /**
         * terminal·미존재 Run 취소는 404를 반환한다.
         */
        @Test
        @DisplayName("terminal Run 취소 → 404")
        void cancelTerminalRunReturns404() throws Exception {
            when(runEngine.requestCancel("run-1")).thenReturn(false);

            mockMvc.perform(post("/api/v1/runs/run-1/cancel"))
                    .andExpect(status().isNotFound());
        }
    }

    /**
     * Run 목록 조회 API 계약을 검증한다.
     */
    @Nested
    @DisplayName("GET /api/v1/runs")
    class ListRuns {

        /**
         * limit 파라미터가 상한(200)으로 클램프된다.
         */
        @Test
        @DisplayName("limit 초과는 200으로 클램프된다")
        void limitClamped() throws Exception {
            when(runEngine.findRecentRuns(200)).thenReturn(List.of(run("r1", RunStatus.PASSED)));

            mockMvc.perform(get("/api/v1/runs").param("limit", "9999"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].runId").value("r1"));
        }

        /**
         * offset으로 다음 페이지를 요청하면 해당 구간 Run을 반환한다.
         *
         * <p>검출 가능한 결함: limit건만 조회한 뒤 offset을 skip하면 offset≥limit일 때
         * 항상 빈 목록이 반환된다(OP-AUDIT-07-PAG-001). offset+limit 조회로 회귀를 막는다.</p>
         */
        @Test
        @DisplayName("offset=1·limit=1 → 두 번째 Run 반환 (빈 페이지 금지)")
        void offsetReturnsSecondPage() throws Exception {
            when(runEngine.findRecentRuns(2)).thenReturn(List.of(
                    run("r1", RunStatus.PASSED), run("r2", RunStatus.FAILED)));

            mockMvc.perform(get("/api/v1/runs").param("offset", "1").param("limit", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].runId").value("r2"));
        }
    }
}
