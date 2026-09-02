-- Evidence는 append-only trigger로 보호된다.
-- retention 삭제만 SECURITY DEFINER procedure로 trigger를 일시 해제해 수행한다.
-- application 사용자는 이 procedure 밖에서 evidence_event를 UPDATE·DELETE할 수 없다.

CREATE OR REPLACE FUNCTION delete_scenario_run_cascade(target_id uuid) RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
AS $retention_delete$
BEGIN
    ALTER TABLE evidence_event DISABLE TRIGGER trg_evidence_no_delete;
    -- scenario_run의 FK ON DELETE CASCADE가 관련 행을 모두 삭제한다.
    DELETE FROM scenario_run WHERE id = target_id;
    ALTER TABLE evidence_event ENABLE TRIGGER trg_evidence_no_delete;
END;
$retention_delete$;
