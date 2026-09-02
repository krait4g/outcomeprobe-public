import { useMemo, useState } from 'react';
import type { TimelineEvent } from '../api/client';

/**
 * Evidence Timeline을 source별 lane으로 그룹화하고 필터로 좁혀 표시한다.
 *
 * <p>책임: Engine·Target·Emulator·Delivery·Probe·Assertion 흐름을 한 화면에서
 * 읽어 Provider commit과 응답 장애, Callback 전달, 수렴 판정의 순서를 추적하게 한다.
 * lane·event type·step·resource·correlation·실패 전용 필터로 긴 Timeline에서
 * 관심 사건만 골라 볼 수 있다.</p>
 * <p>외부에서 오해하기 쉬운 한계: logical time이 아닌 observed time 기준 표시다.
 * 같은 seed라도 실행 시각은 달라질 수 있다.</p>
 */

/** source 값을 lane 이름으로 변환한다. */
export const LANE_NAMES: Record<string, string> = {
  ENGINE: 'Scenario Engine',
  TARGET: 'Target Application',
  EMULATOR: 'Provider API·Truth',
  DELIVERY: 'Callback Delivery',
  PROBE: 'State Probe',
  ASSERTION: 'Assertion',
};

/** 서버 source를 임의 CSS 주입 없이 고정된 강조색 class로 변환한다. */
export function laneSourceClass(source: string): string {
  return Object.hasOwn(LANE_NAMES, source)
    ? `lane-source-${source.toLowerCase()}`
    : 'lane-source-other';
}

/** "실패 전용" 필터가 대상으로 삼는 부정적 사건 유형 집합이다. */
export const FAILURE_EVENT_TYPES: ReadonlySet<string> = new Set([
  'STEP_FAILED',
  'RUN_FAILED',
  'RUN_ERROR',
  'TARGET_REQUEST_FAILED',
  'PROVIDER_REQUEST_REJECTED',
  'PROVIDER_RESPONSE_FAULT_APPLIED',
  'CALLBACK_DELIVERY_FAILED',
  'CALLBACK_DELIVERY_DEAD',
  'CALLBACK_DELIVERY_OUTCOME_UNKNOWN',
  'PROBE_ASSERTION_FAILED',
  'EVIDENCE_ASSERTION_FAILED',
]);

/** Timeline 필터 상태다. 빈 문자열·빈 배열은 해당 차원을 필터링하지 않음을 뜻한다. */
export interface TimelineFilterState {
  /** 선택된 source 목록, 비어 있으면 전체 lane 표시 */
  sources: string[];
  /** 일치시킬 event type, ''이면 전체 */
  type: string;
  /** 일치시킬 step 식별자, ''이면 전체 */
  stepId: string;
  /** 부분 일치시킬 resource 식별자, ''이면 전체 */
  resourceId: string;
  /** 부분 일치시킬 correlation 식별자, ''이면 전체 */
  correlationId: string;
  /** true면 FAILURE_EVENT_TYPES에 속한 사건만 표시 */
  failuresOnly: boolean;
}

/** 모든 차원이 해제된 기본 필터 상태다. */
export const EMPTY_FILTER: TimelineFilterState = {
  sources: [],
  type: '',
  stepId: '',
  resourceId: '',
  correlationId: '',
  failuresOnly: false,
};

/**
 * 필터 상태에 따라 Timeline 이벤트를 좁힌다.
 *
 * <p>순수 함수로 유지해 단위 테스트로 필터 조합을 검증한다. 모든 차원은 AND로
 * 결합되며, 각 차원 내부에서만 여유 조건(빈 값=전체)이 적용된다.</p>
 *
 * @param events 전체 Timeline 이벤트
 * @param filter 적용할 필터 상태
 * @returns 조건을 모두 만족하는 이벤트 배열(입력 순서 유지)
 */
