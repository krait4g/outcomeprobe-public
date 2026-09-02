import { expect, type Page, test } from '@playwright/test';

/** Web 제품 코드와 공유하는 sessionStorage key다. */
const CONTROL_TOKEN_SESSION_KEY = 'outcomeprobe.control-api-token';

export function requireOperatorToken(): string {
  const token = process.env.OUTCOMEPROBE_CONTROL_API_TOKEN;
  if (!token || token.length < 32) {
    throw new Error('OUTCOMEPROBE_CONTROL_API_TOKEN must be set for authenticated Web E2E');
  }
  return token;
}

export async function authenticateThroughLogin(page: Page, token = requireOperatorToken()): Promise<void> {
  await page.goto('/');
  await page.getByLabel('운영자 토큰').fill(token);
  await page.getByRole('button', { name: '인증', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'OutcomeProbe', exact: true })).toBeVisible();
}

export async function installOperatorToken(page: Page, token = requireOperatorToken()): Promise<void> {
  await page.addInitScript(
    ({ key, value }) => window.sessionStorage.setItem(key, value),
    { key: CONTROL_TOKEN_SESSION_KEY, value: token },
  );
}

/**
 * 각 Playwright 페이지가 첫 요청 전에 CI 운영자 토큰을 탭 저장소에서 읽게 한다.
 *
 * <p>토큰을 URL·screenshot·DOM input에 노출하지 않고 page 초기화 script의 인자로만
 * 전달한다. 환경변수가 없으면 인증 없는 E2E가 거짓 통과하지 않게 즉시 실패한다.</p>
 */
export function useOperatorAuthentication(): void {
  test.beforeEach(async ({ page }) => {
    await installOperatorToken(page);
  });
}
