package io.outcomeprobe.emulator.adapter.in.web;

import io.outcomeprobe.emulator.EmulationOutcome;
import io.outcomeprobe.emulator.EmulatorService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Target Application의 Provider API 호출을 받아들이는 catch-all ingress다.
 *
 * <p>책임: {@code /emulator/{alias}/**} 경로의 모든 method·path를 받아
 * EmulatorService에 위임하고, 의도적 응답 지연을 HTTP 쓰기 직전에 적용한다.</p>
 * <p>핵심 불변식: 응답 지연은 Provider commit 트랜잭션 완료 이후에만 발생한다.
 * 따라서 지연 중 Target이 연결을 끊어도 Provider truth는 이미 확정 상태다.</p>
 */
@RestController
public class EmulatorController {

    private static final String EMULATOR_PREFIX = "/emulator/";
    private static final String TOKEN_HEADER = "X-OutcomeProbe-Emulator-Token";

    private final EmulatorService emulatorService;
    private final String emulatorToken;

    /**
     * ingress 처리 서비스를 주입한다.
     *
     * @param emulatorService Emulator orchestration 서비스
     * @param emulatorToken 선택적 ingress token, 설정되면 header 검증을 강제한다
     */
    public EmulatorController(EmulatorService emulatorService,
                              @org.springframework.beans.factory.annotation.Value(
                                      "${outcomeprobe.emulator.token:}") String emulatorToken) {
        this.emulatorService = emulatorService;
        this.emulatorToken = emulatorToken;
    }

    /**
     * alias 하위의 모든 요청을 처리한다.
     *
     * <p>왜 지연을 여기서 소비하는가: TIMEOUT_AFTER_COMMIT은 Target client가
     * 먼저 timeout을 경험해야 하므로, 서비스 확정 응답을 지연 뒤에 쓴다.
     * client가 이미 끊었다면 쓰기 실패는 Target timeout 재현의 자연스러운 결과다.</p>
     *
     * @param alias Emulator alias
     * @param request 원본 서블릿 요청
     * @return Provider 응답
     * @throws IOException 요청 body 읽기 실패 시 발생한다
     */
    @RequestMapping("/emulator/{alias}/**")
    public ResponseEntity<byte[]> handle(@PathVariable String alias, HttpServletRequest request)
            throws IOException {
        // token이 설정된 환경에서는 일치하는 token 없이는 ingress를 거부한다.
        if (!emulatorToken.isBlank()) {
            String provided = request.getHeader(TOKEN_HEADER);
            if (!emulatorToken.equals(provided)) {
                return ResponseEntity.status(401).build();
            }
        }

        String fullPath = request.getRequestURI();
        int aliasEnd = fullPath.indexOf('/', EMULATOR_PREFIX.length());
        String path = aliasEnd < 0 ? "/" : fullPath.substring(aliasEnd);

        Map<String, String> headers = new LinkedHashMap<>();
        request.getHeaderNames().asIterator()
                .forEachRemaining(name -> headers.put(name, request.getHeader(name)));

        byte[] body = request.getInputStream().readAllBytes();

        EmulationOutcome outcome = emulatorService.handle(
                alias, request.getMethod(), path, headers, body);

        if (outcome.hasResponseDelay()) {
            // commit 확정 후 응답만 지연해 모호한 결과를 재현한다. 가상 스레드이므로 차단은 안전하다.
            try {
                Thread.sleep(outcome.responseDelay().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(outcome.status());
        outcome.headers().forEach(builder::header);
        return builder.body(outcome.body());
    }
}
