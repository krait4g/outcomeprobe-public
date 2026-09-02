-- Web 운영자의 Run 생성·재실행 응답이 유실되어도 같은 의도가 중복 실행되지 않게 한다.
CREATE TABLE run_request_idempotency (
    operation varchar(16) NOT NULL,
    key_hash char(64) NOT NULL,
    request_hash char(64) NOT NULL,
    run_id uuid REFERENCES scenario_run (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (operation, key_hash),
    CONSTRAINT ck_run_request_idempotency_operation
        CHECK (operation IN ('CREATE', 'RERUN')),
    CONSTRAINT ck_run_request_idempotency_key_hash
        CHECK (key_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_run_request_idempotency_request_hash
        CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_run_request_idempotency_run
    ON run_request_idempotency (run_id);
