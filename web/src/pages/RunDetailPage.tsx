import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient, useMutation } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import {
  cancelRun,
  createIdempotencyKey,
  downloadReport,
  getReport,
  getRun,
  getSteps,
  getTimeline,
  observeRunStream,
  rerunRun,
  type TimelineEvent,
} from '../api/client';
import { StatusBadge } from '../components/StatusBadge';
import { Timeline } from '../components/Timeline';
import { LoadingState, QueryErrorState } from '../components/AsyncState';

/**
 * Run 상세 화면: 요약·실패 설명·Provider Truth·Step·Timeline·report 다운로드를 제공한다.
 *
 * <p>책임: Run의 판정 근거를 Evidence Timeline으로 투명하게 보여준다.
 * 활성 Run은 SSE로, terminal Run은 조회로 Timeline을 갱신한다.</p>
 * <p>핵심 불변식: 실패한 Run은 가장 먼저 실패 설명을 표시해 결과를 숨기지 않는다.
 * terminal Run은 polling을 중지해 불필요한 대역폭 소모를 막는다.</p>
 */
export function RunDetailPage() {
  const { runId = '' } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [downloadError, setDownloadError] = useState('');
  const [downloadPending, setDownloadPending] = useState(false);
  const downloadActive = useRef(false);
  const rerunIntentKey = useRef<string | null>(null);

  // SSE가 닿지 않는 환경에서도 상태가 갱신되도록 짧은 polling을 병행한다.
  // terminal Run은 더 이상 변경되지 않으므로 polling을 중지해 대역폭을 절약한다.
  const runQuery = useQuery({
    queryKey: ['run', runId],
    queryFn: () => getRun(runId),
    refetchInterval: (query) => isTerminal(query.state.data?.status ?? '') ? false : 2000,
  });
  const stepsQuery = useQuery({
    queryKey: ['steps', runId],
    queryFn: () => getSteps(runId),
    refetchInterval: () => isTerminal(runQuery.data?.status ?? '') ? false : 2000,
  });
  const timelineQuery = useQuery({
    queryKey: ['timeline', runId],
    queryFn: () => getTimeline(runId),
    refetchInterval: () => isTerminal(runQuery.data?.status ?? '') ? false : 2000,
  });
  const reportQuery = useQuery({
    queryKey: ['report', runId],
    queryFn: () => getReport(runId),
    enabled: runQuery.data !== undefined && isTerminal(runQuery.data.status),
  });

  const cancelMutation = useMutation({
    mutationFn: () => cancelRun(runId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['run', runId] });
    },
  });
  const rerunMutation = useMutation({
    mutationFn: () => {
      rerunIntentKey.current ??= createIdempotencyKey();
      return rerunRun(runId, { idempotencyKey: rerunIntentKey.current });
    },
    onSuccess: (newRunId) => {
      navigate(`/runs/${newRunId}`);
    },
  });

  // EventSource는 인증 header를 지원하지 않으므로 fetch stream으로 SSE를 구독한다.
  // 연결이 종료돼도 위의 짧은 polling이 상태 수렴을 보장한다.
  useEffect(() => {
    const status = runQuery.data?.status ?? '';
    if (status === '' || isTerminal(status)) {
      return;
    }
    const controller = new AbortController();
    const invalidate = () => {
      void queryClient.invalidateQueries({ queryKey: ['timeline', runId] });
      void queryClient.invalidateQueries({ queryKey: ['run', runId] });
      void queryClient.invalidateQueries({ queryKey: ['steps', runId] });
    };
    void observeRunStream(runId, controller.signal, (eventName) => {
      if (eventName === 'evidence-appended') {
        invalidate();
      }
      if (eventName === 'run-status') {
        invalidate();
        void queryClient.invalidateQueries({ queryKey: ['report', runId] });
        controller.abort();
      }
    }).catch((error: unknown) => {
      if (!(error instanceof DOMException && error.name === 'AbortError')) {
        // 401은 중앙 client가 인증 화면으로 전환하며, 그 밖의 단절은 polling이 복구한다.
      }
    });
    return () => controller.abort();
  }, [runId, runQuery.data?.status, queryClient]);

  const run = runQuery.data;
  // 조회 실패 시 이전 report cache를 최신 결과처럼 제시하지 않는다.
  const report = reportQuery.isError ? undefined : reportQuery.data;
  const timeline = timelineQuery.data ?? [];
  const terminal = isTerminal(run?.status ?? '');
  const mutationError = cancelMutation.isError || rerunMutation.isError;

  /** 인증 header를 유지한 채 report를 내려받고 실패를 화면에 남긴다. */
  async function handleDownload(kind: 'json' | 'junit' | 'bundle') {
    if (downloadActive.current) {
      return;
    }
    downloadActive.current = true;
    setDownloadPending(true);
    setDownloadError('');
    try {
      await downloadReport(runId, kind);
    } catch {
      setDownloadError('Report 다운로드가 실패했습니다. 서버 연결을 확인하세요.');
    } finally {
      downloadActive.current = false;
      setDownloadPending(false);
    }
  }

  if (runQuery.isLoading) {
    return <div><h2>Run {runId.slice(0, 8)}</h2><p>불러오는 중...</p></div>;
  }
  if (runQuery.isError) {
    return (
      <div>
        <h2>Run {runId.slice(0, 8)}</h2>
        <QueryErrorState
          message="Run을 불러올 수 없습니다. 서버 연결을 확인하세요."
          onRetry={() => void runQuery.refetch()}
          testId="run-server-error"
        />
      </div>
    );
  }

  return (
    <div>
      <h2>Run {runId.slice(0, 8)}</h2>
      {run ? (
        <section className="run-header">
          <p>Scenario: <strong>{run.scenario}</strong></p>
          <p>Target: {run.targetProfile} · Seed: {run.seed}</p>
          <p>
            상태: <StatusBadge status={run.status} />
            {run.problemCode ? <> · 문제 코드: {run.problemCode}</> : null}
          </p>
          <div className="actions">
            {!terminal ? (
              <button type="button" onClick={() => cancelMutation.mutate()} disabled={cancelMutation.isPending}>
                {cancelMutation.isPending ? '취소 중...' : '취소'}
              </button>
            ) : null}
            {terminal ? (
              <button
                type="button"
                onClick={() => {
                  if (!rerunMutation.isError) rerunIntentKey.current = createIdempotencyKey();
                  rerunMutation.mutate();
                }}
                disabled={rerunMutation.isPending}
              >
                {rerunMutation.isPending ? '재실행 중...' : '재실행'}
              </button>
            ) : null}
          </div>
          {mutationError ? (
            <p className="error" role="alert" data-testid="run-mutation-error">
              Run 요청에 실패했습니다. 서버 연결과 현재 상태를 확인하세요.
            </p>
          ) : null}
          {report ? (
            <p className="run-summary">
              Provider commit {report.summary.providerCommits}회 · Callback 전달{' '}
              {report.summary.callbackDeliveries}회 · 생략 {report.summary.callbackOmitted}회 · Probe 시도{' '}
              {report.summary.probeAttempts}회 · Step 통과 {report.summary.stepsPassed}/실패{' '}
              {report.summary.stepsFailed}
            </p>
          ) : null}
        </section>
      ) : null}

      {report?.explanation ? (
        <section className="explanation">
          <h3>실패 설명</h3>
          <p>{report.explanation}</p>
        </section>
      ) : null}
      {terminal && reportQuery.isLoading ? <LoadingState message="Report를 불러오는 중…" /> : null}
      {terminal && reportQuery.isError ? (
        <QueryErrorState
          message="Report를 불러오지 못했습니다. 실행 결과는 유지됩니다."
          onRetry={() => void reportQuery.refetch()}
          testId="report-error"
        />
      ) : null}

      {!timelineQuery.isError ? <ProviderTruthPanel events={timeline} /> : null}

      {terminal ? (
        <section>
          <h3>Report</h3>
          <div className="actions">
            <button type="button" disabled={downloadPending} onClick={() => void handleDownload('json')}>report.json</button>
            <button type="button" disabled={downloadPending} onClick={() => void handleDownload('junit')}>junit.xml</button>
            <button type="button" disabled={downloadPending} onClick={() => void handleDownload('bundle')}>bundle.zip</button>
          </div>
          {downloadPending ? <LoadingState message="Report 다운로드를 준비하는 중…" /> : null}
          {downloadError ? <p className="error" role="alert">{downloadError}</p> : null}
        </section>
      ) : null}

      <section>
        <h3>Steps</h3>
        {stepsQuery.isLoading ? <LoadingState message="Step을 불러오는 중…" /> : null}
        {stepsQuery.isError ? (
          <QueryErrorState
            message="Step을 불러오지 못했습니다."
            onRetry={() => void stepsQuery.refetch()}
            testId="steps-error"
          />
        ) : null}
        {stepsQuery.isSuccess && (stepsQuery.data ?? []).length === 0 ? <p className="empty">아직 기록된 Step이 없습니다.</p> : null}
        {stepsQuery.isSuccess && (stepsQuery.data ?? []).length > 0 ? (
          <div className="table-scroll" tabIndex={0} aria-label="Run Step 표">
            <table>
              <thead>
                <tr><th>#</th><th>Step</th><th>유형</th><th>상태</th><th>시도</th><th>문제 코드</th></tr>
              </thead>
              <tbody>
                {(stepsQuery.data ?? []).map((step) => (
                  <tr key={step.stepId}>
                    <td>{step.ordinal}</td>
                    <td>{step.stepId}</td>
                    <td>{step.type}</td>
                    <td><StatusBadge status={step.status} /></td>
                    <td>{step.attemptCount}</td>
                    <td>{step.problemCode ?? ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </section>

      <section>
        <h3>Evidence Timeline</h3>
        {timelineQuery.isLoading ? <LoadingState message="Evidence Timeline을 불러오는 중…" /> : null}
        {timelineQuery.isError ? (
          <QueryErrorState
            message="Evidence Timeline을 불러오지 못했습니다."
            onRetry={() => void timelineQuery.refetch()}
            testId="timeline-error"
          />
        ) : null}
        {timelineQuery.isSuccess ? <Timeline events={timeline} /> : null}
      </section>
    </div>
  );
}

/**
 * Provider commit·조회 가시성 이벤트를 분리해 보여주는 패널이다.
 *
 * <p>책임: Provider truth(commit)와 Target에 보인 상태(query visible state)를
 * 나란히 보여 "commit 됐지만 응답은 유실된" 상황을 시각화한다.</p>
 *
 * @param props Timeline 이벤트 목록
 * @returns Provider Truth 패널 엘리먼트
 */
export function ProviderTruthPanel({ events }: { events: TimelineEvent[] }) {
  const commits = events.filter((event) => event.type === 'PROVIDER_OPERATION_COMMITTED');
  const faults = events.filter((event) => event.type === 'PROVIDER_RESPONSE_FAULT_APPLIED');
  const visibles = events.filter((event) => event.type === 'PROVIDER_VISIBLE_STATE_RETURNED');
  if (commits.length === 0 && visibles.length === 0) {
    return null;
  }
  return (
    <section className="provider-truth">
      <h3>Provider Truth &amp; Visibility</h3>
      <div className="table-scroll" tabIndex={0} aria-label="Provider Truth 표"><table>
        <thead>
          <tr><th>구분</th><th>operation</th><th>상태</th><th>세부</th></tr>
        </thead>
        <tbody>
          {commits.map((event) => (
            <tr key={`commit-${event.sequence}`}>
              <td>commit</td>
              <td>{event.attributes.operation ?? ''}</td>
              <td>{event.attributes.state ?? ''}</td>
              <td>occurrence {event.attributes.occurrence ?? ''}</td>
            </tr>
          ))}
          {faults.map((event) => (
            <tr key={`fault-${event.sequence}`}>
              <td>response fault</td>
              <td>{event.attributes.operation ?? ''}</td>
              <td>{event.attributes.faultType ?? ''}</td>
              <td>exposed {event.attributes.exposedStatus ?? ''}</td>
            </tr>
          ))}
          {visibles.map((event) => (
            <tr key={`visible-${event.sequence}`}>
              <td>query visible</td>
              <td>{event.attributes.operation ?? ''}</td>
              <td>{event.attributes.visibleState ?? ''}</td>
              <td>truth {event.attributes.canonicalState ?? ''}</td>
            </tr>
          ))}
        </tbody>
      </table></div>
    </section>
  );
}

/**
 * Run 상태가 terminal인지 판단한다.
 *
 * @param status Run 상태 문자열
 * @returns terminal이면 true
 */
export function isTerminal(status: string): boolean {
  return ['PASSED', 'FAILED', 'ERROR', 'CANCELLED'].includes(status);
}
