import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom/vitest';
import { describe, expect, it, vi } from 'vitest';
import { ImportPage, MAX_ARTIFACT_BYTES, decodeArtifactUtf8, utf8Size } from './ImportPage';
import { jsonResponse } from './testResponses';

/** Import 입력 크기·UTF-8·단일 요청·안전한 결과 상태를 검증한다. */
describe('ImportPage', () => {
  it('calculates UTF-8 bytes and rejects malformed UTF-8', () => {
    expect(utf8Size('가')).toBe(3);
    expect(() => decodeArtifactUtf8(Uint8Array.from([0xc3, 0x28]).buffer)).toThrow();
  });

  it('blocks YAML larger than 512 KiB before a request', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    render(<ImportPage />);
    fireEvent.change(screen.getByLabelText('Artifact YAML'), {
      target: { value: 'a'.repeat(MAX_ARTIFACT_BYTES + 1) },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Validate' }));
    expect(await screen.findByTestId('import-error')).toHaveTextContent('512 KiB');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('allows only one request and announces safe success', async () => {
    let finish: ((response: Response) => void) | undefined;
    const pending = new Promise<Response>((resolve) => { finish = resolve; });
    const fetchMock = vi.fn(() => pending);
    vi.stubGlobal('fetch', fetchMock);
    render(<ImportPage />);
    fireEvent.change(screen.getByLabelText('Artifact YAML'), { target: { value: 'kind: Scenario' } });
    const validate = screen.getByRole('button', { name: 'Validate' });
    fireEvent.click(validate);
    fireEvent.click(validate);
    expect(await screen.findByRole('button', { name: '검증 중…' })).toBeDisabled();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    finish?.(jsonResponse({ valid: true, internal: 'not-shown' }));
    expect(await screen.findByTestId('import-result')).toHaveTextContent('검증에 성공했습니다.');
    expect(screen.queryByText(/not-shown/u)).not.toBeInTheDocument();
  });

  it('does not expose a server diagnostic on failure', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({}, 400, 'application/problem+json')));
    render(<ImportPage />);
    fireEvent.change(screen.getByLabelText('Artifact YAML'), { target: { value: 'kind: Scenario' } });
    fireEvent.click(screen.getByRole('button', { name: 'Import' }));
    const alert = await screen.findByTestId('import-error');
    expect(alert).toHaveTextContent('Import에 실패했습니다.');
    expect(alert).not.toHaveTextContent('HTTP 400');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Import' })).toBeEnabled());
  });
});
