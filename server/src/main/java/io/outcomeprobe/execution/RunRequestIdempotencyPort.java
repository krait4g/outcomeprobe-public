package io.outcomeprobe.execution;

import java.time.Instant;

/**
 * Run 변경 요청 idempotency ledger의 application persistence port다.
 *
 * <p>책임: application service가 PostgreSQL 구현을 직접 참조하지 않고 의도 선점, 권위 행 잠금,
 * run 결속을 요청하게 한다. 원문 key나 요청 본문을 port 경계 밖으로 전달하지 않는다.</p>
 */
public interface RunRequestIdempotencyPort {

    /**
     * operation과 key hash를 최초 한 번만 선점한다.
     *
     * @param operation CREATE 또는 RERUN
     * @param keyHash 원문 key의 SHA-256
     * @param requestHash canonical 요청의 SHA-256
     * @param createdAt 최초 접수 시각
     */
    void reserve(String operation, String keyHash, String requestHash, Instant createdAt);

    /**
     * 권위 있는 ledger 행을 잠그고 읽는다.
     *
     * @param operation CREATE 또는 RERUN
     * @param keyHash 원문 key의 SHA-256
     * @return request hash와 이미 결속된 run 식별자
     */
    Entry lock(String operation, String keyHash);

    /**
     * 선점한 ledger 행을 생성된 run에 결속한다.
     *
     * @param operation CREATE 또는 RERUN
     * @param keyHash 원문 key의 SHA-256
     * @param runId 생성된 run 식별자
     */
    void bindRun(String operation, String keyHash, String runId);

    /**
     * 잠긴 ledger 행의 안전한 최소 값이다.
     *
     * @param requestHash canonical 요청 SHA-256
     * @param runId 기존 run 식별자, 아직 생성 전이면 {@code null}
     */
    record Entry(String requestHash, String runId) {
    }
}
