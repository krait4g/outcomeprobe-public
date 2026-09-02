import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom/vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({
  cancelRun: vi.fn(),
  downloadReport: vi.fn(),
  getReport: vi.fn(),
  getRun: vi.fn(),
  getSteps: vi.fn(),
  getTimeline: vi.fn(),
  observeRunStream: vi.fn(),
  rerunRun: vi.fn(),
}));

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return { ...actual, ...api };
});

import { RunDetailPage } from './RunDetailPage';

const run = (runId: string, status: string) => ({
  runId,
  scenario: 'payment-timeout-after-commit@1.0.0',
  targetProfile: 'resilient-consumer',
  seed: 42001,
  status,
  problemCode: null,
  startedAt: null,
  finishedAt: null,
  createdAt: '2026-08-13T00:00:00Z',
});

const report = {
  runId: 'old-run',
  scenario: { name: 'payment-timeout-after-commit', version: '1.0.0', contentHash: 'hash' },
  targetProfile: 'resilient-consumer',
  seed: 42001,
  status: 'PASSED',
  summary: {
    stepsPassed: 1,
    stepsFailed: 0,
    providerCommits: 1,
    callbackDeliveries: 1,
    callbackOmitted: 0,
    probeAttempts: 1,
  },
  failures: [],
  limitations: [],
};

function renderPage(runId = 'old-run') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/runs/${runId}`]}>
        <Routes><Route path="/runs/:runId" element={<RunDetailPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** Run 상태 전환과 mutation/error UI가 현재 run에 결속되는지 검증한다. */
describe('RunDetailPage', () => {
  beforeEach(() => {
    api.cancelRun.mockReset().mockResolvedValue(undefined);
    api.downloadReport.mockReset().mockResolvedValue(undefined);
    api.getReport.mockReset().mockResolvedValue(report);
    api.getRun.mockReset().mockResolvedValue(run('old-run', 'PASSED'));
    api.getSteps.mockReset().mockResolvedValue([]);
    api.getTimeline.mockReset().mockResolvedValue([]);
    api.observeRunStream.mockReset().mockResolvedValue(undefined);
    api.rerunRun.mockReset().mockResolvedValue('new-run');
  });

  it('shows rerun only for a terminal run', async () => {
    renderPage();
    expect(await screen.findByRole('button', { name: '재실행' })).toBeInTheDocument();
    expect(screen.getByRole('status', { name: 'PASSED' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '취소' })).not.toBeInTheDocument();
  });

  it('derives terminal state again after navigating to a rerun', async () => {
    api.getRun.mockImplementation(async (runId: string) => (
      runId === 'old-run' ? run('old-run', 'PASSED') : run('new-run', 'RUNNING')
    ));
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '재실행' }));
    expect(await screen.findByText('Run new-run')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: '취소' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '재실행' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Report' })).not.toBeInTheDocument();
  });

  it('shows a fixed safe mutation error and allows retry', async () => {
    api.rerunRun.mockRejectedValueOnce(new Error('raw-secret-diagnostic'));
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '재실행' }));
    const alert = await screen.findByTestId('run-mutation-error');
    expect(alert).toHaveTextContent('현재 상태를 확인하세요.');
    expect(alert).not.toHaveTextContent('raw-secret-diagnostic');
    await waitFor(() => expect(screen.getByRole('button', { name: '재실행' })).toBeEnabled());
  });

  it('reuses the rerun intent key after an ambiguous failure', async () => {
    api.rerunRun.mockRejectedValueOnce(new Error('response lost')).mockResolvedValueOnce('new-run');
    renderPage();
    const rerun = await screen.findByRole('button', { name: '재실행' });
    fireEvent.click(rerun);
    expect(await screen.findByTestId('run-mutation-error')).toBeInTheDocument();
    fireEvent.click(rerun);
    await waitFor(() => expect(api.rerunRun).toHaveBeenCalledTimes(2));
    expect(api.rerunRun.mock.calls[0][1]?.idempotencyKey).toBeTruthy();
    expect(api.rerunRun.mock.calls[1][1]?.idempotencyKey).toBe(api.rerunRun.mock.calls[0][1]?.idempotencyKey);
  });

  it('distinguishes steps, timeline, and report failures', async () => {
    api.getSteps.mockRejectedValue(new Error('steps raw'));
    api.getTimeline.mockRejectedValue(new Error('timeline raw'));
    api.getReport.mockRejectedValue(new Error('report raw'));
    renderPage();
    expect(await screen.findByTestId('steps-error')).toBeInTheDocument();
    expect(await screen.findByTestId('timeline-error')).toBeInTheDocument();
    expect(await screen.findByTestId('report-error')).toBeInTheDocument();
    expect(screen.queryByText(/steps raw|timeline raw|report raw/u)).not.toBeInTheDocument();
  });
});
