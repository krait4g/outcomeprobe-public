package io.outcomeprobe.infrastructure.retention;

import io.outcomeprobe.foundation.ClockProvider;
import java.sql.Timestamp;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 보존 기간이 지난 terminal Run을 삭제하는 retention 작업이다.
 *
 * <p>책임: 기본 14일이 지난 terminal Run을 SECURITY DEFINER procedure로 삭제한다.
 * Evidence append-only 보호는 유지되며, 삭제는 Run 단위로만 일어난다.</p>
 * <p>핵심 불변식: application 코드는 evidence_event를 직접 삭제하지 않는다.
 * 오직 DB procedure만 trigger를 일시 해제해 삭제한다.</p>
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final JdbcClient jdbcClient;
    private final ClockProvider clockProvider;
    private final int retentionDays;

    /**
     * retention 설정을 주입한다.
     *
     * @param jdbcClient SQL 실행 클라이언트
     * @param clockProvider 기준 시각 제공자
     * @param retentionDays terminal Run 보존 일수
     */
    public RetentionService(JdbcClient jdbcClient, ClockProvider clockProvider,
                            @Value("${outcomeprobe.retention.days:14}") int retentionDays) {
        this.jdbcClient = jdbcClient;
        this.clockProvider = clockProvider;
        this.retentionDays = retentionDays;
    }

    /**
     * 보존 기간이 지난 terminal Run을 삭제한다.
     *
     * <p>한 번에 100건씩 삭제해 장시간 행 잠금을 피한다.</p>
     */
    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 60_000L)
    public void purgeExpiredRuns() {
        Timestamp cutoff = Timestamp.from(clockProvider.now().minusSeconds(retentionDays * 86_400L));
        List<String> expired = jdbcClient.sql("""
                        SELECT id
                        FROM scenario_run
                        WHERE status IN ('PASSED', 'FAILED', 'ERROR', 'CANCELLED')
                          AND finished_at IS NOT NULL
                          AND finished_at < :cutoff
                        LIMIT 100
                        """)
                .param("cutoff", cutoff)
                .query(String.class)
                .list();
        for (String runId : expired) {
            // SECURITY DEFINER procedure가 append-only trigger를 우회해 Run 전체를 삭제한다.
            jdbcClient.sql("SELECT delete_scenario_run_cascade(:runId)")
                    .param("runId", runId)
                    .query((java.sql.ResultSet rs) -> {
                        rs.next();
                        return Boolean.TRUE;
                    });
        }
        if (!expired.isEmpty()) {
            log.info("retention 삭제 완료: {}건 (cutoff {}일)", expired.size(), retentionDays);
        }
    }
}