export function applyFilters(events: TimelineEvent[], filter: TimelineFilterState): TimelineEvent[] {
  const resource = filter.resourceId.trim().toLowerCase();
  const correlation = filter.correlationId.trim().toLowerCase();
  return events.filter((event) => {
    if (filter.sources.length > 0 && !filter.sources.includes(event.source)) {
      return false;
    }
    if (filter.type !== '' && event.type !== filter.type) {
      return false;
    }
    if (filter.stepId !== '' && event.stepId !== filter.stepId) {
      return false;
    }
    if (resource !== '' && !(event.resourceId ?? '').toLowerCase().includes(resource)) {
      return false;
    }
    if (correlation !== '' && !(event.correlationId ?? '').toLowerCase().includes(correlation)) {
      return false;
    }
    if (filter.failuresOnly && !FAILURE_EVENT_TYPES.has(event.type)) {
      return false;
    }
    return true;
  });
}

/**
 * Timeline 이벤트를 source별 lane으로 그룹화한다.
 *
 * @param events Timeline 이벤트 목록
 * @returns lane 이름 → 이벤트 목록 map
 */
export function groupByLane(events: TimelineEvent[]): Map<string, TimelineEvent[]> {
  const lanes = new Map<string, TimelineEvent[]>();
  for (const event of events) {
    const lane = LANE_NAMES[event.source] ?? event.source;
    const bucket = lanes.get(lane) ?? [];
    bucket.push(event);
    lanes.set(lane, bucket);
  }
  return lanes;
}

/**
 * 이벤트 목록에서 지정한 추출자로 중복 없는 값 목록을 만든다.
 *
 * @param events Timeline 이벤트 목록
 * @param extract 이벤트에서 문자열 값을 꺼내는 함수
 * @returns 정렬된 고유 값 배열(null·빈 값 제외)
 */
export function collectDistinct(events: TimelineEvent[], extract: (e: TimelineEvent) => string | null): string[] {
  const values = new Set<string>();
  for (const event of events) {
    const value = extract(event);
    if (value !== null && value !== '') {
      values.add(value);
    }
  }
  return Array.from(values).sort();
}

/** Timeline 필터 변경 핸들러 시그니처다. */
export type TimelineFilterChange = (next: TimelineFilterState) => void;

/** Timeline 필터 바 컴포넌트 속성이다. */
export interface TimelineFilterBarProps {
  /** 현재 필터 상태 */
  filter: TimelineFilterState;
  /** 필터 변경 시 호출되는 핸들러 */
  onChange: TimelineFilterChange;
  /** 필터 옵션을 채우는 데 사용할 전체 이벤트 */
  events: TimelineEvent[];
  /** 필터 적용 후 남은 이벤트 수 */
  visibleCount: number;
}

/**
 * lane chip·type/step 선택·resource/correlation 검색·실패 전용 토글을 제공하는 필터 바를 렌더링한다.
 *
 * @param props 필터 바 속성
 * @returns 필터 바 엘리먼트
 */
export function TimelineFilterBar({ filter, onChange, events, visibleCount }: TimelineFilterBarProps) {
  const types = useMemo(() => collectDistinct(events, (e) => e.type), [events]);
  const steps = useMemo(() => collectDistinct(events, (e) => e.stepId), [events]);
  const sourceCounts = useMemo(() => {
    const counts = new Map<string, number>();
    for (const event of events) {
      counts.set(event.source, (counts.get(event.source) ?? 0) + 1);
    }
    return counts;
  }, [events]);

  const toggleSource = (source: string) => {
    const active = filter.sources.includes(source);
    onChange({
      ...filter,
      sources: active ? filter.sources.filter((s) => s !== source) : [...filter.sources, source],
    });
  };

  const isFiltered =
    filter.sources.length > 0 ||
    filter.type !== '' ||
    filter.stepId !== '' ||
    filter.resourceId !== '' ||
    filter.correlationId !== '' ||
    filter.failuresOnly;

  return (
    <div className="timeline-filters" role="group" aria-label="Timeline 필터">
      <div className="filter-lanes">
        {Object.keys(LANE_NAMES).map((source) => {
          const active = filter.sources.includes(source);
          return (
            <button
              key={source}
              type="button"
              className={`lane-chip ${laneSourceClass(source)}${active ? ' active' : ''}`}
              aria-pressed={active}
              onClick={() => toggleSource(source)}
            >
              {LANE_NAMES[source]}
              <span className="chip-count">{sourceCounts.get(source) ?? 0}</span>
            </button>
          );
        })}
      </div>

      <div className="filter-controls">
        <label>
          Event type
          <select value={filter.type} onChange={(e) => onChange({ ...filter, type: e.target.value })}>
            <option value="">전체</option>
            {types.map((type) => (
              <option key={type} value={type}>{type}</option>
            ))}
          </select>
        </label>

        <label>
          Step
          <select value={filter.stepId} onChange={(e) => onChange({ ...filter, stepId: e.target.value })}>
            <option value="">전체</option>
            {steps.map((step) => (
              <option key={step} value={step}>{step}</option>
            ))}
          </select>
        </label>

        <label>
          Resource
          <input
            type="search"
            placeholder="resource id 검색"
            value={filter.resourceId}
            onChange={(e) => onChange({ ...filter, resourceId: e.target.value })}
          />
        </label>

        <label>
          Correlation
          <input
            type="search"
            placeholder="correlation id 검색"
            value={filter.correlationId}
            onChange={(e) => onChange({ ...filter, correlationId: e.target.value })}
          />
        </label>

        <label className="filter-toggle">
          <input
            type="checkbox"
            checked={filter.failuresOnly}
            onChange={(e) => onChange({ ...filter, failuresOnly: e.target.checked })}
          />
          실패만 보기
        </label>

        <span className="filter-result" aria-live="polite">
          {visibleCount}/{events.length}건
        </span>

        {isFiltered ? (
          <button type="button" className="filter-reset" onClick={() => onChange(EMPTY_FILTER)}>
            초기화
          </button>
        ) : null}
      </div>
    </div>
  );
}

