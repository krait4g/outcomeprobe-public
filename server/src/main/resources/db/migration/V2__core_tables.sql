-- M1 핵심 테이블: Artifact, Run, Step, Effect, Emulator, Provider, Evidence
-- 명세 17장의 컬럼 계약을 따른다. 모든 시각은 timestamptz, 동적 payload는 jsonb를 사용한다.

CREATE TABLE artifact_definition (
    id uuid PRIMARY KEY,
    kind varchar(40) NOT NULL,
    name varchar(63) NOT NULL,
    version varchar(32) NOT NULL,
    api_version varchar(40) NOT NULL,
    display_name varchar(120) NOT NULL,
    content_hash varchar(80) NOT NULL,
    raw_yaml text NOT NULL,
    parsed_json jsonb NOT NULL,
    built_in boolean NOT NULL,
    imported_at timestamptz NOT NULL,
    deleted_at timestamptz
);

-- 동일 kind+name+version의 내용이 다르면 import를 거부하기 위한 고유 제약이다.
CREATE UNIQUE INDEX uq_artifact_identity ON artifact_definition (kind, name, version);
CREATE UNIQUE INDEX uq_artifact_content_hash ON artifact_definition (content_hash);

CREATE TABLE scenario_run (
    id uuid PRIMARY KEY,
    scenario_name varchar(63) NOT NULL,
    scenario_version varchar(32) NOT NULL,
    scenario_hash varchar(80) NOT NULL,
    target_profile_name varchar(63) NOT NULL,
    seed bigint NOT NULL,
    status varchar(24) NOT NULL,
    current_step_ordinal integer,
    compiled_plan jsonb,
    next_action_at timestamptz,
    deadline_at timestamptz,
    lease_owner varchar(100),
    lease_until timestamptz,
    version bigint NOT NULL DEFAULT 0,
    next_evidence_sequence bigint NOT NULL DEFAULT 0,
    cancel_requested boolean NOT NULL DEFAULT false,
    problem_code varchar(80),
    problem_detail jsonb,
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz NOT NULL
);

-- Worker가 기한 도래한 Run을 빠르게 찾도록 상태·시각 복합 인덱스를 둔다.
CREATE INDEX ix_scenario_run_due ON scenario_run (status, next_action_at);
CREATE INDEX ix_scenario_run_lease ON scenario_run (lease_until);
CREATE INDEX ix_scenario_run_created ON scenario_run (created_at DESC);
CREATE INDEX ix_scenario_run_scenario ON scenario_run (scenario_name, scenario_version, created_at DESC);

CREATE TABLE run_step_execution (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES scenario_run (id) ON DELETE CASCADE,
    step_id varchar(80) NOT NULL,
    ordinal integer NOT NULL,
    step_type varchar(40) NOT NULL,
    status varchar(24) NOT NULL,
    input_snapshot jsonb,
    output_snapshot jsonb,
    wait_started_at timestamptz,
    next_action_at timestamptz,
    deadline_at timestamptz,
    attempt_count integer NOT NULL DEFAULT 0,
    last_observation jsonb,
    problem_code varchar(80),
    version bigint NOT NULL DEFAULT 0,
    started_at timestamptz,
    finished_at timestamptz
);

CREATE UNIQUE INDEX uq_run_step_identity ON run_step_execution (run_id, step_id);
CREATE UNIQUE INDEX uq_run_step_ordinal ON run_step_execution (run_id, ordinal);

CREATE TABLE run_effect (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES scenario_run (id) ON DELETE CASCADE,
    step_id varchar(80) NOT NULL,
    effect_type varchar(40) NOT NULL,
    effect_ordinal integer NOT NULL,
    status varchar(24) NOT NULL,
    request_hash varchar(80),
    idempotency_key_hash varchar(80),
    planned_at timestamptz,
    started_at timestamptz,
    completed_at timestamptz,
    result_snapshot jsonb
);

-- 동일 effect key의 중복 side effect를 DB 수준에서 차단한다.
CREATE UNIQUE INDEX uq_run_effect_key ON run_effect (run_id, step_id, effect_type, effect_ordinal);

