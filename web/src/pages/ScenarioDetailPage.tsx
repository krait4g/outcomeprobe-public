import { useCallback, useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import { createIdempotencyKey, createRun, getArtifactRaw, getProfiles } from '../api/client';
import { Dialog } from '../components/Dialog';
import { BootstrapEmptyState, LoadingState, QueryErrorState } from '../components/AsyncState';

/** 변수 입력의 엄격한 key=value 해석 결과다. */
export type VariableParseResult =
  | { ok: true; variables: Record<string, string> }
  | { ok: false; message: string };

/**
 * 줄마다 하나의 key=value만 허용하고 중복·빈 key·제어 문자를 거부한다.
 * 값 안의 첫 `=` 이후 문자는 보존한다.
 */
export function parseVariableOverrides(source: string): VariableParseResult {
  const parsed: Record<string, string> = {};
  const lines = source.split(/\r?\n/u);
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index];
    if (line.trim() === '') {
      continue;
    }
    const separator = line.indexOf('=');
    if (separator <= 0) {
      return { ok: false, message: `${index + 1}행은 key=value 형식이어야 합니다.` };
    }
    const key = line.slice(0, separator).trim();
    const value = line.slice(separator + 1);
    if (!/^[A-Za-z][A-Za-z0-9_.-]{0,127}$/u.test(key)) {
      return { ok: false, message: `${index + 1}행의 변수 이름이 올바르지 않습니다.` };
    }
    if (Object.hasOwn(parsed, key)) {
      return { ok: false, message: `${index + 1}행의 변수 이름이 중복되었습니다.` };
    }
    if (Array.from(value).some((character) => {
      const codePoint = character.codePointAt(0) ?? 0;
      return (codePoint < 32 && codePoint !== 9) || codePoint === 127;
    })) {
      return { ok: false, message: `${index + 1}행의 값에 허용되지 않는 제어 문자가 있습니다.` };
    }
    parsed[key] = value;
  }
  return { ok: true, variables: parsed };
}

/** blank면 canonical YAML seed를 유지하고, 아니면 안전한 정수 override로 해석한다. */
export function parseSeedOverride(source: string): { ok: true; seed?: number } | { ok: false; message: string } {
  const value = source.trim();
  if (value === '') {
    return { ok: true };
  }
  if (!/^\d+$/u.test(value)) {
    return { ok: false, message: 'Seed override는 0 이상의 정수여야 합니다.' };
  }
  const seed = Number(value);
  if (!Number.isSafeInteger(seed)) {
    return { ok: false, message: 'Seed override가 허용 범위를 벗어났습니다.' };
  }
  return { ok: true, seed };
}

/**
 * Scenario 정의(YAML)를 보여주고 실행을 시작하는 상세 화면이다.
 *
 * <p>책임: canonical YAML seed는 기본값으로 유지하고, 준비된 Profile과 검증된
 * override만 단일 실행 요청으로 전송한다.</p>
 */
