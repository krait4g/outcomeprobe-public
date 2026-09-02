package io.outcomeprobe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * OutcomeProbe 서버의 진입점이다.
 *
 * <p>책임: Spring Boot 컨텍스트를 시작하고 Modular Monolith 모듈 경계의 루트 패키지를 제공한다.</p>
 * <p>핵심 불변식: 이 클래스는 어떤 업무 규칙도 직접 구현하지 않으며, 모듈 패키지의 기준점으로만 동작한다.
 * 모든 업무 로직은 {@code scenario}, {@code execution}, {@code emulator} 같은 모듈 패키지에 거주한다.</p>
 */
@SpringBootApplication
public class OutcomeProbeServerApplication {

    /**
     * 명령행 인수를 받아 서버 프로세스를 시작한다.
     *
     * @param args JVM이 전달하는 명령행 인수
     */
    public static void main(String[] args) {
        SpringApplication.run(OutcomeProbeServerApplication.class, args);
    }
}
