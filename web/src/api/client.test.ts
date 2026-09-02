import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ApiError,
  apiFetch,
  assertArtifactRequest,
  ControlAuthenticationError,
  createRun,
  observeRunStream,
  rerunRun,
  shouldRetryApiQuery,
} from './client';
import {
  CONTROL_TOKEN_SESSION_KEY,
  clearControlApiToken,
  setControlApiToken,
} from '../auth/controlToken';

/** 중앙 API client의 Bearer header와 비노출 실패 계약을 검증한다. */
describe('Control API client authentication', () => {
  const token = 'client-test-control-token-0123456789abcdef';

  beforeEach(() => setControlApiToken(token));

  it('adds the same bearer token to every request and overrides caller authorization', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      const headers = new Headers(init?.headers);
      expect(headers.get('Authorization')).toBe(`Bearer ${token}`);
      return new Response('{"ok":true}', {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    });
    vi.stubGlobal('fetch', fetchMock);

    await expect(apiFetch('/settings', {
      headers: { Authorization: 'Bearer caller-controlled-value' },
    })).resolves.toEqual({ ok: true });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('turns 401 into a redacted authentication error and deletes the rejected token', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('reflected-secret-body', { status: 401 })));

    let caught: unknown;
    try {
      await apiFetch('/settings');
    } catch (error) {
      caught = error;
    }

    expect(caught).toBeInstanceOf(ControlAuthenticationError);
    expect(String(caught)).not.toContain(token);
    expect(String(caught)).not.toContain('reflected-secret-body');
    expect(window.sessionStorage.getItem(CONTROL_TOKEN_SESSION_KEY)).toBeNull();
  });

  it('turns 403 into the same redacted boundary while preserving status', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      `forbidden ${token}`,
      { status: 403 },
    )));

    await expect(apiFetch('/settings')).rejects.toMatchObject({
      status: 403,
      category: 'authentication',
    });
    expect(window.sessionStorage.getItem(CONTROL_TOKEN_SESSION_KEY)).toBeNull();
  });

  it('fails before network access when the tab has no token', async () => {
    clearControlApiToken();
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(apiFetch('/settings')).rejects.toBeInstanceOf(ControlAuthenticationError);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('reuses an explicit mutation intent header and omits an undefined seed', async () => {
    const requests: Array<{ headers: Headers; body: string }> = [];
    vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      requests.push({
        headers: new Headers(init?.headers),
        body: String(init?.body),
      });
      return new Response('{"runId":"1d46928b-f1a0-4ccd-a143-c4b0bacb03cd"}', {
        status: 202,
        headers: { 'Content-Type': 'application/json' },
      });
    }));
    const options = { idempotencyKey: 'web-intent-key-0123456789' };

    await createRun('scenario', '1.0.0', 'profile', undefined, {}, options);
    await createRun('scenario', '1.0.0', 'profile', undefined, {}, options);

    expect(requests).toHaveLength(2);
    expect(requests.map((request) => request.headers.get('Idempotency-Key')))
      .toEqual([options.idempotencyKey, options.idempotencyKey]);
    expect(requests.map((request) => JSON.parse(request.body))).toEqual([
      { scenarioRef: { name: 'scenario', version: '1.0.0' }, targetProfile: 'profile', variables: {} },
      { scenarioRef: { name: 'scenario', version: '1.0.0' }, targetProfile: 'profile', variables: {} },
    ]);
  });

  it('never retries a failed mutation inside the API client', async () => {
    const fetchMock = vi.fn(async () => {
      throw new TypeError('network with possible secret body');
    });
    vi.stubGlobal('fetch', fetchMock);

    await expect(rerunRun(
      '1d46928b-f1a0-4ccd-a143-c4b0bacb03cd',
      { idempotencyKey: 'web-rerun-intent-0123456789' },
    )).rejects.toMatchObject({ category: 'network', retryable: false });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('keeps only allowlisted problem fields and retries only transient GET failures once', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      code: 'FIELD_INVALID',
      field: 'variables[paymentId]',
      detail: `reflected ${token}`,
      stack: token,
    }), {
      status: 422,
      headers: { 'Content-Type': 'application/problem+json' },
    })));

    let caught: unknown;
    try {
      await apiFetch('/settings');
    } catch (error) {
      caught = error;
    }
    expect(caught).toBeInstanceOf(ApiError);
    expect(caught).toMatchObject({ code: 'FIELD_INVALID', field: 'variables[paymentId]' });
    expect(String(caught)).not.toContain(token);
    expect(shouldRetryApiQuery(0, new ApiError('safe', 'server', true, 503))).toBe(true);
    expect(shouldRetryApiQuery(1, new ApiError('safe', 'server', true, 503))).toBe(false);
    expect(shouldRetryApiQuery(0, caught)).toBe(false);
  });

  it('applies the Artifact cap to actual UTF-8 bytes before fetch', () => {
    expect(() => assertArtifactRequest('가'.repeat(174_762) + 'aa')).not.toThrow();
    expect(() => assertArtifactRequest('가'.repeat(174_763)))
      .toThrowError(/512 KiB/u);
  });

  it('rejects a non-SSE response before reading reflected content', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(`reflected ${token}`, {
      status: 200,
      headers: { 'Content-Type': 'text/plain' },
    })));

    await expect(observeRunStream(
      '1d46928b-f1a0-4ccd-a143-c4b0bacb03cd',
      new AbortController().signal,
      vi.fn(),
    )).rejects.toMatchObject({ code: 'SSE_CONTENT_TYPE_INVALID' });
  });

  it('cancels an oversized SSE frame without invoking the callback', async () => {
    let cancelled = false;
    const onEvent = vi.fn();
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(`event: evidence-appended\ndata: ${'x'.repeat(65_536)}`));
      },
      cancel() {
        cancelled = true;
      },
    });
    vi.stubGlobal('fetch', vi.fn(async () => new Response(body, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    })));

    await expect(observeRunStream(
      '1d46928b-f1a0-4ccd-a143-c4b0bacb03cd',
      new AbortController().signal,
      onEvent,
    )).rejects.toMatchObject({ code: 'SSE_FRAME_TOO_LARGE' });
    expect(onEvent).not.toHaveBeenCalled();
    expect(cancelled).toBe(true);
  });
});
