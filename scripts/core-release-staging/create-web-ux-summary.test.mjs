import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const script = fileURLToPath(new URL('./create-web-ux-summary.mjs', import.meta.url));
const secret = `summary-test-token-${'x'.repeat(32)}`;
const sourceSha = '1'.repeat(40);
const titles = {
  fresh: [
    '@fresh actual login rejects a wrong token and explains the fresh bootstrap',
    '@fresh invalid import is safe and one in-flight validation cannot duplicate',
  ],
  post: [
    '@post exact two scenarios run against both profiles',
    '@post rejected session recovers through actual login',
    '@post simulated server outage is safe and recovers',
    '@post @mobile 390x844 onboarding has no page overflow',
  ],
};

function report(phase) {
  return {
    suites: [{ specs: titles[phase].map((title) => ({
      title,
      ok: true,
      tests: [{ results: [{ status: 'passed', retry: 0, attachments: [], stdout: [], stderr: [] }] }],
    })) }],
    errors: [],
  };
}

function observations() {
  return {
    schemaVersion: 1,
    scenarios: {
      timeoutAfterCommit: {
        name: 'payment-timeout-after-commit',
        version: '1.0.0',
        runs: {
          vulnerable: { runId: '11111111-1111-4111-8111-111111111111', status: 'FAILED', targetProfile: 'vulnerable-consumer', idempotencyKey: 'web-run-key-111111', canonicalSeedOmitted: true },
          resilient: { runId: '22222222-2222-4222-8222-222222222222', status: 'PASSED', targetProfile: 'resilient-consumer', idempotencyKey: 'web-run-key-222222', canonicalSeedOmitted: true },
        },
      },
      callbackDuplicate: {
        name: 'payment-success-callback-duplicate',
        version: '1.0.0',
        runs: {
          vulnerable: { runId: '33333333-3333-4333-8333-333333333333', status: 'FAILED', targetProfile: 'vulnerable-consumer', idempotencyKey: 'web-run-key-333333', canonicalSeedOmitted: true },
          resilient: { runId: '44444444-4444-4444-8444-444444444444', status: 'PASSED', targetProfile: 'resilient-consumer', idempotencyKey: 'web-run-key-444444', canonicalSeedOmitted: true },
        },
      },
    },
  };
}

async function invoke(mutator = () => {}) {
  const root = await mkdtemp(path.join(os.tmpdir(), 'web-ux-summary-test-'));
  const values = { fresh: report('fresh'), post: report('post'), observations: observations() };
  mutator(values);
  for (const [name, value] of Object.entries(values)) await writeFile(path.join(root, `${name}.json`), JSON.stringify(value));
  const output = path.join(root, 'web-ux-summary.json');
  const result = spawnSync(process.execPath, [
    script, '--fresh-report', path.join(root, 'fresh.json'), '--post-report', path.join(root, 'post.json'),
    '--observations', path.join(root, 'observations.json'), '--source-sha', sourceSha, '--output', output,
  ], { encoding: 'utf8', env: { ...process.env, OUTCOMEPROBE_CONTROL_API_TOKEN: secret } });
  return { result, output };
}

test('emits only the normalized six-flow receipt', async () => {
  const { result, output } = await invoke();
  assert.equal(result.status, 0, result.stderr);
  const receipt = JSON.parse(await readFile(output, 'utf8'));
  assert.deepEqual(receipt.tests, { total: 6, passed: 6, failed: 0, skipped: 0, flaky: 0, retries: 0 });
  assert.equal(receipt.goldenPath.runCount, 4);
  assert.equal(receipt.goldenPath.runIdempotencyKeysPresent, true);
  assert.equal(receipt.goldenPath.canonicalSeedPreserved, true);
  assert.equal(receipt.goldenPath.scenarios.timeoutAfterCommit.version, '1.0.0');
  assert.equal(receipt.goldenPath.scenarios.timeoutAfterCommit.runs.vulnerable.targetProfile, 'vulnerable-consumer');
  assert.equal(receipt.goldenPath.scenarios.callbackDuplicate.runs.resilient.targetProfile, 'resilient-consumer');
  assert.equal(receipt.secrets.plaintextFindingCount, 0);
  assert.equal(JSON.stringify(receipt).includes(secret), false);
});

test('rejects a failed browser result', async () => {
  const { result } = await invoke((values) => { values.post.suites[0].specs[0].tests[0].results[0].status = 'failed'; });
  assert.notEqual(result.status, 0);
});

test('rejects a skipped or missing flow', async () => {
  const { result } = await invoke((values) => { values.fresh.suites[0].specs.pop(); });
  assert.notEqual(result.status, 0);
});

test('rejects duplicate run identities and wrong status', async () => {
  const { result } = await invoke((values) => {
    values.observations.scenarios.callbackDuplicate.runs.vulnerable.runId = '11111111-1111-4111-8111-111111111111';
    values.observations.scenarios.callbackDuplicate.runs.resilient.status = 'FAILED';
  });
  assert.notEqual(result.status, 0);
});

test('rejects plaintext token in raw browser evidence', async () => {
  const { result } = await invoke((values) => { values.post.errors = [secret]; });
  assert.notEqual(result.status, 0);
});
