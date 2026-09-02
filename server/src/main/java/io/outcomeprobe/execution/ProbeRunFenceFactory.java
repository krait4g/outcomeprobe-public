package io.outcomeprobe.execution;

import io.outcomeprobe.probe.ProbeRunFence;
import java.time.Instant;
import java.util.Objects;

/** 실행 모듈의 lease Run snapshot을 Probe 모듈의 불변 fence 값으로 변환한다. */
public final class ProbeRunFenceFactory {

    /** 값 변환 전용 factory의 인스턴스 생성을 막는다. */
    private ProbeRunFenceFactory() {
    }

    /**
     * transaction 진입 시각 자체가 판정 시각인 fence를 만든다.
     *
     * @param run Worker가 lease한 Run snapshot
     * @param fencedAt transaction 진입 직전 시각
     * @param boundary 상태 변경 경계
     * @return 부모 선잠금에 사용할 fence
     */
    public static ProbeRunFence at(
            ScenarioRun run, Instant fencedAt, ProbeRunFence.Boundary boundary) {
        Objects.requireNonNull(run, "run");
        return new ProbeRunFence(run.id(), run.version(), run.leaseOwner(), run.leaseUntil(),
                fencedAt, fencedAt, boundary);
    }

    /**
     * HTTP 응답 시각과 DB transaction 진입 시각을 분리한 완료 fence를 만든다.
     *
     * <p>응답이 deadline 안에 도착했다면 결과 직렬화·스케줄링 지연만으로 판정을 잃지 않는다.
     * lease 소유권은 여전히 더 늦은 transaction 진입 시각으로 검증한다.</p>
     *
     * @param run Worker가 lease한 Run snapshot
     * @param fencedAt transaction 진입 직전 시각
     * @param respondedAt Target 응답을 실제로 관측한 시각
     * @return 완료 경계용 fence
     */
    public static ProbeRunFence completion(
            ScenarioRun run, Instant fencedAt, Instant respondedAt) {
        Objects.requireNonNull(run, "run");
        return new ProbeRunFence(run.id(), run.version(), run.leaseOwner(), run.leaseUntil(),
                fencedAt, respondedAt, ProbeRunFence.Boundary.COMPLETE);
    }
}
