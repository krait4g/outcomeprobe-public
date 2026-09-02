import { useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import {
  apiFetch,
  createIdempotencyKey,
  createRun,
  getProfiles,
  getScenarios,
  getStats,
  type RunSummary,
} from '../api/client';
import { BootstrapEmptyState, LoadingState, QueryErrorState } from '../components/AsyncState';
import { StatusBadge } from '../components/StatusBadge';

/**
 * 최근 Run 현황과 Quick Start 액션을 보여주는 첫 화면이다.
 *
 * <p>책임: 방문자가 대표 실패를 즉시 이해하되, bootstrap 전 빈 상태나 서버 장애를
 * 실제 데이터 0건과 혼동하지 않고 안전하게 다음 행동을 선택하게 한다.</p>
 */
export function OverviewPage() {
  const navigate = useNavigate();
  const demoRequestActive = useRef(false);
  const demoIntentKey = useRef<string | null>(null);
  const demoIntentTarget = useRef<string | null>(null);
  const [demoPending, setDemoPending] = useState(false);
  const [demoError, setDemoError] = useState<string | null>(null);
  const runsQuery = useQuery({
    queryKey: ['runs-recent'],
    queryFn: () => apiFetch<RunSummary[]>('/runs?limit=20'),
    refetchInterval: 3000,
  });
  const statsQuery = useQuery({
    queryKey: ['stats'],
    queryFn: getStats,
    refetchInterval: 5000,
  });
  const scenariosQuery = useQuery({ queryKey: ['scenarios'], queryFn: getScenarios });
  const profilesQuery = useQuery({ queryKey: ['profiles'], queryFn: getProfiles });

  // refetch 실패 뒤 남은 cache를 현재 상태처럼 보이지 않게 조회 오류 시 숨긴다.
  const runs = runsQuery.isError ? [] : (runsQuery.data ?? []);
  const stats = statsQuery.isError ? undefined : statsQuery.data;
  const inventoryLoading = scenariosQuery.isLoading || profilesQuery.isLoading;
  const inventoryError = scenariosQuery.isError || profilesQuery.isError;
  const hasQuickStartScenario = (scenariosQuery.data ?? []).some(
    (scenario) => scenario.name === 'payment-timeout-after-commit' && scenario.version === '1.0.0',
  );
  const profileNames = new Set((profilesQuery.data ?? []).map((profile) => profile.name));
  const inventoryReady = !inventoryLoading
    && !inventoryError
    && hasQuickStartScenario
    && profileNames.has('vulnerable-consumer')
    && profileNames.has('resilient-consumer');
  const demoReady = inventoryReady && runsQuery.isSuccess && statsQuery.isSuccess;

  const runDemo = async (target: string, scenario: string, paymentId: string) => {
    if (demoRequestActive.current || !demoReady) {
      return;
    }
    demoRequestActive.current = true;
    setDemoPending(true);
    setDemoError(null);
    try {
      // 각 버튼 클릭은 새 의도이며 응답 유실 뒤 명시 재시도만 같은 key를 유지한다.
      demoIntentKey.current ??= createIdempotencyKey();
      const runId = await createRun(
        scenario,
        '1.0.0',
        target,
        42001,
        { paymentId },
        { idempotencyKey: demoIntentKey.current },
      );
      navigate(`/runs/${runId}`);
    } catch {
      // 서버 응답 원문은 인증 정보나 내부 진단을 포함할 수 있어 고정 안내만 표시한다.
      setDemoError('Quick Start 실행에 실패했습니다. Docker bootstrap을 완료하고 다시 시도하세요.');
    } finally {
      demoRequestActive.current = false;
      setDemoPending(false);
    }
  };

  return (
    <div>
      <h2>Overview</h2>
      <p className="tagline">
        외부 연동을 의도적으로 망가뜨린 뒤, 대상 애플리케이션의 최종 업무 상태가
        올바르게 수렴하는지 증거로 확인합니다.
      </p>
      <section className="overview-counts" aria-label="24시간 Run 현황">
        <div><strong>{stats?.passed24h ?? '–'}</strong> PASSED (24h)</div>
        <div><strong>{stats?.failed24h ?? '–'}</strong> FAILED (24h)</div>
        <div><strong>{stats?.error24h ?? '–'}</strong> ERROR (24h)</div>
        <div><strong>{stats?.activeRuns ?? '–'}</strong> 실행 중</div>
        <div><strong>{stats?.staleRuns ?? '–'}</strong> 정체 Run</div>
      </section>
      {statsQuery.isLoading ? <LoadingState message="24시간 현황을 불러오는 중…" /> : null}
      {statsQuery.isError ? (
        <QueryErrorState
          message="24시간 현황을 불러오지 못했습니다. 서버 연결을 확인하세요."
          onRetry={() => void statsQuery.refetch()}
          testId="overview-stats-error"
        />
      ) : null}
      {stats?.mostFailedScenario ? (
        <p className="tagline">최근 최다 실패 Scenario: {stats.mostFailedScenario}</p>
      ) : null}

      <section>
        <h3>Quick Start</h3>
        {inventoryLoading ? <LoadingState message="Quick Start 준비 상태를 확인하는 중…" /> : null}
        {inventoryError ? (
          <QueryErrorState
            message="Quick Start 준비 상태를 확인하지 못했습니다. 서버 연결을 확인하세요."
            onRetry={() => {
              void scenariosQuery.refetch();
              void profilesQuery.refetch();
            }}
            testId="overview-server-error"
          />
        ) : null}
        {!inventoryLoading && !inventoryError && !inventoryReady ? (
          <BootstrapEmptyState
            message="Quick Start Artifact가 아직 준비되지 않았습니다"
            testId="overview-bootstrap-empty"
          />
        ) : null}
        <div className="actions">
          <button
            type="button"
            disabled={demoPending || !demoReady}
            onClick={() => {
              if (!demoError || demoIntentTarget.current !== 'vulnerable-consumer') {
                demoIntentKey.current = createIdempotencyKey();
              }
              demoIntentTarget.current = 'vulnerable-consumer';
              void runDemo('vulnerable-consumer', 'payment-timeout-after-commit', 'pay-demo-vuln');
            }}
          >
            {demoPending ? '실행 중…' : '취약 Consumer로 timeout-after-commit 실행 (FAIL 예상)'}
          </button>
          <button
            type="button"
            disabled={demoPending || !demoReady}
            onClick={() => {
              if (!demoError || demoIntentTarget.current !== 'resilient-consumer') {
                demoIntentKey.current = createIdempotencyKey();
              }
              demoIntentTarget.current = 'resilient-consumer';
              void runDemo('resilient-consumer', 'payment-timeout-after-commit', 'pay-demo-res');
            }}
          >
            {demoPending ? '실행 중…' : '복원력 Consumer로 timeout-after-commit 실행 (PASS 예상)'}
          </button>
        </div>
        {demoError ? <p className="error" role="alert">{demoError}</p> : null}
      </section>

      <section>
        <h3>최근 Run</h3>
        {runsQuery.isLoading ? <LoadingState message="최근 Run을 불러오는 중…" /> : null}
        {runsQuery.isError ? (
          <QueryErrorState
            message="최근 Run을 불러오지 못했습니다. 서버 연결을 확인하세요."
            onRetry={() => void runsQuery.refetch()}
            testId="overview-runs-error"
          />
        ) : null}
        {runsQuery.isSuccess && runs.length === 0 ? (
          <BootstrapEmptyState message="아직 실행된 Run이 없습니다" testId="overview-runs-empty">
            <p><Link to="/scenarios">대표 Scenario를 살펴보세요.</Link></p>
          </BootstrapEmptyState>
        ) : null}
        {runs.length > 0 ? (
          <div className="table-scroll" tabIndex={0} aria-label="최근 Run 표">
            <table>
              <thead>
                <tr><th>Run</th><th>Scenario</th><th>Target</th><th>상태</th><th>생성 시각</th></tr>
              </thead>
              <tbody>
                {runs.map((run) => (
                  <tr key={run.runId}>
                    <td><Link to={`/runs/${run.runId}`}>{run.runId.slice(0, 8)}</Link></td>
                    <td>{run.scenario}</td>
                    <td>{run.targetProfile}</td>
                    <td><StatusBadge status={run.status} /></td>
                    <td>{run.createdAt}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </section>
    </div>
  );
}
