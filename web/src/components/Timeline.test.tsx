import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import {
  applyFilters,
  collectDistinct,
  EMPTY_FILTER,
  groupByLane,
  laneSourceClass,
  Timeline,
  type TimelineFilterState,
} from './Timeline';
import type { TimelineEvent } from '../api/client';

/**
 * Timeline lane 그룹화와 필터 규칙을 검증한다.
 *
 * <p>검출 가능한 결함: source가 다른 Evidence가 같은 lane에 섞여 Provider commit과
 * 응답 장애의 순서를 읽을 수 없게 되는 문제, 그리고 필터가 의도와 다르게 사건을
 * 누락·혼입해 실패 원인 추적을 방해하는 문제.</p>
 */

/** 테스트용 TimelineEvent를 생성한다. */
const event = (
  sequence: number,
  source: string,
  type: string,
  overrides: Partial<TimelineEvent> = {},
): TimelineEvent => ({
  sequence,
  source,
  type,
  stepId: null,
  correlationId: null,
  resourceType: null,
  resourceId: null,
  attributes: {},
  logicalTimeMs: null,
  payloadSummary: null,
  bodyHash: null,
  redactionApplied: false,
  observedAt: '2026-07-26T00:00:00Z',
  ...overrides,
});

describe('groupByLane', () => {
  it('groups events by source lane', () => {
    const lanes = groupByLane([
      event(1, 'ENGINE', 'RUN_STARTED'),
      event(2, 'EMULATOR', 'PROVIDER_OPERATION_COMMITTED'),
      event(3, 'EMULATOR', 'PROVIDER_RESPONSE_FAULT_APPLIED'),
      event(4, 'PROBE', 'PROBE_ATTEMPTED'),
    ]);

    expect(lanes.get('Scenario Engine')?.length).toBe(1);
    expect(lanes.get('Provider API·Truth')?.length).toBe(2);
    expect(lanes.get('State Probe')?.length).toBe(1);
  });

  it('returns empty map for no events', () => {
    expect(groupByLane([]).size).toBe(0);
  });
});

describe('Timeline CSP presentation', () => {
  it('uses allow-listed classes without inline styles', () => {
    expect(laneSourceClass('ENGINE')).toBe('lane-source-engine');
    expect(laneSourceClass('UNKNOWN value')).toBe('lane-source-other');
    const { container } = render(<Timeline events={[event(1, 'ENGINE', 'RUN_STARTED')]} />);
    expect(container.querySelector('[style]')).toBeNull();
    expect(container.querySelector('.timeline-lane.lane-source-engine')).not.toBeNull();
    expect(container.querySelector('.lane-chip.lane-source-engine')).not.toBeNull();
  });
});

describe('applyFilters', () => {
  const events = [
    event(1, 'ENGINE', 'RUN_STARTED', { stepId: 'start' }),
    event(2, 'EMULATOR', 'PROVIDER_OPERATION_COMMITTED', { stepId: 'expect', resourceId: 'pay-1', correlationId: 'corr-A' }),
    event(3, 'EMULATOR', 'PROVIDER_RESPONSE_FAULT_APPLIED', { stepId: 'expect', resourceId: 'pay-1', correlationId: 'corr-A' }),
    event(4, 'DELIVERY', 'CALLBACK_DELIVERY_SUCCEEDED', { resourceId: 'pay-1', correlationId: 'corr-B' }),
    event(5, 'PROBE', 'PROBE_ASSERTION_FAILED', { stepId: 'assert', resourceId: 'pay-2' }),
    event(6, 'DELIVERY', 'CALLBACK_DELIVERY_OUTCOME_UNKNOWN', { resourceId: 'pay-3' }),
  ];

  const withFilter = (partial: Partial<TimelineFilterState>): TimelineFilterState => ({
    ...EMPTY_FILTER,
    ...partial,
  });

  it('returns all events when filter is empty', () => {
    expect(applyFilters(events, EMPTY_FILTER)).toHaveLength(6);
  });

  it('filters by selected sources (lane)', () => {
    const result = applyFilters(events, withFilter({ sources: ['EMULATOR'] }));
    expect(result.map((e) => e.sequence)).toEqual([2, 3]);
  });

  it('filters by exact event type', () => {
    const result = applyFilters(events, withFilter({ type: 'PROVIDER_RESPONSE_FAULT_APPLIED' }));
    expect(result.map((e) => e.sequence)).toEqual([3]);
  });

  it('filters by exact step id', () => {
    const result = applyFilters(events, withFilter({ stepId: 'expect' }));
    expect(result.map((e) => e.sequence)).toEqual([2, 3]);
  });

  it('filters by resource id substring (case-insensitive)', () => {
    const result = applyFilters(events, withFilter({ resourceId: 'PAY-2' }));
    expect(result.map((e) => e.sequence)).toEqual([5]);
  });

  it('filters by correlation id substring', () => {
    const result = applyFilters(events, withFilter({ correlationId: 'corr-a' }));
    expect(result.map((e) => e.sequence)).toEqual([2, 3]);
  });

  it('shows only failure events when failuresOnly is set', () => {
    const result = applyFilters(events, withFilter({ failuresOnly: true }));
    expect(result.map((e) => e.type)).toEqual([
      'PROVIDER_RESPONSE_FAULT_APPLIED',
      'PROBE_ASSERTION_FAILED',
      'CALLBACK_DELIVERY_OUTCOME_UNKNOWN',
    ]);
  });

  it('combines dimensions with AND semantics', () => {
    const result = applyFilters(events, withFilter({ sources: ['EMULATOR'], failuresOnly: true }));
    expect(result.map((e) => e.sequence)).toEqual([3]);
  });

  it('treats whitespace-only text as no filter', () => {
    expect(applyFilters(events, withFilter({ resourceId: '   ' }))).toHaveLength(6);
  });
});

describe('collectDistinct', () => {
  it('returns sorted distinct non-null values', () => {
    const events = [
      event(1, 'ENGINE', 'RUN_STARTED', { stepId: 'b-step' }),
      event(2, 'ENGINE', 'RUN_STARTED', { stepId: 'a-step' }),
      event(3, 'ENGINE', 'RUN_STARTED', { stepId: 'a-step' }),
      event(4, 'ENGINE', 'RUN_STARTED'),
    ];
    expect(collectDistinct(events, (e) => e.stepId)).toEqual(['a-step', 'b-step']);
  });
});
