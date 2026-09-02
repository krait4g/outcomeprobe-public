package org.outcomeprobe.referenceconsumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * OutcomeProbe 데모용 Reference Consumer의 진입점이다.
 *
 * <p>책임: 같은 코드베이스를 vulnerable·resilient 두 profile로 실행해, 동일한 외부 장애에서
 * 최종 업무 상태가 어떻게 달라지는지 보여 준다. 제품 서버 코드가 아니며 OutcomeProbe
 * core 모듈과 의존 관계를 공유하지 않는다.</p>
 * <p>외부에서 오해하기 쉬운 한계: 이 애플리케이션은 production 결제·예약 모범 구현 전체가
 * 아니라 장애 재현용 데모 대상이다. test-support endpoint는 demo profile에서만 활성화된다.</p>
 */
@SpringBootApplication
public class ReferenceConsumerApplication {

    /**
     * 명령행 인수를 받아 Reference Consumer 프로세스를 시작한다.
     *
     * @param args JVM이 전달하는 명령행 인수
     */
    public static void main(String[] args) {
        SpringApplication.run(ReferenceConsumerApplication.class, args);
    }
}
