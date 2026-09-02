package io.outcomeprobe.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.DispatcherType;
import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Control API의 HTTP 인증·공개 endpoint·CORS·SSE 경계를 검증한다.
 *
 * <p>책임: 모든 {@code /api/v1/**} 요청에 static Bearer token이 필요하고,
 * health·info·Emulator는 그 token과 분리되며 Prometheus는 보호되는지 확인한다.</p>
 */
@WebMvcTest(
        controllers = ControlApiSecurityWebTest.SecurityBoundaryController.class,
        properties = "outcomeprobe.security.control-api-token="
                + ControlApiSecurityWebTest.TOKEN)
@Import({ControlApiSecurityConfiguration.class, ControlApiTokenVerifier.class,
        ControlApiSecurityWebTest.SecurityBoundaryController.class})
class ControlApiSecurityWebTest {

    static final String TOKEN = "control-api-test-token-0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    /** 인증 실패 header 표본을 제공한다. */
    static Stream<Arguments> invalidAuthorizationHeaders() {
        return Stream.of(
                Arguments.of((String) null),
                Arguments.of("Basic " + TOKEN),
                Arguments.of("Bearer wrong-control-api-token-0123456789abcdef"),
                Arguments.of("Bearer  " + TOKEN),
                Arguments.of("Bearer " + TOKEN + " "));
    }

    /** 누락·오류 credential은 같은 비노출 401 계약을 따른다. */
    @ParameterizedTest
    @MethodSource("invalidAuthorizationHeaders")
    void rejectsMissingOrInvalidControlApiCredential(String authorization) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request =
                get("/api/v1/test");
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }

        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer realm=\"outcomeprobe-control\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("CONTROL_API_UNAUTHORIZED"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(TOKEN)
                .doesNotContain("wrong-control-api-token");
    }

    /** 중복 Authorization header는 모호하게 결합하지 않고 거부한다. */
    @Test
    void rejectsDuplicateAuthorizationHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/test")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + TOKEN,
                                "Bearer wrong-control-api-token-0123456789abcdef"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CONTROL_API_UNAUTHORIZED"));
    }

    /** 정확한 Bearer token은 Control API 접근을 허용한다. */
    @Test
    void acceptsExactBearerTokenForControlApi() throws Exception {
        mockMvc.perform(get("/api/v1/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string("control"));
    }

    /** health probe와 info는 운영자 token 없이 공개된다. */
    @Test
    void exposesOnlyRequiredActuatorHealthAndInfoWithoutControlToken() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk());
    }

    /** Prometheus endpoint는 Control API와 같은 운영자 token을 요구한다. */
    @Test
    void protectsPrometheusEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk());
    }

    /** Emulator ingress는 Control API Bearer token과 별도 경계로 남는다. */
    @Test
    void keepsEmulatorOutsideControlApiAuthenticationBoundary() throws Exception {
        mockMvc.perform(get("/emulator/example/status"))
                .andExpect(status().isOk())
                .andExpect(content().string("emulator"));
    }

    /** Emulator 전용 header는 Control API 운영자 인증을 대신할 수 없다. */
    @Test
    void doesNotAcceptEmulatorTokenAsControlApiCredential() throws Exception {
        mockMvc.perform(get("/api/v1/test")
                        .header("X-OutcomeProbe-Emulator-Token", TOKEN))
                .andExpect(status().isUnauthorized());
    }

    /** SSE 연결의 최초 GET도 일반 Control API와 같은 Bearer token을 요구한다. */
    @Test
    void protectsServerSentEventConnection() throws Exception {
        mockMvc.perform(get("/api/v1/test/stream"))
                .andExpect(status().isUnauthorized());

        MvcResult initial = mockMvc.perform(get("/api/v1/test/stream")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string("data:ok\n\n"));
    }

    /** ERROR 재디스패치는 최초 인증 결과를 401·403으로 가리지 않는다. */
    @Test
    void permitsContainerErrorRedispatch() throws Exception {
        mockMvc.perform(get("/internal-error-boundary")
                        .with(request -> {
                            request.setDispatcherType(DispatcherType.ERROR);
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(content().string("error-dispatch"));
    }

    /** 허용한 loopback origin의 preflight는 token 없이 통과한다. */
    @Test
    void allowsLoopbackCorsPreflightWithoutCredential() throws Exception {
        mockMvc.perform(options("/api/v1/test")
                        .header(HttpHeaders.ORIGIN, "http://localhost:4173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                HttpHeaders.AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        "http://localhost:4173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        org.hamcrest.Matchers.containsString("GET")));
    }

    /** 허용 origin의 인증 실패도 browser가 읽을 수 있도록 CORS header를 보존한다. */
    @Test
    void preservesCorsHeaderOnAuthenticationFailure() throws Exception {
        mockMvc.perform(get("/api/v1/test")
                        .header(HttpHeaders.ORIGIN, "http://127.0.0.1:3000"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        "http://127.0.0.1:3000"));
    }

    /** 원격 origin의 preflight는 공개 인증 예외를 악용하지 못한다. */
    @Test
    void rejectsRemoteCorsPreflight() throws Exception {
        mockMvc.perform(options("/api/v1/test")
                        .header(HttpHeaders.ORIGIN, "https://attacker.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                HttpHeaders.AUTHORIZATION))
                .andExpect(status().isForbidden());
    }

    /** 보안 matcher를 검증하기 위한 최소 HTTP endpoint 모음이다. */
    @RestController
    public static class SecurityBoundaryController {

        /** Control API 응답을 반환한다. */
        @GetMapping("/api/v1/test")
        public String controlApi() {
            return "control";
        }

        /** SSE content type의 Control API 응답을 반환한다. */
        @GetMapping(value = "/api/v1/test/stream",
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public SseEmitter controlApiStream() {
            SseEmitter emitter = new SseEmitter(2_000L);
            Thread.ofVirtual().start(() -> {
                try {
                    emitter.send(SseEmitter.event().data("ok"));
                    emitter.complete();
                } catch (IOException exception) {
                    emitter.completeWithError(exception);
                }
            });
            return emitter;
        }

        /** 공개 liveness 응답을 반환한다. */
        @GetMapping("/actuator/health/liveness")
        public String liveness() {
            return "up";
        }

        /** 공개 readiness 응답을 반환한다. */
        @GetMapping("/actuator/health/readiness")
        public String readiness() {
            return "up";
        }

        /** 공개 info 응답을 반환한다. */
        @GetMapping("/actuator/info")
        public String info() {
            return "info";
        }

        /** 보호된 metrics 응답을 반환한다. */
        @GetMapping("/actuator/prometheus")
        public String prometheus() {
            return "metrics";
        }

        /** Emulator 분리 경계 응답을 반환한다. */
        @GetMapping("/emulator/example/status")
        public String emulator() {
            return "emulator";
        }

        /** 컨테이너 ERROR 재디스패치의 결과를 반환한다. */
        @GetMapping("/internal-error-boundary")
        public String errorDispatch() {
            return "error-dispatch";
        }
    }
}
