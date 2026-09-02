import { defineConfig } from '@playwright/test';

const jsonOutput = process.env.OUTCOMEPROBE_PLAYWRIGHT_JSON_OUTPUT;

/**
 * OutcomeProbe Web UI의 Playwright E2E 구성이다.
 *
 * <p>책임: Docker Compose로 기동된 전체 스택(UI :3000, 서버 :8080, Consumer 2종)을
 * 대상으로 핵심 사용자 흐름을 검증한다. 로컬에서는 서버 스택이 필요하므로
 * CI의 e2e 워크플로에서 실행한다.</p>
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 180_000,
  expect: { timeout: 60_000 },
  workers: 1,
  retries: 0,
  forbidOnly: true,
  use: {
    baseURL: process.env.OUTCOMEPROBE_UI_URL ?? 'http://localhost:3000',
    browserName: 'chromium',
    viewport: { width: 1280, height: 720 },
    screenshot: 'off',
    trace: 'off',
    video: 'off',
  },
  reporter: jsonOutput
    ? [['json', { outputFile: jsonOutput }]]
    : [['list']],
});
