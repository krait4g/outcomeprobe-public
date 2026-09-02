import { useQuery } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { getProfiles, verifyTargetProfile, type TargetProfilePreflight } from '../api/client';
import { BootstrapEmptyState, LoadingState, QueryErrorState } from '../components/AsyncState';

/**
 * Target Profile Artifact 목록을 보여주는 화면이다.
 *
 * <p>책임: Scenario가 연결할 수 있는 Target 신뢰 경계 목록을 제공한다.
 * secret 값은 표시하지 않는다.</p>
 */
export function ProfilesPage() {
  const query = useQuery({ queryKey: ['profiles'], queryFn: getProfiles });
  const inFlight = useRef(false);
  const [activeProfile, setActiveProfile] = useState<string | null>(null);
  const [preflight, setPreflight] = useState<TargetProfilePreflight | null>(null);
  const [preflightError, setPreflightError] = useState<string | null>(null);

  /** 한 번에 한 Profile만 검사해 중복 POST와 결과 혼선을 막는다. */
  async function runPreflight(profileName: string) {
    if (inFlight.current) {
      return;
    }
    inFlight.current = true;
    setActiveProfile(profileName);
    setPreflight(null);
    setPreflightError(null);
    try {
      setPreflight(await verifyTargetProfile(profileName));
    } catch {
      setPreflightError('연결 사전검사를 완료하지 못했습니다. 설정과 Target 상태를 확인하세요.');
    } finally {
      inFlight.current = false;
      setActiveProfile(null);
    }
  }

  if (query.isLoading) {
    return <div><h2>Target Profiles</h2><LoadingState message="Target Profile 목록을 불러오는 중…" /></div>;
  }
  if (query.isError) {
    return (
      <div>
        <h2>Target Profiles</h2>
        <QueryErrorState
          message="Target Profile 목록을 불러오지 못했습니다. 서버 연결을 확인하세요."
          onRetry={() => void query.refetch()}
          testId="profiles-server-error"
        />
      </div>
    );
  }

  const profiles = query.data ?? [];

  return (
    <div>
      <h2>Target Profiles</h2>
      {profiles.length === 0 ? (
        <BootstrapEmptyState
          message="아직 등록된 Target Profile이 없습니다"
          testId="profiles-empty"
        />
      ) : (
        <div className="table-scroll" tabIndex={0} aria-label="Target Profile 목록 표">
          <table>
            <thead>
              <tr><th>이름</th><th>버전</th><th>표시 이름</th><th>구분</th><th>import 시각</th><th>연결</th></tr>
            </thead>
            <tbody>
              {profiles.map((profile) => (
                <tr key={`${profile.name}@${profile.version}`}>
                  <td>{profile.name}</td>
                  <td>{profile.version}</td>
                  <td>{profile.displayName}</td>
                  <td>{profile.builtIn ? 'built-in' : 'user'}</td>
                  <td>{profile.importedAt}</td>
                  <td>
                    <button
                      type="button"
                      disabled={activeProfile !== null}
                      onClick={() => void runPreflight(profile.name)}
                    >
                      {activeProfile === profile.name ? '검사 중…' : '연결 사전검사'}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {preflight !== null && (
        <section
          className={`preflight-result ${preflight.passed ? 'preflight-pass' : 'preflight-fail'}`}
          role={preflight.passed ? 'status' : 'alert'}
          aria-live="polite"
        >
          <strong>{preflight.name}: {preflight.passed ? '연결 준비 완료' : '연결 준비 실패'}</strong>
          <ul>
            {Object.entries(preflight.checks)
              .filter((entry): entry is [string, boolean] => typeof entry[1] === 'boolean')
              .map(([name, passed]) => <li key={name}>{name}: {passed ? 'PASS' : 'FAIL'}</li>)}
          </ul>
        </section>
      )}
      {preflightError !== null && <p role="alert" className="error-banner">{preflightError}</p>}
    </div>
  );
}
