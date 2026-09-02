package io.outcomeprobe.execution.adapter.in.web;

import io.outcomeprobe.execution.StatsService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Overview 대시보드 통계 API다.
 *
 * <p>책임: Overview가 제품 건강성을 한눈에 보여주도록 상태별 Run 수·
 * 최다 실패 Scenario·정체 Run을 제공한다.</p>
 */
@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {

    private final StatsService statsService;

    /**
     * 통계 서비스를 주입한다.
     *
     * @param statsService 통계 집계 서비스
     */
    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /**
     * Overview 통계를 조회한다.
     *
     * @return 상태별 Run 수·최다 실패 Scenario·정체 Run 수
     */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return statsService.overview();
    }
}
