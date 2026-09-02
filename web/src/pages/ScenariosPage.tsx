import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { getScenarios } from '../api/client';
import { BootstrapEmptyState, LoadingState, QueryErrorState } from '../components/AsyncState';

/** 공개 Quick Start가 보장하는 두 대표 Scenario 이름이다. */
export const PUBLIC_SCENARIOS = [
  'payment-timeout-after-commit',
  'payment-success-callback-duplicate',
] as const;

/**
 * Scenario Artifact 목록을 보여주는 화면이다.
 *
 * <p>책임: 내장·사용자 Scenario를 이름·버전·import 시각으로 탐색하게 한다.</p>
 */
export function ScenariosPage() {
  const query = useQuery({ queryKey: ['scenarios'], queryFn: getScenarios });

  if (query.isLoading) {
    return <div><h2>Scenarios</h2><LoadingState message="Scenario 목록을 불러오는 중…" /></div>;
  }
  if (query.isError) {
    return (
      <div>
        <h2>Scenarios</h2>
        <QueryErrorState
          message="Scenario 목록을 불러오지 못했습니다. 서버 연결을 확인하세요."
          onRetry={() => void query.refetch()}
          testId="scenarios-server-error"
        />
      </div>
    );
  }

  const scenarios = query.data ?? [];
  const otherScenarios = scenarios.filter(
    (scenario) => !PUBLIC_SCENARIOS.some((publicName) => scenario.name === publicName),
  );

  return (
    <div>
      <h2>Scenarios</h2>
      {scenarios.length === 0 ? (
        <BootstrapEmptyState
          message="아직 등록된 Scenario가 없습니다"
          testId="scenarios-empty"
        />
      ) : (
        <>
          <section aria-labelledby="public-scenarios-title">
            <h3 id="public-scenarios-title">대표 공개 Scenario</h3>
            <p className="tagline">각 Scenario 상세에서 취약·복원력 Target으로 실행할 수 있습니다.</p>
            <ul className="representative-scenarios">
              {PUBLIC_SCENARIOS.map((publicName) => {
                const scenario = scenarios.find((item) => item.name === publicName && item.version === '1.0.0');
                return (
                  <li key={publicName}>
                    {scenario ? (
                      <Link to={`/scenarios/${scenario.name}/${scenario.version}`}>{scenario.name}</Link>
                    ) : (
                      <span>{publicName} <strong className="error">등록 필요</strong></span>
                    )}
                  </li>
                );
              })}
            </ul>
          </section>
          {otherScenarios.length > 0 ? (
            <section aria-labelledby="other-scenarios-title">
              <h3 id="other-scenarios-title">기타 imported Scenario</h3>
              <div className="table-scroll" tabIndex={0} aria-label="기타 Scenario 목록 표">
                <table>
                  <thead>
                    <tr><th>이름</th><th>버전</th><th>표시 이름</th><th>구분</th><th>import 시각</th></tr>
                  </thead>
                  <tbody>
                    {otherScenarios.map((scenario) => (
                      <tr key={`${scenario.name}@${scenario.version}`}>
                        <td><Link to={`/scenarios/${scenario.name}/${scenario.version}`}>{scenario.name}</Link></td>
                        <td>{scenario.version}</td>
                        <td>{scenario.displayName}</td>
                        <td>{scenario.builtIn ? 'built-in' : 'user'}</td>
                        <td>{scenario.importedAt}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          ) : null}
        </>
      )}
    </div>
  );
}
