-- retention procedure를 감사(OP-AUDIT-RET-001) 결과에 따라 강화한다.
-- search_path를 고정하고 객체를 schema-qualified로 참조해 search_path hijack을 막는다.
-- V4 checksum을 바꾸지 않도록 CREATE OR REPLACE로 재생성한다.

CREATE OR REPLACE FUNCTION public.delete_scenario_run_cascade(target_id uuid) RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $retention_delete$
BEGIN
    -- 진행 중 Run은 삭제하지 않는다. terminal Run만 대상이다.
    IF EXISTS (SELECT 1 FROM public.scenario_run
               WHERE id = target_id
                 AND status NOT IN ('PASSED', 'FAILED', 'ERROR', 'CANCELLED')) THEN
        RAISE EXCEPTION 'cannot delete non-terminal run';
    END IF;

    ALTER TABLE public.evidence_event DISABLE TRIGGER trg_evidence_no_delete;
    -- scenario_run의 FK ON DELETE CASCADE가 관련 행을 모두 삭제한다.
    DELETE FROM public.scenario_run WHERE id = target_id;
    ALTER TABLE public.evidence_event ENABLE TRIGGER trg_evidence_no_delete;
END;
$retention_delete$;