CREATE TABLE emulator_alias_lock (
    alias varchar(63) PRIMARY KEY,
    run_id uuid NOT NULL,
    -- Emulator가 Run 조회 없이 계약을 사용할 수 있도록 활성 Run의 컨텍스트 snapshot을 둔다.
    context_json jsonb,
    acquired_at timestamptz NOT NULL,
    released_at timestamptz,
    version bigint NOT NULL DEFAULT 0
);

-- 활성 alias는 Run 하나만 점유할 수 있도록 부분 unique index로 보호한다.
CREATE UNIQUE INDEX uq_emulator_alias_active ON emulator_alias_lock (alias) WHERE released_at IS NULL;

CREATE TABLE provider_resource (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES scenario_run (id) ON DELETE CASCADE,
    resource_type varchar(80) NOT NULL,
    resource_id varchar(160) NOT NULL,
    canonical_state varchar(80) NOT NULL,
    visible_state varchar(80) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    remaining_stale_query_attempts integer NOT NULL DEFAULT 0,
    visible_after timestamptz,
    attributes jsonb NOT NULL DEFAULT '{}'::jsonb,
    updated_at timestamptz NOT NULL
);

CREATE UNIQUE INDEX uq_provider_resource_identity ON provider_resource (run_id, resource_type, resource_id);

CREATE TABLE expected_invocation (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES scenario_run (id) ON DELETE CASCADE,
    step_id varchar(80) NOT NULL,
    operation_key varchar(80) NOT NULL,
    matcher jsonb NOT NULL DEFAULT '{}'::jsonb,
    behavior jsonb NOT NULL DEFAULT '{}'::jsonb,
    minimum_count integer NOT NULL DEFAULT 1,
    maximum_count integer NOT NULL DEFAULT 1,
    matched_count integer NOT NULL DEFAULT 0,
    status varchar(24) NOT NULL,
    expires_at timestamptz,
    version bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_expected_invocation_lookup ON expected_invocation (run_id, operation_key, status);

CREATE TABLE provider_invocation_receipt (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES scenario_run (id) ON DELETE CASCADE,
    operation_key varchar(80) NOT NULL,
    idempotency_key_hash varchar(80) NOT NULL,
    request_hash varchar(80) NOT NULL,
    resource_type varchar(80),
    resource_id varchar(160),
    result_snapshot jsonb NOT NULL,
    first_committed_at timestamptz NOT NULL
);

-- 같은 idempotency key 재호출은 최초 결과를 재사용하기 위한 고유 제약이다.
CREATE UNIQUE INDEX uq_provider_receipt_key ON provider_invocation_receipt (run_id, operation_key, idempotency_key_hash);

CREATE TABLE evidence_event (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES scenario_run (id) ON DELETE CASCADE,
    sequence bigint NOT NULL,
    logical_time_ms bigint,
    observed_at timestamptz NOT NULL,
    type varchar(100) NOT NULL,
    source varchar(40) NOT NULL,
    step_id varchar(80),
    correlation_id varchar(160),
    resource_type varchar(80),
    resource_id varchar(160),
    attributes jsonb NOT NULL DEFAULT '{}'::jsonb,
    payload_summary jsonb,
    body_hash varchar(80),
    redaction_applied boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL
);

-- Run 안에서 evidence 순서를 단조 증가로 보장한다.
CREATE UNIQUE INDEX uq_evidence_sequence ON evidence_event (run_id, sequence);
CREATE INDEX ix_evidence_type ON evidence_event (run_id, type);
CREATE INDEX ix_evidence_resource ON evidence_event (run_id, resource_type, resource_id);

-- Evidence는 append-only다. application 권한과 무관하게 UPDATE·DELETE를 DB trigger로 차단한다.
CREATE OR REPLACE FUNCTION prevent_evidence_mutation() RETURNS trigger AS $evidence_append_only$
BEGIN
    RAISE EXCEPTION 'evidence_event is append-only';
END;
$evidence_append_only$ LANGUAGE plpgsql;

CREATE TRIGGER trg_evidence_no_update
    BEFORE UPDATE ON evidence_event
    FOR EACH ROW EXECUTE FUNCTION prevent_evidence_mutation();

CREATE TRIGGER trg_evidence_no_delete
    BEFORE DELETE ON evidence_event
    FOR EACH ROW EXECUTE FUNCTION prevent_evidence_mutation();
