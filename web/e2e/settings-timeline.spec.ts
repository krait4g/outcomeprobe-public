import { expect, test } from '@playwright/test';
import { authenticateThroughLogin, requireOperatorToken } from './auth';

test('@fresh actual login rejects a wrong token and explains the fresh bootstrap', async ({ page }) => {
  const wrongToken = `wrong-web-token-${'x'.repeat(32)}`;
  await page.goto('/');
  await page.getByLabel('운영자 토큰').fill(wrongToken);
  await page.getByRole('button', { name: '인증', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('운영자');
  await authenticateThroughLogin(page, requireOperatorToken());
  await expect(page.locator('body')).not.toContainText(requireOperatorToken());

  await page.goto('/#/scenarios');
  await expect(page.getByTestId('scenarios-empty')).toContainText('아직 등록된 Scenario가 없습니다');
  await expect(page.getByTestId('scenarios-empty')).toContainText('Docker bootstrap');
  await page.goto('/#/runs');
  await expect(page.getByTestId('runs-empty')).toContainText('아직 실행된 Run이 없습니다');
  await page.goto('/');
  await expect(page.getByTestId('overview-bootstrap-empty')).toContainText('Docker bootstrap');
  await expect(page.getByTestId('overview-runs-empty')).toContainText('아직 실행된 Run이 없습니다');
});

test('@fresh invalid import is safe and one in-flight validation cannot duplicate', async ({ page }) => {
  await authenticateThroughLogin(page);
  let requests = 0;
  await page.route('**/api/v1/artifacts/validate', async (route) => {
    requests += 1;
    await new Promise((resolve) => setTimeout(resolve, 500));
    await route.fulfill({ status: 422, contentType: 'application/json', body: '{"error":"invalid"}' });
  });
  await page.goto('/#/import');
  await page.getByLabel('Artifact YAML').fill('apiVersion: wrong/v9\nkind: Scenario\n');
  const validate = page.getByRole('button', { name: 'Validate', exact: true });
  await validate.evaluate((button: HTMLButtonElement) => {
    button.click();
    button.click();
  });
  await expect(page.getByRole('button', { name: '검증 중…', exact: true })).toBeDisabled();
  await expect(page.getByTestId('import-error')).toBeVisible();
  expect(requests).toBe(1);
  await expect(page.getByTestId('import-error')).not.toContainText('invalid');
});