export function ScenarioDetailPage() {
  const { name = '', version = '' } = useParams();
  const navigate = useNavigate();
  const [modalOpen, setModalOpen] = useState(false);
  const [target, setTarget] = useState('');
  const [seed, setSeed] = useState('');
  const [variables, setVariables] = useState('');
  const [starting, setStarting] = useState(false);
  const [startError, setStartError] = useState('');
  const requestActive = useRef(false);
  const intentKeyRef = useRef<string | null>(null);

  const yamlQuery = useQuery({
    queryKey: ['artifact-raw', 'SCENARIO', name, version],
    queryFn: () => getArtifactRaw('SCENARIO', name, version),
  });
  const profilesQuery = useQuery({ queryKey: ['profiles'], queryFn: getProfiles });
  const profiles = profilesQuery.data ?? [];

  useEffect(() => {
    if (profiles.length === 0) {
      setTarget('');
      return;
    }
    if (!profiles.some((profile) => profile.name === target)) {
      const preferred = profiles.find((profile) => profile.name === 'resilient-consumer') ?? profiles[0];
      setTarget(preferred.name);
    }
  }, [profiles, target]);

  const closeDialog = useCallback(() => {
    if (!requestActive.current) {
      setModalOpen(false);
      setStartError('');
    }
  }, []);

  const openDialog = () => {
    intentKeyRef.current = createIdempotencyKey();
    setStartError('');
    setModalOpen(true);
  };

  /** 입력 변경은 이전 응답 유실 재시도가 아닌 새 사용자 의도이므로 새 key를 준비한다. */
  const beginChangedIntent = () => {
    intentKeyRef.current = createIdempotencyKey();
    setStartError('');
  };

  const startRun = async () => {
    if (requestActive.current) {
      return;
    }
    if (!profiles.some((profile) => profile.name === target)) {
      setStartError('실행할 Target Profile을 선택하세요.');
      return;
    }
    const parsedSeed = parseSeedOverride(seed);
    if (!parsedSeed.ok) {
      setStartError(parsedSeed.message);
      return;
    }
    const parsedVariables = parseVariableOverrides(variables);
    if (!parsedVariables.ok) {
      setStartError(parsedVariables.message);
      return;
    }

    requestActive.current = true;
    setStarting(true);
    setStartError('');
    try {
      // 응답 유실 뒤 재시도는 dialog를 닫기 전까지 같은 사용자 의도 key를 재사용한다.
      intentKeyRef.current ??= createIdempotencyKey();
      const runId = await createRun(
        name,
        version,
        target,
        parsedSeed.seed,
        parsedVariables.variables,
        { idempotencyKey: intentKeyRef.current },
      );
      navigate(`/runs/${runId}`);
    } catch {
      setStartError('Scenario 실행에 실패했습니다. 서버 연결과 bootstrap 상태를 확인하세요.');
    } finally {
      requestActive.current = false;
      setStarting(false);
    }
  };

  const profilesReady = profilesQuery.isSuccess && profiles.length > 0 && target !== '';

  return (
    <div>
      <h2>Scenario: {name}@{version}</h2>
      <div className="actions">
        <button
          type="button"
          disabled={!yamlQuery.isSuccess || !profilesReady}
          onClick={openDialog}
        >
          Run Scenario
        </button>
      </div>

      {profilesQuery.isLoading ? <LoadingState message="Target Profile을 불러오는 중…" /> : null}
      {profilesQuery.isError ? (
        <QueryErrorState
          message="Target Profile을 불러오지 못했습니다. 서버 연결을 확인하세요."
          onRetry={() => void profilesQuery.refetch()}
          testId="scenario-profiles-error"
        />
      ) : null}
      {profilesQuery.isSuccess && profiles.length === 0 ? (
        <BootstrapEmptyState message="아직 등록된 Target Profile이 없습니다" testId="scenario-profiles-empty" />
      ) : null}

      {modalOpen ? (
        <Dialog title="Run 설정" onClose={closeDialog} closeDisabled={starting}>
          <label>
            Target Profile
            <select
              value={target}
              disabled={starting}
              onChange={(event) => {
                beginChangedIntent();
                setTarget(event.target.value);
              }}
            >
              {profiles.map((profile) => (
                <option key={`${profile.name}@${profile.version}`} value={profile.name}>
                  {profile.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            Seed override (비우면 Scenario 기본값)
            <input
              inputMode="numeric"
              value={seed}
              disabled={starting}
              onChange={(event) => {
                beginChangedIntent();
                setSeed(event.target.value);
              }}
            />
          </label>
          <label>
            변수 override (줄마다 key=value)
            <textarea
              rows={4}
              value={variables}
              disabled={starting}
              placeholder="paymentId=pay-my-1"
              onChange={(event) => {
                beginChangedIntent();
                setVariables(event.target.value);
              }}
            />
          </label>
          {startError ? (
            <p className="error" role="alert" data-testid="run-start-error">{startError}</p>
          ) : null}
          <div className="actions">
            <button type="button" disabled={starting || !profilesReady} onClick={() => void startRun()}>
              {starting ? '실행 중…' : 'Run Scenario'}
            </button>
            <button type="button" className="secondary-action" disabled={starting} onClick={closeDialog}>
              취소
            </button>
          </div>
        </Dialog>
      ) : null}

      <h3>Scenario 정의 (YAML)</h3>
      {yamlQuery.isLoading ? <LoadingState message="Scenario 정의를 불러오는 중…" /> : null}
      {yamlQuery.isError ? (
        <QueryErrorState
          message="Scenario 정의를 불러오지 못했습니다. 서버 연결을 확인하세요."
          onRetry={() => void yamlQuery.refetch()}
          testId="scenario-yaml-error"
        />
      ) : null}
      {yamlQuery.isSuccess ? <pre className="yaml-view">{yamlQuery.data}</pre> : null}
    </div>
  );
}
