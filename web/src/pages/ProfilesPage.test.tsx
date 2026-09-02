import { fireEvent, render, screen } from '@testing-library/react';
import '@testing-library/jest-dom/vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';
import { ProfilesPage } from './ProfilesPage';
import { jsonResponse } from './testResponses';

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><ProfilesPage /></QueryClientProvider>);
}

/** Profile 목록의 fresh empty와 안전한 서버 오류를 검증한다. */
describe('ProfilesPage', () => {
  it('shows bootstrap guidance when profiles are empty', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse([])));
    renderPage();
    expect(await screen.findByText('아직 등록된 Target Profile이 없습니다')).toBeInTheDocument();
    expect(screen.getByText(/Docker bootstrap/u)).toBeInTheDocument();
  });

  it('does not expose a raw error', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('secret-diagnostic'); }));
    renderPage();
    const alert = await screen.findByTestId('profiles-server-error');
    expect(alert).not.toHaveTextContent('secret-diagnostic');
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
  });

  it('runs one safe connection preflight and renders boolean checks', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') {
        return jsonResponse({
          name: 'realtime-track-compose',
          passed: true,
          message: 'internal-detail-is-not-rendered',
          checks: { hostAllowed: true, secretRefsResolved: true },
        });
      }
      return jsonResponse([{
        kind: 'TARGET_PROFILE',
        name: 'realtime-track-compose',
        version: '1.0.0',
        displayName: 'Realtime Track Compose Target',
        contentHash: 'a'.repeat(64),
        builtIn: false,
        importedAt: '2026-08-13T00:00:00Z',
      }]);
    });
    vi.stubGlobal('fetch', fetchMock);
    renderPage();

    const button = await screen.findByRole('button', { name: '연결 사전검사' });
    fireEvent.click(button);
    fireEvent.click(button);

    expect(await screen.findByRole('status')).toHaveTextContent('연결 준비 완료');
    expect(screen.getByRole('status')).toHaveTextContent('hostAllowed: PASS');
    expect(screen.getByRole('status')).not.toHaveTextContent('internal-detail-is-not-rendered');
    expect(fetchMock.mock.calls.filter((call) => call[1]?.method === 'POST')).toHaveLength(1);
  });

  it('shows a fixed error and no remote diagnostic when preflight fails to load', async () => {
    vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') {
        throw new Error('target-secret-remote-diagnostic');
      }
      return jsonResponse([{
        kind: 'TARGET_PROFILE', name: 'realtime-track-compose', version: '1.0.0',
        displayName: 'Realtime Track', contentHash: 'b'.repeat(64), builtIn: false,
        importedAt: '2026-08-13T00:00:00Z',
      }]);
    }));
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: '연결 사전검사' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('연결 사전검사를 완료하지 못했습니다');
    expect(alert).not.toHaveTextContent('target-secret-remote-diagnostic');
  });
});
