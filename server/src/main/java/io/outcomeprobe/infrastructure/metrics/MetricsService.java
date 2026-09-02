package io.outcomeprobe.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Service;

/**
 * OutcomeProbe 운영 메트릭을 기록한다.
 *
 * <p>책임: Run·Provider·Callback·Probe 지표를 기록해 "도구가 살아있는지"와
 * 시나리오 흐름의 건강성을 관측하게 한다. Scenario가 의도한 실패와 도구 자체
 * 장애를 분리해 볼 수 있게 한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: 시나리오 이름 label의 cardinality를 제한한다.
 * 사용자 입력 전체를 label로 쓰지 않는다.</p>
 */
@Service
public class MetricsService {

    private final MeterRegistry registry;

    /**
     * 메트릭 등록기를 주입한다.
     *
     * @param registry Micrometer 등록기
     */
    public MetricsService(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Run 시작을 기록한다.
     *
     * @param scenario 시나리오 이름
     */
    public void runStarted(String scenario) {
        Counter.builder("outcomeprobe_run_started_total")
                .tag("scenario", scenario)
                .register(registry)
                .increment();
    }

    /**
     * Run 종료와 소요 시간을 기록한다.
     *
     * @param scenario 시나리오 이름
     * @param status 종료 상태
     * @param duration 소요 시간
     */
    public void runCompleted(String scenario, String status, Duration duration) {
        Counter.builder("outcomeprobe_run_completed_total")
                .tag("scenario", scenario)
                .tag("status", status)
                .register(registry)
                .increment();
        Timer.builder("outcomeprobe_run_duration_seconds")
                .tag("scenario", scenario)
                .tag("status", status)
                .register(registry)
                .record(duration);
    }

    /**
     * Provider commit을 기록한다.
     *
     * @param operation operation 이름
     * @param state commit된 상태
     */
    public void providerCommit(String operation, String state) {
        Counter.builder("outcomeprobe_provider_commit_total")
                .tag("operation", operation)
                .tag("state", state)
                .register(registry)
                .increment();
    }

    /**
     * Provider 응답 fault를 기록한다.
     *
     * @param faultType fault 유형
     */
    public void providerFault(String faultType) {
        Counter.builder("outcomeprobe_provider_fault_total")
                .tag("faultType", faultType)
                .register(registry)
                .increment();
    }

    /**
     * Callback 전달 결과를 기록한다.
     *
     * @param callback callback 이름
     * @param result 전달 결과
     */
    public void callbackDelivery(String callback, String result) {
        Counter.builder("outcomeprobe_callback_delivery_total")
                .tag("callback", callback)
                .tag("result", result)
                .register(registry)
                .increment();
    }

    /**
     * Probe 시도를 기록한다.
     *
     * @param result probe 결과
     */
    public void probeAttempt(String result) {
        Counter.builder("outcomeprobe_probe_attempt_total")
                .tag("result", result)
                .register(registry)
                .increment();
    }
}
