/**
 * 브라우저 탭 안에서만 단일 운영자 Control API 토큰을 보관한다.
 *
 * <p>책임: 토큰을 URL·DOM·localStorage에 남기지 않고 현재 탭의 sessionStorage와
 * 메모리에서만 관리한다. 외부에서 오해하기 쉬운 한계: 브라우저 XSS까지 막는
 * credential vault가 아니므로 Web은 신뢰된 로컬 환경에서만 사용해야 한다.</p>
 */

/** Control API 토큰이 바뀌었음을 알리는 브라우저 이벤트 이름이다. */
export const CONTROL_TOKEN_CHANGED_EVENT = 'outcomeprobe:control-token-changed';

/** 서버가 현재 토큰을 거부했음을 알리는 브라우저 이벤트 이름이다. */
export const CONTROL_AUTH_REQUIRED_EVENT = 'outcomeprobe:control-auth-required';

/** Playwright와 제품 코드가 공유하는 탭 단위 저장소 키다. */
export const CONTROL_TOKEN_SESSION_KEY = 'outcomeprobe.control-api-token';

const MIN_TOKEN_LENGTH = 32;
const MAX_TOKEN_LENGTH = 512;
let memoryToken: string | null = null;

/**
 * 현재 탭의 Control API 토큰을 읽는다.
 *
 * @returns 설정된 토큰, 없으면 null
 */
export function getControlApiToken(): string | null {
  if (memoryToken !== null) {
    return memoryToken;
  }
  if (typeof window === 'undefined') {
    return null;
  }
  try {
    const stored = window.sessionStorage.getItem(CONTROL_TOKEN_SESSION_KEY);
    if (stored && !isValidControlToken(stored)) {
      window.sessionStorage.removeItem(CONTROL_TOKEN_SESSION_KEY);
      memoryToken = null;
      return null;
    }
    memoryToken = stored;
    return memoryToken;
  } catch {
    return null;
  }
}

/**
 * 현재 탭의 Control API 토큰을 저장한다.
 *
 * @param rawToken 운영자가 입력한 토큰
 * @param announce 검증된 변경을 전역 UI에 즉시 알릴지 여부
 * @throws 토큰이 비어 있거나 안전한 길이·문자 계약을 위반할 때 발생한다
 */
export function setControlApiToken(rawToken: string, announce = true): void {
  const token = rawToken;
  if (!isValidControlToken(token)) {
    throw new Error(
      `운영자 토큰은 ${MIN_TOKEN_LENGTH}~${MAX_TOKEN_LENGTH}자의 제한된 token68 문자여야 합니다.`,
    );
  }
  memoryToken = token;
  if (typeof window !== 'undefined') {
    try {
      window.sessionStorage.setItem(CONTROL_TOKEN_SESSION_KEY, token);
    } catch {
      // 저장소가 차단된 브라우저에서도 현재 페이지의 메모리 세션은 유지한다.
    }
    if (announce) {
      window.dispatchEvent(new Event(CONTROL_TOKEN_CHANGED_EVENT));
    }
  }
}

/** 현재 탭의 토큰을 지우고 인증 화면으로 돌아갈 수 있게 알린다. */
export function clearControlApiToken(): void {
  memoryToken = null;
  if (typeof window !== 'undefined') {
    try {
      window.sessionStorage.removeItem(CONTROL_TOKEN_SESSION_KEY);
    } catch {
      // 저장소 접근 실패와 무관하게 메모리 토큰은 이미 폐기됐다.
    }
    window.dispatchEvent(new Event(CONTROL_TOKEN_CHANGED_EVENT));
  }
}

/** 서버의 401·403 응답을 토큰 원문 없이 전역 인증 경계에 전달한다. */
export function rejectControlApiToken(): void {
  clearControlApiToken();
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new Event(CONTROL_AUTH_REQUIRED_EVENT));
  }
}

/**
 * 토큰이 HTTP header에 안전한 단일 행 문자열인지 검증한다.
 *
 * @param token 검증할 토큰
 * @returns 길이와 제어문자 계약을 만족하면 true
 */
export function isValidControlToken(token: string): boolean {
  return token.length >= MIN_TOKEN_LENGTH
    && token.length <= MAX_TOKEN_LENGTH
    && /^[A-Za-z0-9._~+/-]+={0,2}$/u.test(token);
}

/** 단위 테스트가 모듈 메모리 상태를 격리할 때만 사용하는 초기화 함수다. */
export function resetControlTokenForTest(): void {
  memoryToken = null;
}
