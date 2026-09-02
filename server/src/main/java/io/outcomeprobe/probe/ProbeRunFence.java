package io.outcomeprobe.probe;

import io.outcomeprobe.foundation.PostgresqlTimestamp;
import java.time.Instant;
import java.util.Objects;

/**
 * Probe 상태 변경이 소유한 부모 Run lease와 시간 경계를 고정한 잠금 토큰이다.
 *
 * <p>책임: Probe transaction이 {@code scenario_run}을 먼저 잠글 때 읽었던 Run과 현재
 * Worker가 같은 lease를 소유하는지 검증할 값을 전달한다. version뿐 아니라 lease owner와
 * lease 만료 시각도 함께 비교해 같은 Worker가 lease를 다시 얻은 경우까지 구분한다.</p>
 *
 * @param runId 부모 Run 식별자
 * @param expectedRunVersion lease 시점 Run version
 * @param expectedLeaseOwner lease 소유 Worker
 * @param expectedLeaseUntil lease 시점 만료 시각
 * @param fencedAt transaction 진입 직전 검증 시각
 * @param boundaryAt 경계 판정의 권위 시각. COMPLETE는 실제 응답 시각이고 나머지는 fencedAt이다.
 * @param boundary Probe 상태 변경 경계
 */
public record ProbeRunFence(
        String runId,
        long expectedRunVersion,
        String expectedLeaseOwner,
        Instant expectedLeaseUntil,
        Instant fencedAt,
        Instant boundaryAt,
        Boundary boundary) {

    /** Probe 상태 변경별 부모 Run 허용 조건이다. */
    public enum Boundary {
        /** 외부 GET을 시작하기 전 active·cancel·deadline을 모두 검증한다. */
        BEGIN,
        /** 이미 관측한 GET 결과를 확정하기 전 active·cancel·deadline을 검증한다. */
        COMPLETE,
        /** active Run 안에서 결과 미확정 attempt를 중단한다. */
        ACTIVE_INTERRUPT,
        /** 취소 요청 Run을 terminal로 만들기 전에 attempt를 중단한다. */
        CANCEL_INTERRUPT,
        /** 전체 deadline Run을 terminal로 만들기 전에 attempt를 중단한다. */
        DEADLINE_INTERRUPT
    }

    /** 필수 식별자와 lease가 없는 fence 생성을 거부하고 DB 정밀도로 시각을 맞춘다. */
    public ProbeRunFence {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("Probe Run fence에는 runId가 필요합니다.");
        }
        if (expectedRunVersion < 0) {
            throw new IllegalArgumentException("Probe Run fence version은 음수일 수 없습니다.");
        }
        if (expectedLeaseOwner == null || expectedLeaseOwner.isBlank()) {
            throw new IllegalArgumentException("Probe Run fence에는 lease owner가 필요합니다.");
        }
        expectedLeaseUntil = PostgresqlTimestamp.normalize(
                Objects.requireNonNull(expectedLeaseUntil, "expectedLeaseUntil"));
        fencedAt = PostgresqlTimestamp.normalize(Objects.requireNonNull(fencedAt, "fencedAt"));
        boundaryAt = PostgresqlTimestamp.normalize(Objects.requireNonNull(boundaryAt, "boundaryAt"));
        boundary = Objects.requireNonNull(boundary, "boundary");
        if (boundary != Boundary.COMPLETE && !boundaryAt.equals(fencedAt)) {
            throw new IllegalArgumentException(
                    "COMPLETE가 아닌 Probe Run fence는 boundaryAt과 fencedAt이 같아야 합니다.");
        }
    }

}
