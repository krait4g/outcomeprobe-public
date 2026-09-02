-- M2 Callback Delivery와 Probe attempt 테이블
-- 명세 17.10~17.12의 컬럼 계약을 따른다.

CREATE TABLE callback_event (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES scenario_run (id) ON DELETE CASCADE,
    callback_key varchar(80) NOT NULL,
    event_id varchar(160) NOT NULL,
    event_type varchar(120) NOT NULL,
    resource_type varchar(80),
    resource_id varchar(160),
    resource_version bigint,
    payload jsonb NOT NULL,
    payload_hash varchar(80) NOT NULL,
    created_at timestamptz NOT NULL
);

-- 동일 Run에서 같은 eventId는 하나의 업무 사건만 나타낸다.
CREATE UNIQUE INDEX uq_callback_event_identity ON callback_event (run_id, event_id);

CREATE TABLE callback_delivery (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES scenario_run (id) ON DELETE CASCADE,
    callback_event_id uuid NOT NULL REFERENCES callback_event (id) ON DELETE CASCADE,
    occurrence integer NOT NULL,
    status varchar(24) NOT NULL,
    scheduled_at timestamptz NOT NULL,
    next_attempt_at timestamptz NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    maximum_attempts integer NOT NULL DEFAULT 1,
    lease_owner varchar(100),
    lease_until timestamptz,
    request_snapshot jsonb,
    response_snapshot jsonb,
    last_failure_code varchar(80),
    version bigint NOT NULL DEFAULT 0,
    completed_at timestamptz
);

-- 같은 사건의 중복 전달 occurrence는 고유하다.
CREATE UNIQUE INDEX uq_callback_delivery_occurrence ON callback_delivery (callback_event_id, occurrence);
CREATE INDEX ix_callback_delivery_due ON callback_delivery (status, next_attempt_at);
CREATE INDEX ix_callback_delivery_lease ON callback_delivery (lease_until);
CREATE INDEX ix_callback_delivery_run ON callback_delivery (run_id, status);

CREATE TABLE probe_attempt (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES scenario_run (id) ON DELETE CASCADE,
    step_id varchar(80) NOT NULL,
    attempt_number integer NOT NULL,
    requested_at timestamptz NOT NULL,
    responded_at timestamptz,
    status_code integer,
    response_hash varchar(80),
    response_body jsonb,
    assertion_results jsonb,
    decision varchar(24),
    failure_code varchar(80)
);

CREATE UNIQUE INDEX uq_probe_attempt_identity ON probe_attempt (run_id, step_id, attempt_number);
