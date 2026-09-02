import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { expect, it, describe, vi, beforeEach } from 'vitest';
import { App } from './App';
import { clearControlApiToken } from './auth/controlToken';

/**
 * 최상위 레이아웃과 라우팅 렌더링 계약을 검증한다.
 *
 * <p>검출 가능한 결함: 라우터·Query provider 배선 오류로 화면이 렌더링되지
 * 않거나 제품 정체성 문구가 사라지는 문제.</p>
 */
describe('App', () => {
  beforeEach(() => {
    // 저장 토큰 확인과 Overview 조회를 실제 Response stream 형태로 stub한다.
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      const body = url.endsWith('/settings')
        ? { mode: 'local-test-workbench' }
        : [];
      return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }));
  });

  it('renders product name and navigation after stored token verification', async () => {
    const queryClient = new QueryClient();
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/']}>
          <App />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await waitFor(() => expect(screen.getByRole('navigation')).toBeDefined());
    expect(screen.getByRole('heading', { level: 1, name: 'OutcomeProbe' })).toBeDefined();
  });

  it('hides the product UI until the operator token is verified', async () => {
    clearControlApiToken();
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      expect(new Headers(init?.headers).get('Authorization')).toBe(
        'Bearer browser-login-control-token-0123456789abcdef',
      );
      return new Response('{"mode":"local-test-workbench"}', {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    });
    vi.stubGlobal('fetch', fetchMock);
    const queryClient = new QueryClient();
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/']}>
          <App />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(screen.queryByRole('navigation')).toBeNull();
    fireEvent.change(screen.getByLabelText('운영자 토큰'), {
      target: { value: 'browser-login-control-token-0123456789abcdef' },
    });
    fireEvent.click(screen.getByRole('button', { name: '인증' }));

    await waitFor(() => expect(screen.getByRole('navigation')).toBeDefined());
    expect(document.body.textContent).not.toContain('browser-login-control-token-0123456789abcdef');
  });

  it('removes a submitted token from storage and the password input after a network failure', async () => {
    clearControlApiToken();
    vi.stubGlobal('fetch', vi.fn(async () => {
      throw new TypeError('network failure');
    }));
    const queryClient = new QueryClient();
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/']}>
          <App />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    const rawToken = 'browser-network-token-0123456789abcdef';
    const input = screen.getByLabelText('운영자 토큰') as HTMLInputElement;
    fireEvent.change(input, { target: { value: rawToken } });
    fireEvent.click(screen.getByRole('button', { name: '인증' }));

    await waitFor(() => expect(screen.getByRole('alert')).toBeDefined());
    expect(input.value).toBe('');
    expect(document.body.textContent).not.toContain(rawToken);
    expect(window.sessionStorage.getItem('outcomeprobe.control-api-token')).toBeNull();
  });
});
