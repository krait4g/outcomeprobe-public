/**
 * Run·Step 상태를 아이콘과 텍스트로 함께 표시하는 badge다.
 *
 * <p>책임: 상태를 색상만으로 구분하지 않고 텍스트·기호를 함께 보여줘
 * 접근성 요구를 만족시킨다.</p>
 */

/** 상태 badge 속성이다. */
export interface StatusBadgeProps {
  /** 표시할 상태 문자열 */
  status: string;
}

/**
 * 상태별 표시 기호와 클래스를 결정한다.
 *
 * @param status Run·Step 상태
 * @returns 기호와 설명
 */
export function statusPresentation(status: string): { symbol: string; label: string } {
  switch (status) {
    case 'PASSED':
      return { symbol: '✓', label: 'PASSED' };
    case 'FAILED':
      return { symbol: '✗', label: 'FAILED' };
    case 'ERROR':
      return { symbol: '!', label: 'ERROR' };
    case 'RUNNING':
    case 'PREPARING':
    case 'ASSERTING':
    case 'QUEUED':
      return { symbol: '…', label: status };
    case 'CANCELLED':
      return { symbol: '⊘', label: 'CANCELLED' };
    default:
      return { symbol: '·', label: status };
  }
}

/**
 * 상태 badge를 렌더링한다.
 *
 * @param props badge 속성
 * @returns badge 엘리먼트
 */
export function StatusBadge({ status }: StatusBadgeProps) {
  const presentation = statusPresentation(status);
  return (
    <span
      className={`status-badge status-${status.toLowerCase()}`}
      role="status"
      aria-label={presentation.label}
    >
      <span aria-hidden="true">{presentation.symbol}</span> {presentation.label}
    </span>
  );
}
