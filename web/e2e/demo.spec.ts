import { mkdir, rename, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { expect, type Page, test } from '@playwright/test';
import { requireOperatorToken, useOperatorAuthentication } from './auth';

useOperatorAuthentication();

const scenarios = [
  { key: 'timeoutAfterCommit', name: 'payment-timeout-after-commit', version: '1.0.0' },
  { key: 'callbackDuplicate', name: 'payment-success-callback-duplicate', version: '1.0.0' },
] as const;
const profiles = [
  { key: 'vulnerable', name: 'vulnerable-consumer', status: 'FAILED' },
  { key: 'resilient', name: 'resilient-consumer', status: 'PASSED' },
] as const;

async function runScenario(
  page: Page,
  scenario: (typeof scenarios)[number],
  profile: (typeof profiles)[number],
  ordinal: number,
) {
  await page.goto('/#/scenarios');
  await page.getByRole('link', { name: scenario.name, exact: true }).first().click();
  await expect(page.getByRole('heading', { name: `Scenario: ${scenario.name}@${scenario.version}` })).toBeVisible();
  await page.getByRole('button', { name: 'Run Scenario', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Run 설정' });
  await dialog.getByLabel('Target Profile').selectOption(profile.name);
  await dialog.getByLabel(/Seed/u).fill('');
  await dialog.getByLabel(/변수 override/u).fill(`paymentId=pay-web-${ordinal}`);

  const createdResponse = page.waitForResponse((response) => (
    response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/v1/runs'
  ));
  await page.route('**/api/v1/runs', async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 300));
    await route.continue();
  }, { times: 1 });
  await dialog.getByRole('button', { name: 'Run Scenario', exact: true }).click();
  await expect(dialog.getByRole('button', { name: '실행 중…', exact: true })).toBeDisabled();
  const response = await createdResponse;
  expect(response.ok()).toBe(true);
  const requestBody = response.request().postDataJSON() as Record<string, unknown>;
  expect(requestBody).toEqual({
    scenarioRef: { name: scenario.name, version: scenario.version },
    targetProfile: profile.name,
    variables: { paymentId: `pay-web-${ordinal}` },
  });
  expect(Object.hasOwn(requestBody, 'seed')).toBe(false);
  const idempotencyKey = await response.request().headerValue('idempotency-key');
  if (!idempotencyKey || !/^[A-Za-z0-9._~+/-]{16,128}$/u.test(idempotencyKey)) {
    throw new Error('Run request did not bind a safe Idempotency-Key');
  }
  const runHash = /^#\/runs\/([0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})$/u;
  await page.waitForURL((url) => runHash.test(url.hash));
  const runMatch = new URL(page.url()).hash.match(runHash);
  if (!runMatch) {
    throw new Error('Run navigation did not contain an exact UUIDv4 identity');
  }
  const runId = runMatch[1];
  const runHeader = page.locator('.run-header');
  await expect(runHeader.getByRole('status', { name: profile.status, exact: true })).toBeVisible({ timeout: 150_000 });
  await expect(runHeader).toContainText(`Scenario: ${scenario.name}@${scenario.version}`);
  await expect(runHeader).toContainText(`Target: ${profile.name}`);
  return {
    runId,
    status: profile.status,
    targetProfile: profile.name,
    idempotencyKey,
    canonicalSeedOmitted: true,
  };
}

test('@post exact two scenarios run against both profiles', async ({ page }) => {
  test.setTimeout(720_000);
  await page.goto('/#/scenarios');
  for (const scenario of scenarios) {
    await expect(page.getByRole('link', { name: scenario.name, exact: true }).first()).toBeVisible();
  }

  const observations: Record<string, unknown> = { schemaVersion: 1, scenarios: {} };
  const scenarioObservations = observations.scenarios as Record<string, unknown>;
  const idempotencyKeys = new Set<string>();
  let ordinal = 1;
  for (const scenario of scenarios) {
    const runs: Record<string, unknown> = {};
    for (const profile of profiles) {
      const run = await runScenario(page, scenario, profile, ordinal);
      expect(idempotencyKeys.has(run.idempotencyKey)).toBe(false);
      idempotencyKeys.add(run.idempotencyKey);
      runs[profile.key] = run;
      ordinal += 1;
    }
    scenarioObservations[scenario.key] = { name: scenario.name, version: scenario.version, runs };
  }
  expect(idempotencyKeys.size).toBe(4);

  const output = process.env.OUTCOMEPROBE_WEB_UX_OBSERVATIONS;
  if (!output) {
    throw new Error('OUTCOMEPROBE_WEB_UX_OBSERVATIONS must be set');
  }
  await mkdir(path.dirname(output), { recursive: true });
  const temporary = `${output}.tmp`;
  await writeFile(temporary, `${JSON.stringify(observations)}\n`, { encoding: 'utf8', mode: 0o600 });
  await rename(temporary, output);
});

test('@post rejected session recovers through actual login', async ({ page }) => {
  const rejectedToken = `rejected-web-token-${'x'.repeat(32)}`;
  const operatorToken = requireOperatorToken();
  await page.goto('/#/settings');
  await expect(page.getByRole('heading', { name: 'Settings', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '토큰 변경', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'OutcomeProbe 운영자 인증' })).toBeVisible();
  await page.getByLabel('운영자 토큰').fill(rejectedToken);
  await page.getByRole('button', { name: '인증', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('운영자');
  await expect(page.getByRole('heading', { name: 'OutcomeProbe 운영자 인증' })).toBeVisible();
  await page.getByLabel('운영자 토큰').fill(operatorToken);
  await page.getByRole('button', { name: '인증', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Settings', exact: true })).toBeVisible();
  await expect(page.locator('body')).not.toContainText(operatorToken);
});

test('@post simulated server outage is safe and recovers', async ({ page }) => {
  const apiPattern = '**/api/v1/artifacts?kind=SCENARIO';
  await page.route(apiPattern, (route) => route.abort('connectionfailed'));
  await page.goto('/#/scenarios');
  const error = page.getByTestId('scenarios-server-error');
  await expect(error).toBeVisible();
  await expect(error).not.toContainText('ECONNREFUSED');
  await page.unroute(apiPattern);
  await error.getByRole('button').click();
  await expect(page.getByRole('link', { name: scenarios[0].name, exact: true }).first()).toBeVisible();
});

test('@post @mobile 390x844 onboarding has no page overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  for (const [route, heading] of [
    ['/', 'Overview'],
    ['/#/scenarios', 'Scenarios'],
    ['/#/runs', 'Runs'],
    ['/#/import', 'Artifact Import'],
  ] as const) {
    await page.goto(route);
    await expect(page.getByRole('navigation', { name: '주요 화면' })).toBeVisible();
    await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible();
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  }
});