/** Timeline 컴포넌트 속성이다. */
export interface TimelineProps {
  /** 표시할 Timeline 이벤트 */
  events: TimelineEvent[];
}

/**
 * Timeline 필터 바와 lane들을 렌더링한다.
 *
 * <p>필터 상태는 컴포넌트 내부에서 관리하며, Run 상세 페이지는 이벤트만 전달한다.
 * 각 사건 카드는 observed time·correlation id를 함께 보여 cross-lane 추적을 돕는다.</p>
 *
 * @param props Timeline 속성
 * @returns Timeline 엘리먼트
 */
export function Timeline({ events }: TimelineProps) {
  const [filter, setFilter] = useState<TimelineFilterState>(EMPTY_FILTER);
  const filtered = useMemo(() => applyFilters(events, filter), [events, filter]);
  const lanes = groupByLane(filtered);

  if (events.length === 0) {
    return <p className="empty">아직 기록된 Evidence가 없습니다.</p>;
  }

  return (
    <div className="timeline">
      <TimelineFilterBar filter={filter} onChange={setFilter} events={events} visibleCount={filtered.length} />

      {filtered.length === 0 ? (
        <p className="empty">조건에 맞는 Evidence가 없습니다. 필터를 조정하거나 초기화하세요.</p>
      ) : (
        Array.from(lanes.entries()).map(([lane, laneEvents]) => {
          const source = laneEvents[0]?.source ?? '';
          return (
            <section key={lane} className={`timeline-lane ${laneSourceClass(source)}`}>
              <h4>{lane}</h4>
              <ol>
                {laneEvents.map((event) => (
                  <li key={event.sequence} className={`event event-${event.type.toLowerCase()}`} tabIndex={0}>
                    <span className="event-seq">#{event.sequence}</span>
                    <span className="event-type">{event.type}</span>
                    {event.stepId ? <span className="event-step">[{event.stepId}]</span> : null}
                    {event.resourceId ? <span className="event-resource">{event.resourceId}</span> : null}
                    {event.correlationId ? <span className="event-correlation">↔ {event.correlationId}</span> : null}
                    <span className="event-time">{formatObservedAt(event.observedAt)}</span>
                    <span className="event-attrs">{JSON.stringify(event.attributes)}</span>
                  </li>
                ))}
              </ol>
            </section>
          );
        })
      )}
    </div>
  );
}

/**
 * observed timestamp를 읽기 짧은 시각 문자열로 변환한다.
 *
 * @param observedAt ISO-8601 시각 문자열
 * @returns HH:mm:ss.SSS 형식 문자열, 해석 실패 시 원본
 */
export function formatObservedAt(observedAt: string): string {
  const date = new Date(observedAt);
  if (Number.isNaN(date.getTime())) {
    return observedAt;
  }
  return date.toISOString().slice(11, 23);
}
