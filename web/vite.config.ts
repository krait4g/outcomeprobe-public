import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

/**
 * OutcomeProbe Web UI의 Vite·Vitest 구성이다.
 *
 * <p>책임: 개발 서버에서 Control API(/api)를 로컬 서버로 프록시하고,
 * 단위 테스트를 jsdom 환경에서 실행한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: SSE 재연결과 Playwright E2E 구성은
 * M3에서 추가된다. 이 구성은 M0 스켈레톤 기준이다.</p>
 */
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
    setupFiles: ['test/setup.ts'],
  },
});
