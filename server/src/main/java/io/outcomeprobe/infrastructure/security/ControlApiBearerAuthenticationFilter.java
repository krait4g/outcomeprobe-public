package io.outcomeprobe.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 보호 경로의 static Bearer token을 Spring Security 인증으로 변환한다.
 *
 * <p>책임: Control API와 보호된 metrics 요청에서 Authorization header를 정확히 하나만
 * 받아 검증하고, 성공한 요청에는 credential이 없는 운영자 principal만 설정한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: Emulator의 {@code X-OutcomeProbe-Emulator-Token}은
 * 이 filter의 대상이 아니며 EmulatorController가 별도 신뢰 경계에서 검증한다.</p>
 */
public final class ControlApiBearerAuthenticationFilter extends OncePerRequestFilter {

    private static final String CONTROL_OPERATOR = "outcomeprobe-control-operator";
    private static final String CONTROL_OPERATOR_ROLE = "ROLE_CONTROL_OPERATOR";

    private final ControlApiTokenVerifier tokenVerifier;
    private final ControlApiAuthenticationEntryPoint authenticationEntryPoint;

    /**
     * token 검증기와 값 비노출 실패 응답기를 주입한다.
     *
     * @param tokenVerifier configured token digest 검증기
     * @param authenticationEntryPoint 인증 실패 응답기
     */
    public ControlApiBearerAuthenticationFilter(
            ControlApiTokenVerifier tokenVerifier,
            ControlApiAuthenticationEntryPoint authenticationEntryPoint) {
        this.tokenVerifier = tokenVerifier;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    /**
     * Control API와 보호된 Prometheus endpoint 외의 요청은 별도 인증 경계에 맡긴다.
     *
     * @param request 현재 servlet 요청
     * @return 이 filter가 처리하지 않을 요청이면 {@code true}
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }
        String path = applicationPath(request);
        return !(path.equals("/api/v1") || path.startsWith("/api/v1/")
                || path.equals("/actuator/prometheus"));
    }

    /**
     * 단일 Authorization header를 검증하고 성공한 요청만 다음 filter로 전달한다.
     *
     * @param request 보호 경로 요청
     * @param response 인증 실패 응답 또는 downstream 응답
     * @param filterChain 다음 servlet filter chain
     * @throws ServletException downstream servlet 처리에 실패한 경우
     * @throws IOException 요청·응답 처리에 실패한 경우
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String authorizationHeader = singleAuthorizationHeader(request);
        if (!tokenVerifier.matchesBearerHeader(authorizationHeader)) {
            authenticationEntryPoint.commence(request, response,
                    new BadCredentialsException("Control API 인증에 실패했습니다."));
            return;
        }

        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        CONTROL_OPERATOR, null,
                        List.of(new SimpleGrantedAuthority(CONTROL_OPERATOR_ROLE)));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        filterChain.doFilter(request, response);
    }

    /**
     * 중복 Authorization header를 모호하게 병합하지 않고 정확히 한 값만 반환한다.
     *
     * @param request 현재 servlet 요청
     * @return header가 정확히 하나면 값, 없거나 중복이면 {@code null}
     */
    private String singleAuthorizationHeader(HttpServletRequest request) {
        Enumeration<String> headers = request.getHeaders(HttpHeaders.AUTHORIZATION);
        if (headers == null || !headers.hasMoreElements()) {
            return null;
        }
        String value = headers.nextElement();
        if (headers.hasMoreElements()) {
            return null;
        }
        return value;
    }

    /**
     * context path를 제외한 애플리케이션 경로를 반환한다.
     *
     * @param request 현재 servlet 요청
     * @return security matcher와 비교할 절대 경로
     */
    private String applicationPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }
        return requestUri;
    }
}
