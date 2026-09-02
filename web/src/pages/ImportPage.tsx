import { useRef, useState } from 'react';
import { importArtifact, validateArtifact } from '../api/client';

/** 브라우저에서 허용하는 Artifact YAML 최대 UTF-8 크기다. */
export const MAX_ARTIFACT_BYTES = 512 * 1024;

/** 문자열의 실제 UTF-8 전송 크기를 계산한다. */
export function utf8Size(value: string): number {
  return new TextEncoder().encode(value).byteLength;
}

/** 잘못된 UTF-8을 대체 문자로 숨기지 않고 거부한다. */
export function decodeArtifactUtf8(bytes: ArrayBuffer): string {
  return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
}

type Operation = 'validate' | 'import';
type Feedback = { kind: 'success' | 'error'; message: string } | null;

/**
 * Artifact YAML을 검증·import하는 화면이다.
 *
 * <p>책임: 붙여넣기와 UTF-8 파일 입력을 동일한 512 KiB 경계에서 처리하고,
 * 요청 원문이나 서버 진단을 화면에 재노출하지 않으면서 단일 요청 상태를 알린다.</p>
 */
export function ImportPage() {
  const [yaml, setYaml] = useState('');
  const [pending, setPending] = useState<Operation | null>(null);
  const [feedback, setFeedback] = useState<Feedback>(null);
  const requestActive = useRef(false);

  const execute = async (operation: Operation) => {
    if (requestActive.current) {
      return;
    }
    if (yaml.trim() === '') {
      setFeedback({ kind: 'error', message: '검증할 YAML 문서를 입력하세요.' });
      return;
    }
    if (utf8Size(yaml) > MAX_ARTIFACT_BYTES) {
      setFeedback({ kind: 'error', message: 'Artifact YAML은 UTF-8 기준 512 KiB 이하여야 합니다.' });
      return;
    }

    requestActive.current = true;
    setPending(operation);
    setFeedback(null);
    try {
      if (operation === 'validate') {
        await validateArtifact(yaml);
        setFeedback({ kind: 'success', message: '검증에 성공했습니다.' });
      } else {
        await importArtifact(yaml);
        setFeedback({ kind: 'success', message: 'Import에 성공했습니다.' });
      }
    } catch {
      setFeedback({
        kind: 'error',
        message: operation === 'validate'
          ? '검증에 실패했습니다. 문서와 서버 연결을 확인하세요.'
          : 'Import에 실패했습니다. 문서와 서버 연결을 확인하세요.',
      });
    } finally {
      requestActive.current = false;
      setPending(null);
    }
  };

  const loadFile = async (file: File | undefined) => {
    if (!file) {
      return;
    }
    setFeedback(null);
    if (file.size > MAX_ARTIFACT_BYTES) {
      setFeedback({ kind: 'error', message: 'Artifact YAML 파일은 512 KiB 이하여야 합니다.' });
      return;
    }
    try {
      const decoded = decodeArtifactUtf8(await file.arrayBuffer());
      if (utf8Size(decoded) > MAX_ARTIFACT_BYTES) {
        setFeedback({ kind: 'error', message: 'Artifact YAML 파일은 512 KiB 이하여야 합니다.' });
        return;
      }
      setYaml(decoded);
      setFeedback({ kind: 'success', message: 'UTF-8 YAML 파일을 불러왔습니다.' });
    } catch {
      setFeedback({ kind: 'error', message: '파일을 UTF-8 YAML로 읽을 수 없습니다.' });
    }
  };

  return (
    <div aria-busy={pending !== null}>
      <h2>Artifact Import</h2>
      <p>YAML 문서를 붙여넣거나 파일을 선택한 뒤 검증하세요. secret 값은 문서에 쓸 수 없습니다.</p>
      <label className="file-field">
        UTF-8 YAML 파일 (최대 512 KiB)
        <input
          type="file"
          accept=".yaml,.yml,text/yaml,text/plain"
          disabled={pending !== null}
          onChange={(event) => void loadFile(event.target.files?.[0])}
        />
      </label>
      <label htmlFor="artifact-yaml">Artifact YAML</label>
      <textarea
        id="artifact-yaml"
        aria-label="Artifact YAML"
        aria-describedby="artifact-size-limit"
        rows={18}
        value={yaml}
        disabled={pending !== null}
        onChange={(event) => {
          setYaml(event.target.value);
          setFeedback(null);
        }}
        placeholder={'apiVersion: outcomeprobe.io/v1alpha1\nkind: Scenario\n...'}
      />
      <p id="artifact-size-limit" className="field-hint">UTF-8 기준 512 KiB 이하만 전송합니다.</p>
      <div className="actions">
        <button type="button" disabled={pending !== null} onClick={() => void execute('validate')}>
          {pending === 'validate' ? '검증 중…' : 'Validate'}
        </button>
        <button type="button" disabled={pending !== null} onClick={() => void execute('import')}>
          {pending === 'import' ? 'Import 중…' : 'Import'}
        </button>
      </div>
      {feedback ? (
        <p
          className={feedback.kind === 'error' ? 'error import-result' : 'success import-result'}
          role={feedback.kind === 'error' ? 'alert' : 'status'}
          aria-live={feedback.kind === 'error' ? 'assertive' : 'polite'}
          data-testid={feedback.kind === 'error' ? 'import-error' : 'import-result'}
        >
          {feedback.message}
        </p>
      ) : null}
    </div>
  );
}
