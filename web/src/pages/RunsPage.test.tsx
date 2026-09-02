import { render, screen, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom/vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RunsPage } from './RunsPage';
import { jsonResponse } from './testResponses';

/**
 * Runs 목록 화면이 Run 데이터를 테이블로 표시하는지 검증한다.
 *
 * <p>검출 가능한 결함: 빈 목록에서 테이블 헤더만 남거나, 상태 배지가
 * 렌더링되지 않거나, Run 링크가 잘못된 경로를 가리키는 문제.</p>
 */
describe('RunsPage', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse([
        {
          runId: 'run-aaaa1111',
          scenario: 'payment-timeout@1.0.0',
          targetProfile: 'resilient-consumer',
          seed: 100,
          status: 'PASSED',
          problemCode: null,
          startedAt: '2026-01-01T00:00:00Z',
          finishedAt: '2026-01-01T00:00:05Z',
          createdAt: '2026-01-01T00:00:00Z',
        },
        {
          runId: 'run-bbbb2222',
          scenario: 'booking-duplicate@1.0.0',
          targetProfile: 'vulnerable-consumer',
          seed: 200,
          status: 'FAILED',
          problemCode: 'PROBE_ASSERTION_FAILED',
          startedAt: null,
          finishedAt: null,
          createdAt: '2026-01-01T01:00:00Z',
        },
    ])));
  });

  it('renders run rows with status badges', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <RunsPage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText('run-aaaa')).toBeDefined();
    });
    expect(screen.getByText('run-bbbb')).toBeDefined();
    expect(screen.getByText('PASSED')).toBeDefined();
    expect(screen.getByText('FAILED')).toBeDefined();
    expect(screen.getByText('resilient-consumer')).toBeDefined();
  });

  it('distinguishes a fresh empty list from a server error', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse([])));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter><RunsPage /></MemoryRouter>
      </QueryClientProvider>,
    );
    expect(await screen.findByText('아직 실행된 Run이 없습니다')).toBeInTheDocument();
    expect(screen.getByText(/Docker bootstrap/u)).toBeInTheDocument();
  });
});
