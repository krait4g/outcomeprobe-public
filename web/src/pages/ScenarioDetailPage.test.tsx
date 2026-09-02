import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import '@testing-library/jest-dom/vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { parseSeedOverride, parseVariableOverrides, ScenarioDetailPage } from './ScenarioDetailPage';
import { jsonResponse, textResponse } from './testResponses';

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/scenarios/payment-success-callback-duplicate/1.0.0']}>
        <Routes><Route path="/scenarios/:name/:version" element={<ScenarioDetailPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function successFetch(url: string, init?: RequestInit): Promise<Response> {
  if (init?.method === 'POST') {
    return Promise.resolve(jsonResponse({ runId: '10000000-0000-4000-8000-000000000002' }));
  }
  if (url.includes('/raw')) {
    return Promise.resolve(textResponse('metadata:\n  seed: 42002'));
  }
  return Promise.resolve(jsonResponse([
      { name: 'vulnerable-consumer', version: '1.0.0' },
      { name: 'resilient-consumer', version: '1.0.0' },
  ]));
}

/** seed/변수 검증과 dialog 접근성·요청 payload를 검증한다. */
describe('ScenarioDetailPage helpers', () => {
  it('keeps canonical seed for blank and validates overrides', () => {
    expect(parseSeedOverride('')).toEqual({ ok: true });
    expect(parseSeedOverride('42002')).toEqual({ ok: true, seed: 42002 });
    expect(parseSeedOverride('-1').ok).toBe(false);
    expect(parseSeedOverride('NaN').ok).toBe(false);
  });

  it('strictly parses variables', () => {
    expect(parseVariableOverrides('paymentId=pay=1')).toEqual({ ok: true, variables: { paymentId: 'pay=1' } });
    expect(parseVariableOverrides('missing').ok).toBe(false);
    expect(parseVariableOverrides('a=1\na=2').ok).toBe(false);
    expect(parseVariableOverrides('a=x\u0001').ok).toBe(false);
  });
});

describe('ScenarioDetailPage UI', () => {
  it('omits seed by default, traps a single request, and supports Escape', async () => {
    let complete: ((response: Response) => void) | undefined;
    const pending = new Promise<Response>((resolve) => { complete = resolve; });
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      if (init?.method === 'POST') return pending;
      return successFetch(url, init);
    });
    vi.stubGlobal('fetch', fetchMock);
    renderPage();
    const open = await screen.findByRole('button', { name: 'Run Scenario' });
    await waitFor(() => expect(open).toBeEnabled());
    fireEvent.click(open);
    const dialog = screen.getByRole('dialog', { name: 'Run 설정' });
    expect(dialog).toBeInTheDocument();
    const run = within(dialog).getByRole('button', { name: 'Run Scenario' });
    fireEvent.click(run);
    fireEvent.click(run);
    expect(await screen.findByRole('button', { name: '실행 중…' })).toBeDisabled();
    const posts = fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST');
    expect(posts).toHaveLength(1);
    const body = JSON.parse(String(posts[0][1]?.body)) as Record<string, unknown>;
    expect(body).not.toHaveProperty('seed');
    const firstHeaders = new Headers(posts[0][1]?.headers);
    const idempotencyKey = firstHeaders.get('Idempotency-Key');
    expect(idempotencyKey).toMatch(/^op-web-[0-9a-f-]+$/u);
    complete?.(jsonResponse({ runId: '10000000-0000-4000-8000-000000000002' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: '실행 중…' })).not.toBeInTheDocument());
  });

  it('closes with Escape and restores focus', async () => {
    vi.stubGlobal('fetch', vi.fn(successFetch));
    renderPage();
    const open = await screen.findByRole('button', { name: 'Run Scenario' });
    await waitFor(() => expect(open).toBeEnabled());
    open.focus();
    fireEvent.click(open);
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(document.activeElement).toBe(open);
  });

  it('blocks malformed variables without a network request', async () => {
    const fetchMock = vi.fn(successFetch);
    vi.stubGlobal('fetch', fetchMock);
    renderPage();
    const open = await screen.findByRole('button', { name: 'Run Scenario' });
    await waitFor(() => expect(open).toBeEnabled());
    fireEvent.click(open);
    fireEvent.change(screen.getByLabelText(/변수 override/u), { target: { value: 'not-valid' } });
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Run Scenario' }));
    expect(await screen.findByTestId('run-start-error')).toHaveTextContent('key=value');
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0);
  });

  it('reuses the intent key after an ambiguous failure in the same dialog', async () => {
    let attempt = 0;
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        attempt += 1;
        if (attempt === 1) return Promise.reject(new Error('response lost'));
        return Promise.resolve(jsonResponse({ runId: '10000000-0000-4000-8000-000000000003' }));
      }
      return successFetch(url, init);
    });
    vi.stubGlobal('fetch', fetchMock);
    renderPage();
    const open = await screen.findByRole('button', { name: 'Run Scenario' });
    await waitFor(() => expect(open).toBeEnabled());
    fireEvent.click(open);
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Run Scenario' }));
    expect(await screen.findByTestId('run-start-error')).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Run Scenario' }));
    await waitFor(() => expect(attempt).toBe(2));
    const posts = fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST');
    const keys = posts.map(([, init]) => new Headers(init?.headers).get('Idempotency-Key'));
    expect(keys[0]).toBeTruthy();
    expect(keys[1]).toBe(keys[0]);
  });

  it('creates a new intent key when input changes after failure', async () => {
    let attempt = 0;
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        attempt += 1;
        if (attempt === 1) return Promise.reject(new Error('response lost'));
        return Promise.resolve(jsonResponse({ runId: '10000000-0000-4000-8000-000000000004' }));
      }
      return successFetch(url, init);
    });
    vi.stubGlobal('fetch', fetchMock);
    renderPage();
    const open = await screen.findByRole('button', { name: 'Run Scenario' });
    await waitFor(() => expect(open).toBeEnabled());
    fireEvent.click(open);
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Run Scenario' }));
    expect(await screen.findByTestId('run-start-error')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/Seed override/u), { target: { value: '42003' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Run Scenario' }));
    await waitFor(() => expect(attempt).toBe(2));
    const posts = fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST');
    const keys = posts.map(([, init]) => new Headers(init?.headers).get('Idempotency-Key'));
    expect(keys[0]).toBeTruthy();
    expect(keys[1]).toBeTruthy();
    expect(keys[1]).not.toBe(keys[0]);
  });
});
