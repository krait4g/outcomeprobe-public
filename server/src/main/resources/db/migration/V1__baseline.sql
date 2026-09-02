-- OutcomeProbe 스키마 baseline migration
-- 이 파일은 Flyway 적용 경로와 PostgreSQL 연결만 검증한다.
-- 업무 테이블은 마일스톤별 후속 migration에서 추가한다.

CREATE TABLE IF NOT EXISTS schema_baseline (
    id integer PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO schema_baseline (id)
VALUES (1)
ON CONFLICT (id) DO NOTHING;
