import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { OverviewPage } from './OverviewPage';
import { jsonResponse } from './testResponses';

/**
 * Overview 화면이 통계·Quick Start·최근 Run을 올바르게 표시하는지 검증한다.
 *
 * <p>검출 가능한 결함: 통계 API 응답이 화면에 반영되지 않거나, Quick Start
 * 버튼이 사라지거나, 빈 목록에서 렌더링이 깨지는 문제.</p>
 */
describe('OverviewPage', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      const body = (() => {
        if (String(url).includes('/stats')) {
          return {
            passed24h: 12,
            failed24h: 3,
            error24h: 1,
            activeRuns: 2,
            staleRuns: 0,
            mostFailedScenario: 'payment-timeout-after-commit',
          };
        }
        if (String(url).includes('kind=SCENARIO')) {
          return [{ name: 'payment-timeout-after-commit', version: '1.0.0' }];
        }
        if (String(url).includes('kind=TARGET_PROFILE')) {
          return [{ name: 'vulnerable-consumer' }, { name: 'resilient-consumer' }];
        }
        return [
          {
            runId: 'abc12345',
            scenario: 'payment-timeout-after-commit@1.0.0',
            targetProfile: 'vulnerable-consumer',
            seed: 42001,
            status: 'FAILED',
            problemCode: 'PROBE_ASSERTION_FAILED',
            startedAt: null,
            finishedAt: null,
            createdAt: '2026-01-01T00:00:00Z',
          },
        ];
      })();
      return jsonResponse(body);
    }));
  });

  it('shows 24h stats and quick start buttons', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <OverviewPage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText('12')).toBeDefined();
    });
    expect(screen.getByText(/PASSED \(24h\)/)).toBeDefined();
    expect(screen.getByText(/FAILED \(24h\)/)).toBeDefined();
    expect(screen.getByText(/취약 Consumer/)).toBeDefined();
    expect(screen.getByText(/복원력 Consumer/)).toBeDefined();
    expect(screen.queryByRole('button', { name: /중복 Callback/ })).toBeNull();
  });

  it('shows recent runs in the list', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <OverviewPage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText('abc12345')).toBeDefined();
    });
    expect(screen.getByText('FAILED')).toBeDefined();
  });

  it('disables both demo buttons and prevents duplicate run creation while pending', async () => {
    let completeRun: ((response: Response) => void) | undefined;
    const pendingRun = new Promise<Response>((resolve) => {
      completeRun = resolve;
    });
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        return pendingRun;
      }
      const body = (() => {
          if (String(url).includes('/stats')) return {};
          if (String(url).includes('kind=SCENARIO')) return [{ name: 'payment-timeout-after-commit', version: '1.0.0' }];
          if (String(url).includes('kind=TARGET_PROFILE')) return [{ name: 'vulnerable-consumer' }, { name: 'resilient-consumer' }];
          return [];
      })();
      return jsonResponse(body);
    });
    vi.stubGlobal('fetch', fetchMock);

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <OverviewPage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    const vulnerable = screen.getByRole('button', { name: /취약 Consumer/ });
    const resilient = screen.getByRole('button', { name: /복원력 Consumer/ });
    await waitFor(() => {
      expect((vulnerable as HTMLButtonElement).disabled).toBe(false);
      expect((resilient as HTMLButtonElement).disabled).toBe(false);
    });
    fireEvent.click(vulnerable);
    fireEvent.click(vulnerable);

    await waitFor(() => {
      expect((vulnerable as HTMLButtonElement).disabled).toBe(true);
      expect((resilient as HTMLButtonElement).disabled).toBe(true);
    });
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1);

    completeRun?.(jsonResponse({ runId: '10000000-0000-4000-8000-000000000001' }));
    await waitFor(() => {
      expect((vulnerable as HTMLButtonElement).disabled).toBe(false);
      expect((resilient as HTMLButtonElement).disabled).toBe(false);
    });
  });

  it('shows a safe error and re-enables demos after run creation fails', async () => {
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        return jsonResponse({ detail: 'raw-secret-diagnostic' }, 400, 'application/problem+json');
      }
      const body = (() => {
          if (String(url).includes('/stats')) return {};
          if (String(url).includes('kind=SCENARIO')) return [{ name: 'payment-timeout-after-commit', version: '1.0.0' }];
          if (String(url).includes('kind=TARGET_PROFILE')) return [{ name: 'vulnerable-consumer' }, { name: 'resilient-consumer' }];
          return [];
      })();
      return jsonResponse(body);
    });
    vi.stubGlobal('fetch', fetchMock);

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <OverviewPage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    const vulnerable = screen.getByRole('button', { name: /취약 Consumer/ });
    const resilient = screen.getByRole('button', { name: /복원력 Consumer/ });
    await waitFor(() => {
      expect((vulnerable as HTMLButtonElement).disabled).toBe(false);
      expect((resilient as HTMLButtonElement).disabled).toBe(false);
    });
    fireEvent.click(vulnerable);

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('Docker bootstrap을 완료하고 다시 시도하세요.');
    expect(alert.textContent).not.toContain('HTTP 400');
    expect(alert.textContent).not.toContain('raw-secret-diagnostic');
    expect((vulnerable as HTMLButtonElement).disabled).toBe(false);
    expect((resilient as HTMLButtonElement).disabled).toBe(false);
  });

  it('separates a server outage from empty data and offers retry', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => {
      throw new Error('raw-server-diagnostic');
    }));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter><OverviewPage /></MemoryRouter>
      </QueryClientProvider>,
    );

    const serverError = await screen.findByTestId('overview-server-error');
    expect(serverError.textContent).toContain('서버 연결을 확인하세요.');
    expect(document.body.textContent).not.toContain('raw-server-diagnostic');
    expect(screen.queryByTestId('overview-bootstrap-empty')).toBeNull();
    expect(screen.getAllByRole('button', { name: '다시 시도' }).length).toBeGreaterThan(0);
  });

  it('disables demo mutations after a background outage until an explicit retry succeeds', async () => {
    let runsUnavailable = false;
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (String(url).includes('/runs')) {
        if (runsUnavailable) {
          throw new Error('raw-background-outage');
        }
        return jsonResponse([]);
      }
      if (String(url).includes('/stats')) {
        return jsonResponse({});
      }
      if (String(url).includes('kind=SCENARIO')) {
        return jsonResponse([{ name: 'payment-timeout-after-commit', version: '1.0.0' }]);
      }
      return jsonResponse([{ name: 'vulnerable-consumer' }, { name: 'resilient-consumer' }]);
    }));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter><OverviewPage /></MemoryRouter>
      </QueryClientProvider>,
    );

    const vulnerable = screen.getByRole('button', { name: /취약 Consumer/ });
    const resilient = screen.getByRole('button', { name: /복원력 Consumer/ });
    await waitFor(() => {
      expect((vulnerable as HTMLButtonElement).disabled).toBe(false);
      expect((resilient as HTMLButtonElement).disabled).toBe(false);
    });

    runsUnavailable = true;
    await queryClient.refetchQueries({ queryKey: ['runs-recent'] });
    await waitFor(() => {
      expect((vulnerable as HTMLButtonElement).disabled).toBe(true);
      expect((resilient as HTMLButtonElement).disabled).toBe(true);
    });
    expect(screen.queryByTestId('overview-bootstrap-empty')).toBeNull();
    expect(document.body.textContent).not.toContain('raw-background-outage');

    runsUnavailable = false;
    fireEvent.click(within(screen.getByTestId('overview-runs-error')).getByRole('button', {
      name: '다시 시도',
    }));
    await waitFor(() => {
      expect((vulnerable as HTMLButtonElement).disabled).toBe(false);
      expect((resilient as HTMLButtonElement).disabled).toBe(false);
    });
  });
});
