package io.outcomeprobe.infrastructure.security;

import jakarta.servlet.DispatcherType;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 단일 운영자 Control API 인증과 공개 health·Emulator 경계를 구성한다.
 *
 * <p>책임: 모든 {@code /api/v1/**} 요청과 Prometheus metrics에 static Bearer 인증을
 * 강제하면서 health·liveness·readiness·info와 Emulator ingress는 분리한다.</p>
 * <p>핵심 불변식: 세션·form login·HTTP Basic·CSRF fallback은 사용하지 않고,
 * 허용된 loopback CORS preflight만 credential 없이 통과시킨다.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class ControlApiSecurityConfiguration {

    /**
     * token 검증 filter와 경로별 최소 권한을 결속한다.
     *
     * @param http Spring Security HTTP 구성기
     * @param tokenVerifier static token 검증기
     * @param authenticationEntryPoint 값 비노출 401 응답기
     * @param controlApiCorsConfigurationSource loopback CORS 정책
     * @return stateless servlet security filter chain
     * @throws Exception Spring Security 구성에 실패한 경우
     */
    @Bean
    public SecurityFilterChain controlApiSecurityFilterChain(
            HttpSecurity http,
            ControlApiTokenVerifier tokenVerifier,
            ControlApiAuthenticationEntryPoint authenticationEntryPoint,
            @Qualifier("controlApiCorsConfigurationSource")
            CorsConfigurationSource controlApiCorsConfigurationSource) throws Exception {
        ControlApiBearerAuthenticationFilter bearerFilter =
                new ControlApiBearerAuthenticationFilter(tokenVerifier, authenticationEntryPoint);
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(controlApiCorsConfigurationSource))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint))
                .authorizeHttpRequests(authorize -> authorize
                        // ASYNC·ERROR는 외부 요청으로 지정할 수 없는 내부 재디스패치다.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers(CorsUtils::isPreFlightRequest).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/actuator/info", "/emulator/**")
                        .permitAll()
                        .requestMatchers("/api/v1", "/api/v1/**", "/actuator/prometheus")
                        .authenticated()
                .anyRequest().denyAll())
                .addFilterBefore(bearerFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new ArtifactRequestSizeFilter(),
                        ControlApiBearerAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 로컬 UI가 Authorization·SSE 재연결 header를 보낼 수 있는 CORS 정책을 만든다.
     *
     * <p>원격 origin은 허용하지 않으며, reverse proxy를 통한 same-origin 요청에는
     * 이 정책이 관여하지 않는다.</p>
     *
     * @return {@code /api/v1/**} 전용 loopback CORS 구성
     */
    @Bean
    public CorsConfigurationSource controlApiCorsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of(
                "http://localhost:[*]", "http://127.0.0.1:[*]"));
        configuration.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Idempotency-Key", "Last-Event-ID"));
        configuration.setExposedHeaders(List.of("Location"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", configuration);
        return source;
    }

    /**
     * 고정 인증 실패 응답기를 생성한다.
     *
     * @return token 원문을 포함하지 않는 401 응답기
     */
    @Bean
    public ControlApiAuthenticationEntryPoint controlApiAuthenticationEntryPoint() {
        return new ControlApiAuthenticationEntryPoint();
    }
}
