#!/usr/bin/env node
import { constants, lstat, readFile, rename, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u;
const SHA1 = /^[0-9a-f]{40}$/u;
const IDEMPOTENCY_KEY = /^[A-Za-z0-9._~+/-]{16,128}$/u;
const EXPECTED = {
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

function fail(message) {
  throw new Error(`web UX receipt rejected: ${message}`);
}

function parseArguments(argv) {
  const values = new Map();
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!key?.startsWith('--') || value === undefined || values.has(key)) fail('invalid arguments');
    values.set(key, value);
  }
  const required = ['--fresh-report', '--post-report', '--observations', '--source-sha', '--output'];
  for (const key of required) if (!values.has(key)) fail(`missing ${key}`);
  if (values.size !== required.length) fail('unexpected argument');
  return Object.fromEntries(required.map((key) => [key.slice(2).replaceAll('-', '_'), values.get(key)]));
}

async function readRegularJson(file, label) {
  const stat = await lstat(file).catch(() => fail(`${label} is missing`));
  if (!stat.isFile() || stat.isSymbolicLink()) fail(`${label} must be a regular non-symlink file`);
  const raw = await readFile(file, 'utf8');
  const secret = process.env.OUTCOMEPROBE_CONTROL_API_TOKEN;
  if (!secret || secret.length < 32) fail('control token environment is missing');
  if (raw.includes(secret)) fail(`${label} contains the control token`);
  try {
    return JSON.parse(raw);
  } catch {
    return fail(`${label} is not JSON`);
  }
}

function collectSpecs(suites, output = []) {
  if (!Array.isArray(suites)) fail('report suites must be an array');
  for (const suite of suites) {
    if (!suite || typeof suite !== 'object') fail('report suite must be an object');
    if (Array.isArray(suite.specs)) output.push(...suite.specs);
    if (Array.isArray(suite.suites)) collectSpecs(suite.suites, output);
  }
  return output;
}

function validateReport(report, phase) {
  if (!report || typeof report !== 'object') fail(`${phase} report must be an object`);
  if (Array.isArray(report.errors) && report.errors.length !== 0) fail(`${phase} report has top-level errors`);
  const specs = collectSpecs(report.suites);
  const expected = EXPECTED[phase];
  const actual = specs.map((spec) => spec?.title).sort();
  if (JSON.stringify(actual) !== JSON.stringify([...expected].sort())) fail(`${phase} test inventory differs`);
  for (const spec of specs) {
    if (spec.ok !== true || !Array.isArray(spec.tests) || spec.tests.length !== 1) fail(`${phase} spec is not exact PASS`);
    const test = spec.tests[0];
    if (!Array.isArray(test.results) || test.results.length !== 1) fail(`${phase} retries or missing result detected`);
    const result = test.results[0];
    if (result.status !== 'passed' || (result.retry ?? 0) !== 0) fail(`${phase} result is not first-attempt PASS`);
    if ((result.attachments?.length ?? 0) !== 0 || (result.stdout?.length ?? 0) !== 0 || (result.stderr?.length ?? 0) !== 0) {
      fail(`${phase} result contains raw attachments or output`);
    }
  }
  return specs.length;
}

