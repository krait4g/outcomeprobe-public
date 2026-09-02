import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { apiFetch, type RunSummary } from '../api/client';
import { StatusBadge } from '../components/StatusBadge';
import { BootstrapEmptyState, LoadingState, QueryErrorState } from '../components/AsyncState';

/**
 * 전체 Run 목록을 보여주는 화면이다.
 *
 * <p>책임: Run 상태를 실시간에 가깝게 갱신해 진행 중 Run과 최근 결과를 추적하게 한다.</p>
 */
export function RunsPage() {
  const query = useQuery({
    queryKey: ['runs'],
    queryFn: () => apiFetch<RunSummary[]>('/runs?limit=50'),
    refetchInterval: 2000,
  });

  if (query.isLoading) {
    return <div><h2>Runs</h2><LoadingState message="Run 목록을 불러오는 중…" /></div>;
  }
  if (query.isError) {
    return (
      <div>
        <h2>Runs</h2>
        <QueryErrorState
          message="Run 목록을 불러오지 못했습니다. 서버 연결을 확인하세요."
          onRetry={() => void query.refetch()}
          testId="runs-server-error"
        />
      </div>
    );
  }

  const runs = query.data ?? [];

  return (
    <div>
      <h2>Runs</h2>
      {runs.length === 0 ? (
        <BootstrapEmptyState message="아직 실행된 Run이 없습니다" testId="runs-empty">
          <p><Link to="/scenarios">대표 Scenario를 선택해 첫 Run을 시작하세요.</Link></p>
        </BootstrapEmptyState>
      ) : (
        <div className="table-scroll" tabIndex={0} aria-label="Run 목록 표">
          <table>
            <thead>
              <tr>
                <th>Run</th><th>Scenario</th><th>Target</th><th>Seed</th><th>상태</th><th>문제 코드</th><th>생성 시각</th>
              </tr>
            </thead>
            <tbody>
              {runs.map((run) => (
                <tr key={run.runId}>
                  <td><Link to={`/runs/${run.runId}`}>{run.runId.slice(0, 8)}</Link></td>
                  <td>{run.scenario}</td>
                  <td>{run.targetProfile}</td>
                  <td>{run.seed}</td>
                  <td><StatusBadge status={run.status} /></td>
                  <td>{run.problemCode ?? ''}</td>
                  <td>{run.createdAt}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
