import { type FormEvent, type ReactNode, useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { ControlAuthenticationError, getSettings, safeErrorMessage } from '../api/client';
import {
  CONTROL_AUTH_REQUIRED_EVENT,
  CONTROL_TOKEN_CHANGED_EVENT,
  clearControlApiToken,
  getControlApiToken,
  setControlApiToken,
} from '../auth/controlToken';

/** 운영자 인증 경계 속성이다. */
interface OperatorAuthBoundaryProps {
  /** 인증된 세션에만 표시할 UI다. */
  children: ReactNode;
}

/**
 * Control API 토큰이 확인된 탭에만 제품 UI를 노출한다.
 *
 * <p>책임: 토큰 입력·교체·401 재인증을 한곳에서 처리하고 인증 전 Query cache가
 * 다음 세션에 남지 않게 한다. 토큰은 렌더링하거나 오류 메시지에 포함하지 않는다.</p>
 *
 * @param props 인증 뒤 표시할 자식 UI
 * @returns 인증 화면 또는 제품 UI
 */
export function OperatorAuthBoundary({ children }: OperatorAuthBoundaryProps) {
  const queryClient = useQueryClient();
  const [authenticated, setAuthenticated] = useState(false);
  const [verifying, setVerifying] = useState(() => getControlApiToken() !== null);
  const [verificationError, setVerificationError] = useState('');
  const [verificationAttempt, setVerificationAttempt] = useState(0);
  const [rejected, setRejected] = useState(false);

  useEffect(() => {
    let active = true;
    if (getControlApiToken() === null) {
      setVerifying(false);
      return () => {
        active = false;
      };
    }
    setVerifying(true);
    setVerificationError('');
    void getSettings()
      .then(() => {
        if (active) {
          setAuthenticated(true);
          setRejected(false);
          setVerifying(false);
        }
      })
      .catch((cause: unknown) => {
        if (!active) {
          return;
        }
        setAuthenticated(false);
        setVerifying(false);
        if (cause instanceof ControlAuthenticationError) {
          setRejected(true);
        } else {
          setVerificationError(safeErrorMessage(
            cause,
            '저장된 운영자 토큰을 서버에서 확인할 수 없습니다.',
          ));
        }
      });
    return () => {
      active = false;
    };
  }, [verificationAttempt]);

  useEffect(() => {
    const update = () => {
      if (getControlApiToken() === null) {
        setAuthenticated(false);
        setVerifying(false);
        setVerificationError('');
      }
    };
    const requireAuthentication = () => {
      setRejected(true);
      setAuthenticated(false);
      setVerifying(false);
      setVerificationError('');
      queryClient.clear();
    };
    window.addEventListener(CONTROL_TOKEN_CHANGED_EVENT, update);
    window.addEventListener(CONTROL_AUTH_REQUIRED_EVENT, requireAuthentication);
    return () => {
      window.removeEventListener(CONTROL_TOKEN_CHANGED_EVENT, update);
      window.removeEventListener(CONTROL_AUTH_REQUIRED_EVENT, requireAuthentication);
    };
  }, [queryClient]);

  if (verifying) {
    return <main className="operator-login" role="status">운영자 세션을 확인하고 있습니다.</main>;
  }

  if (!authenticated && verificationError && getControlApiToken() !== null) {
    return (
      <main className="operator-login">
        <section aria-labelledby="operator-verification-title">
          <h1 id="operator-verification-title">운영자 세션 확인 실패</h1>
          <p className="error" role="alert">{verificationError}</p>
          <button type="button" onClick={() => setVerificationAttempt((value) => value + 1)}>
            다시 확인
          </button>
          <button type="button" onClick={() => clearControlApiToken()}>
            다른 토큰 입력
          </button>
        </section>
      </main>
    );
  }

  if (!authenticated) {
    return (
      <OperatorLogin
        rejected={rejected}
        onAuthenticated={() => {
          setRejected(false);
          setAuthenticated(true);
          queryClient.clear();
        }}
      />
    );
  }

  return (
    <>
      <div className="operator-session" role="status">
        <span>단일 운영자 세션</span>
        <button
          type="button"
          onClick={() => {
            queryClient.clear();
            clearControlApiToken();
          }}
        >
          토큰 변경
        </button>
      </div>
      {children}
    </>
  );
}

/** 로그인 화면 속성이다. */
interface OperatorLoginProps {
  /** 직전 토큰이 서버에서 거부됐는지 여부다. */
  rejected: boolean;
  /** 서버 검증까지 성공했을 때 호출한다. */
  onAuthenticated: () => void;
}

/**
 * 탭 단위 운영자 토큰을 입력하고 서버에서 검증한다.
 *
 * @param props 거부 상태와 인증 성공 callback
 * @returns 운영자 인증 form
 */
function OperatorLogin({ rejected, onAuthenticated }: OperatorLoginProps) {
  const [token, setToken] = useState('');
  const [pending, setPending] = useState(false);
  const submitInFlight = useRef(false);
  const [error, setError] = useState(rejected ? '운영자 토큰이 거부되었습니다. 새 토큰을 입력하세요.' : '');

  /** 토큰을 탭 저장소에 잠시 설정한 뒤 보호된 Settings API로 검증한다. */
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitInFlight.current) {
      return;
    }
    submitInFlight.current = true;
    setPending(true);
    setError('');
    try {
      // 보호된 API 검증이 끝나기 전에는 제품 UI를 잠깐이라도 열지 않는다.
      setControlApiToken(token, false);
      await getSettings();
      setToken('');
      onAuthenticated();
    } catch (cause) {
      clearControlApiToken();
      // 실패 종류와 무관하게 password input의 DOM value에서 원문 토큰을 즉시 제거한다.
      setToken('');
      setError(safeErrorMessage(cause, '운영자 인증에 실패했습니다.'));
    } finally {
      submitInFlight.current = false;
      setPending(false);
    }
  }

  return (
    <main className="operator-login">
      <section aria-labelledby="operator-login-title">
        <h1 id="operator-login-title">OutcomeProbe 운영자 인증</h1>
        <p>
          서버의 <code>OUTCOMEPROBE_CONTROL_API_TOKEN</code>과 같은 값을 입력하세요.
          토큰은 현재 브라우저 탭의 sessionStorage에만 보관됩니다.
        </p>
        <form onSubmit={(event) => void submit(event)}>
          <label htmlFor="operator-token">운영자 토큰</label>
          <input
            id="operator-token"
            name="operator-token"
            type="password"
            autoComplete="off"
            autoCapitalize="none"
            spellCheck={false}
            value={token}
            onChange={(event) => setToken(event.target.value)}
            required
            minLength={32}
            maxLength={512}
          />
          <button type="submit" disabled={pending}>
            {pending ? '확인 중...' : '인증'}
          </button>
        </form>
        {error ? <p className="error" role="alert">{error}</p> : null}
        <p className="tagline">
          이 인증은 단일 운영자용입니다. OIDC·RBAC·멀티테넌시는 지원하지 않으며 인터넷에 직접 노출하면 안 됩니다.
        </p>
      </section>
    </main>
  );
}
