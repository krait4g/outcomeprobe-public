package io.outcomeprobe.report;

import io.outcomeprobe.evidence.EvidenceEvent;
import io.outcomeprobe.evidence.EvidenceQueryPort;
import io.outcomeprobe.execution.RunStepExecution;
import io.outcomeprobe.execution.RunStepPort;
import io.outcomeprobe.execution.ScenarioRun;
import io.outcomeprobe.execution.ScenarioRunPort;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bundle용 Run·Step·Evidence를 하나의 PostgreSQL 읽기 snapshot에서 조회한다.
 *
 * <p>책임: 기존 외부 트랜잭션의 격리 수준에 영향을 받지 않는 새 REPEATABLE_READ
 * 읽기 전용 트랜잭션을 열고, 각 저장소를 정확히 한 번씩 조회한다.</p>
 * <p>핵심 불변식: report renderer는 이 객체가 반환한 snapshot 이후 live 포트를
 * 다시 조회하지 않는다. 조회 결과가 null이거나 Run 결속·정렬 계약이 깨졌으면
 * {@link RunBundleSnapshot} 생성 단계에서 fail-closed 처리한다.</p>
 */
@Service
public class RunBundleSnapshotReader {

    private final ScenarioRunPort scenarioRunPort;
    private final RunStepPort runStepPort;
    private final EvidenceQueryPort evidenceQueryPort;

    /**
     * 같은 Spring 트랜잭션에 참여할 세 조회 포트를 주입한다.
     *
     * @param scenarioRunPort Run 조회 포트
     * @param runStepPort Step 조회 포트
     * @param evidenceQueryPort Evidence 조회 포트
     */
    public RunBundleSnapshotReader(ScenarioRunPort scenarioRunPort, RunStepPort runStepPort,
                                   EvidenceQueryPort evidenceQueryPort) {
        this.scenarioRunPort = scenarioRunPort;
        this.runStepPort = runStepPort;
        this.evidenceQueryPort = evidenceQueryPort;
    }

    /**
     * Run과 그 Run에 속한 Step·Evidence를 한 번의 일관된 읽기 snapshot으로 고정한다.
     *
     * <p>REQUIRES_NEW를 사용하는 이유는 호출자 트랜잭션이 READ_COMMITTED여도 Bundle
     * 내부 파일들이 서로 다른 commit을 관측하지 않게 하기 위해서다. Run이 없을 때는
     * 불필요한 두 조회를 실행하지 않고 empty를 반환한다.</p>
     *
     * @param runId Bundle을 생성할 Run 식별자
     * @return 일관된 Bundle snapshot, Run이 없으면 empty
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ,
            propagation = Propagation.REQUIRES_NEW)
    public Optional<RunBundleSnapshot> read(String runId) {
        Optional<ScenarioRun> run = scenarioRunPort.findById(runId);
        if (run.isEmpty()) {
            return Optional.empty();
        }
        List<RunStepExecution> steps = runStepPort.findByRunId(runId);
        List<EvidenceEvent> timeline = evidenceQueryPort.timeline(runId);
        return Optional.of(new RunBundleSnapshot(run.get(), steps, timeline));
    }
}
