package io.outcomeprobe.execution;

import io.outcomeprobe.execution.adapter.out.persistence.JdbcScenarioRunRepository;
import io.outcomeprobe.foundation.ClockProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Overview 대시보드 통계를 집계한다.
 *
 * <p>책임: 최근 Run 현황·최다 실패 Scenario·정체 Run을 집계해 Overview가
 * 제품 건강성을 한눈에 보여준다.</p>
 */
@Service
public class StatsService {

    private final JdbcScenarioRunRepository runRepository;
    private final ClockProvider clockProvider;

    /**
     * 통계 집계에 필요한 협업 객체를 주입한다.
     *
     * @param runRepository Run 저장소
     * @param clockProvider 기준 시각 제공자
     */
    public StatsService(JdbcScenarioRunRepository runRepository, ClockProvider clockProvider) {
        this.runRepository = runRepository;
        this.clockProvider = clockProvider;
    }

    /**
     * Overview 통계를 집계해 반환한다.
     *
     * @return 상태별 Run 수·최다 실패 Scenario·정체 Run 수
     */
    public Map<String, Object> overview() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("passed24h", runRepository.countByStatusSince("PASSED", hoursAgo(24)));
        stats.put("failed24h", runRepository.countByStatusSince("FAILED", hoursAgo(24)));
        stats.put("error24h", runRepository.countByStatusSince("ERROR", hoursAgo(24)));
        stats.put("activeRuns", runRepository.countActive());
        stats.put("staleRuns", runRepository.countStaleRuns(hoursAgo(1)));
        stats.put("mostFailedScenario", runRepository.mostFailedScenario().orElse(null));
        return stats;
    }

    /**
     * 현재 시각에서 지정한 시간 수만큼 이전 시각을 계산한다.
     *
     * @param hours 돌아갈 시간 수
     * @return 계산된 시각
     */
    private java.time.Instant hoursAgo(int hours) {
        return clockProvider.now().minusSeconds(hours * 3600L);
    }
}
