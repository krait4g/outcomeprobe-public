import type { ReactNode } from 'react';

/** 비동기 화면 상태가 노출할 최소 속성이다. */
interface AsyncStateProps {
  /** 사용자에게 보여줄 안전한 안내다. */
  message: string;
  /** 접근성·회귀 테스트용 안정적인 식별자다. */
  testId?: string;
}

/** 목록이나 상세 데이터를 기다리는 상태를 보조기기에 알린다. */
export function LoadingState({ message, testId }: AsyncStateProps) {
  return <p role="status" aria-live="polite" data-testid={testId}>{message}</p>;
}

/** 서버 원문을 노출하지 않는 조회 실패와 명시적인 재시도 동작을 제공한다. */
export function QueryErrorState({
  message,
  onRetry,
  testId,
}: AsyncStateProps & { onRetry: () => void }) {
  return (
    <div className="async-state error-state" role="alert" data-testid={testId}>
      <p className="error">{message}</p>
      <button type="button" className="secondary-action" onClick={onRetry}>다시 시도</button>
    </div>
  );
}

/** 초기 bootstrap 전의 정상적인 빈 목록을 장애와 구분해 안내한다. */
export function BootstrapEmptyState({
  message,
  children,
  testId,
}: AsyncStateProps & { children?: ReactNode }) {
  return (
    <section className="async-state empty-state" aria-live="polite" data-testid={testId}>
      <p className="empty">{message}</p>
      <p>Docker bootstrap을 실행하면 대표 Artifact가 등록됩니다.</p>
      {children}
    </section>
  );
}
