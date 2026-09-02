import { render, screen } from '@testing-library/react';
import '@testing-library/jest-dom/vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ScenariosPage } from './ScenariosPage';
import { jsonResponse } from './testResponses';

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><MemoryRouter><ScenariosPage /></MemoryRouter></QueryClientProvider>);
}

/** 공개 두 Scenario의 발견성과 fresh/error 분리를 검증한다. */
describe('ScenariosPage', () => {
  it('shows bootstrap guidance for an empty inventory', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse([])));
    renderPage();
    expect(await screen.findByText('아직 등록된 Scenario가 없습니다')).toBeInTheDocument();
    expect(screen.getByText(/Docker bootstrap/u)).toBeInTheDocument();
  });

  it('links both representative public scenarios to detail', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse([
        { name: 'payment-timeout-after-commit', version: '1.0.0', displayName: 'timeout', builtIn: false, importedAt: '' },
        { name: 'payment-success-callback-duplicate', version: '1.0.0', displayName: 'duplicate', builtIn: false, importedAt: '' },
    ])));
    renderPage();
    const timeoutLink = await screen.findByRole('link', { name: 'payment-timeout-after-commit' });
    const duplicateLink = screen.getByRole('link', { name: 'payment-success-callback-duplicate' });
    expect(timeoutLink).toHaveAttribute('href', '/scenarios/payment-timeout-after-commit/1.0.0');
    expect(duplicateLink).toHaveAttribute('href', '/scenarios/payment-success-callback-duplicate/1.0.0');
  });

  it('shows a safe retry state for server failure', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('raw-secret'); }));
    renderPage();
    const alert = await screen.findByTestId('scenarios-server-error');
    expect(alert).toHaveTextContent('서버 연결을 확인하세요.');
    expect(alert).not.toHaveTextContent('raw-secret');
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
  });
});
