import { describe, expect, it, vi } from 'vitest';
import {
  CONTROL_TOKEN_SESSION_KEY,
  clearControlApiToken,
  getControlApiToken,
  isValidControlToken,
  setControlApiToken,
} from './controlToken';

/** 탭 단위 토큰 저장·삭제·입력 경계를 검증한다. */
describe('controlToken', () => {
  it('uses sessionStorage and never localStorage', () => {
    const localStorageSpy = vi.spyOn(Storage.prototype, 'setItem');
    const token = 'session-only-control-token-0123456789abcdef';

    setControlApiToken(token);

    expect(getControlApiToken()).toBe(token);
    expect(window.sessionStorage.getItem(CONTROL_TOKEN_SESSION_KEY)).toBe(token);
    expect(window.localStorage.getItem(CONTROL_TOKEN_SESSION_KEY)).toBeNull();
    expect(localStorageSpy).toHaveBeenCalledTimes(1);

    clearControlApiToken();
    expect(getControlApiToken()).toBeNull();
  });

  it('rejects blank, short, whitespace and header-injection values', () => {
    for (const invalid of [
      '',
      'short',
      `valid-looking-token-${'x'.repeat(20)}\nInjected: yes`,
      `has space ${'x'.repeat(40)}`,
      ` ${'x'.repeat(32)}`,
      `${'x'.repeat(32)} `,
      `quote-${'x'.repeat(32)}"`,
      `${'x'.repeat(32)}=padding-in-the-middle`,
    ]) {
      expect(isValidControlToken(invalid)).toBe(false);
      expect(() => setControlApiToken(invalid)).toThrowError(/운영자 토큰/u);
    }
  });

  it('deletes an invalid stored token instead of treating it as an authenticated session', () => {
    clearControlApiToken();
    window.sessionStorage.setItem(CONTROL_TOKEN_SESSION_KEY, 'invalid\nheader');

    expect(getControlApiToken()).toBeNull();
    expect(window.sessionStorage.getItem(CONTROL_TOKEN_SESSION_KEY)).toBeNull();
  });
});