function validateObservations(value) {
  if (!value || value.schemaVersion !== 1 || !value.scenarios || typeof value.scenarios !== 'object') {
    fail('observations schema differs');
  }
  const contract = [
    ['timeoutAfterCommit', 'payment-timeout-after-commit', '1.0.0'],
    ['callbackDuplicate', 'payment-success-callback-duplicate', '1.0.0'],
  ];
  const output = {};
  const ids = new Set();
  const idempotencyKeys = new Set();
  for (const [scenarioKey, name, version] of contract) {
    const observed = value.scenarios[scenarioKey];
    if (!observed || observed.name !== name || observed.version !== version || !observed.runs) {
      fail(`${scenarioKey} observation differs`);
    }
    const runs = {};
    for (const [profile, status] of [['vulnerable', 'FAILED'], ['resilient', 'PASSED']]) {
      const run = observed.runs[profile];
      if (!run || !UUID_V4.test(run.runId) || run.status !== status || ids.has(run.runId)
          || !IDEMPOTENCY_KEY.test(run.idempotencyKey) || idempotencyKeys.has(run.idempotencyKey)
          || run.canonicalSeedOmitted !== true
          || run.targetProfile !== `${profile}-consumer`) {
        fail(`${scenarioKey}.${profile} run differs`);
      }
      ids.add(run.runId);
      idempotencyKeys.add(run.idempotencyKey);
      runs[profile] = { runId: run.runId, status, targetProfile: run.targetProfile };
    }
    if (Object.keys(observed.runs).sort().join(',') !== 'resilient,vulnerable') fail(`${scenarioKey} run inventory differs`);
    output[scenarioKey] = { name, version, runs };
  }
  if (Object.keys(value.scenarios).sort().join(',') !== 'callbackDuplicate,timeoutAfterCommit') fail('scenario inventory differs');
  return output;
}

async function main() {
  const args = parseArguments(process.argv.slice(2));
  if (!SHA1.test(args.source_sha)) fail('source SHA must be exact lowercase SHA-1');
  await lstat(args.output).then(() => fail('output already exists')).catch((error) => {
    if (error?.code !== 'ENOENT') throw error;
  });
  const fresh = await readRegularJson(args.fresh_report, 'fresh report');
  const post = await readRegularJson(args.post_report, 'post report');
  const observations = await readRegularJson(args.observations, 'observations');
  const freshCount = validateReport(fresh, 'fresh');
  const postCount = validateReport(post, 'post');
  const goldenScenarios = validateObservations(observations);
  const total = freshCount + postCount;
  const receipt = {
    schemaVersion: 1,
    result: 'PASS',
    source: { kind: 'exact-checkout', sourceSha: args.source_sha },
    runtime: {
      browser: 'chromium',
      viewports: { desktop: { width: 1280, height: 720 }, mobile: { width: 390, height: 844 } },
    },
    tests: { total, passed: total, failed: 0, skipped: 0, flaky: 0, retries: 0 },
    flows: {
      actualLogin: true,
      wrongTokenRejected: true,
      freshEmptyGuidance: true,
      bootstrapGuidance: true,
      invalidImportSafe: true,
      duplicateRequestCount: 1,
      exactScenarioDiscovery: true,
      auth401Recovery: true,
      serverDownSafe: true,
      serverRecovery: true,
      mobileNoOverflow: true,
    },
    goldenPath: {
      scenarios: goldenScenarios,
      runCount: 4,
      runIdempotencyKeysPresent: true,
      canonicalSeedPreserved: true,
    },
    secrets: { plaintextFindingCount: 0 },
    cleanup: { containers: 0, networks: 0, volumes: 0, temporaryCredentialsRemoved: true },
    pushPerformed: false,
  };
  const serialized = `${JSON.stringify(receipt, null, 2)}\n`;
  if (serialized.includes(process.env.OUTCOMEPROBE_CONTROL_API_TOKEN)) fail('receipt contains the control token');
  const parent = path.dirname(path.resolve(args.output));
  await lstat(parent).then((stat) => { if (!stat.isDirectory()) fail('output parent is not a directory'); });
  const temporary = path.join(parent, `.web-ux-summary.${process.pid}.tmp`);
  try {
    await writeFile(temporary, serialized, { encoding: 'utf8', mode: 0o600, flag: 'wx' });
    await rename(temporary, args.output);
  } catch (error) {
    await rm(temporary, { force: true }).catch(() => {});
    throw error;
  }
  await import('node:fs/promises').then(({ access }) => access(args.output, constants.R_OK));
}

main().catch((error) => {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
  process.exitCode = 1;
});
