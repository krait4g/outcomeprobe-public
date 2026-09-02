package io.outcomeprobe.infrastructure.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Control API 인증 실패를 값 비노출 problem 응답으로 변환한다.
 *
 * <p>책임: token 누락과 불일치를 같은 401 응답으로 처리해 credential 존재 여부를
 * 구분할 단서를 주지 않는다.</p>
 * <p>핵심 불변식: 요청 Authorization 값과 설정 token은 응답 body·header·로그에
 * 절대 복사하지 않는다.</p>
 */
public final class ControlApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final byte[] RESPONSE_BODY = ("{\"type\":\"urn:outcomeprobe:problem:"
            + "control-api-unauthorized\",\"title\":\"Control API 인증이 필요합니다.\","
            + "\"status\":401,\"code\":\"CONTROL_API_UNAUTHORIZED\"}")
            .getBytes(StandardCharsets.UTF_8);

    /**
     * 인증 실패 원인을 노출하지 않는 고정 401 응답을 쓴다.
     *
     * @param request 인증되지 않은 요청
     * @param response 고정 problem 응답을 기록할 servlet 응답
     * @param authException Spring Security 인증 실패 정보
     * @throws IOException 응답 body 기록에 실패한 경우
     * @throws ServletException servlet 응답 처리에 실패한 경우
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException)
            throws IOException, ServletException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                "Bearer realm=\"outcomeprobe-control\"");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setContentLength(RESPONSE_BODY.length);
        response.getOutputStream().write(RESPONSE_BODY);
    }
}
