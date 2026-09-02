import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SettingsPage } from './SettingsPage';
import { jsonResponse } from './testResponses';

/**
 * Settings 화면이 서버 실행 중 설정을 올바르게 표시하는지 검증한다.
 *
 * <p>검출 가능한 결함: 서버가 production gateway로 잘못 표시되거나, telemetry가
 * on으로 뒤집혀 보이거나, 읽기 전용 안내가 사라지는 문제.</p>
 */
describe('SettingsPage', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({
        version: '0.1.0',
        dslVersions: ['outcomeprobe.io/v1alpha1'],
        mode: 'local-test-workbench',
        storage: 'postgresql',
        productionGateway: false,
        retentionDays: 14,
        maxConcurrentRuns: 10,
        emulatorTokenRequired: false,
        telemetryEnabled: false,
        telemetryNote: 'OutcomeProbe는 사용 통계를 외부로 자동 전송하지 않습니다.',
    })));
  });

  it('shows local workbench mode and privacy facts', async () => {
    const queryClient = new QueryClient();
    render(
      <QueryClientProvider client={queryClient}>
        <SettingsPage />
      </QueryClientProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText('local-test-workbench')).toBeDefined();
    });
    expect(screen.getByText(/아니요 \(local·CI 전용\)/)).toBeDefined();
    expect(screen.getByText(/off \(기본값\)/)).toBeDefined();
    expect(screen.getByText(/읽기 전용/)).toBeDefined();
  });
});
