/**
 * OutcomeProbe Control API client와 공유 타입이다.
 *
 * <p>책임: UI 전역이 동일한 fetch 규칙과 타입으로 서버와 통신하게 한다.
 * 응답 실패는 예외로 던져 TanStack Query가 error 상태로 처리하게 한다.</p>
 * <p>핵심 불변식: 모든 Control API 요청은 탭 단위 운영자 Bearer 토큰을 header로
 * 전송하며 URL·오류 메시지·브라우저 로그에는 토큰을 남기지 않는다.</p>
 */

import { getControlApiToken, rejectControlApiToken } from '../auth/controlToken';

/** Run 요약 정보다. */
export interface RunSummary {
  runId: string;
  scenario: string;
  targetProfile: string;
  seed: number;
  status: string;
  problemCode: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  createdAt: string;
}

/** Overview 대시보드 통계다. */
export interface OverviewStats {
  passed24h: number;
  failed24h: number;
  error24h: number;
  activeRuns: number;
  staleRuns: number;
  mostFailedScenario: string | null;
}

/** Step 실행 요약 정보다. */
export interface StepSummary {
  stepId: string;
  ordinal: number;
  type: string;
  status: string;
  attemptCount: number;
  problemCode: string | null;
}

/** Timeline Evidence 항목이다. */
export interface TimelineEvent {
  sequence: number;
  type: string;
  source: string;
  stepId: string | null;
  correlationId: string | null;
  resourceType: string | null;
  resourceId: string | null;
  attributes: Record<string, string>;
  logicalTimeMs: number | null;
  payloadSummary: string | null;
  bodyHash: string | null;
  redactionApplied: boolean;
  observedAt: string;
}

/** 서버 실행 중 설정이다. */
export interface ServerSettings {
  version: string;
  dslVersions: string[];
  mode: string;
  storage: string;
  productionGateway: boolean;
  retentionDays: number;
  maxConcurrentRuns: number;
  controlApiAuthenticationRequired: boolean;
  emulatorTokenRequired: boolean;
  telemetryEnabled: boolean;
  telemetryNote: string;
}

/** Artifact 요약 정보다. */
export interface ArtifactSummary {
  kind: string;
  name: string;
  displayName: string;
  version: string;
  contentHash: string;
  builtIn: boolean;
  importedAt: string;
}

/** secret 원문 없이 반환되는 Target Profile 연결 사전검사 결과다. */
export interface TargetProfilePreflight {
  name: string;
  passed: boolean;
  message: string;
  checks: Record<string, unknown>;
}

/** Run JSON report 문서다. */
export interface RunReport {
  runId: string;
  scenario: { name: string; version: string; contentHash: string };
  targetProfile: string;
  seed: number;
  status: string;
  summary: {
    stepsPassed: number;
    stepsFailed: number;
    providerCommits: number;
    callbackDeliveries: number;
    callbackOmitted: number;
    probeAttempts: number;
  };
  failures: Array<{ type: string; stepId: string | null; attributes: Record<string, string> }>;
  explanation?: string;
  problemCode?: string;
  limitations: string[];
}

const BASE = '/api/v1';

/** 기본 요청 timeout(ms). 서버가 응답하지 않으면 UI가 무한 대기하지 않도록 차단한다. */
const DEFAULT_TIMEOUT_MS = 30_000;
const SSE_IDLE_TIMEOUT_MS = 15_000;
const SSE_ABSOLUTE_TIMEOUT_MS = 11 * 60_000;
const MAX_JSON_BYTES = 16 * 1024 * 1024;
const MAX_PROBLEM_BYTES = 16 * 1024;
const MAX_RAW_ARTIFACT_BYTES = 512 * 1024;
const MAX_DOWNLOAD_BYTES = 64 * 1024 * 1024;
const MAX_SSE_FRAME_BYTES = 64 * 1024;

/** 운영자에게 안전하게 노출할 수 있는 API 실패 분류다. */
export type ApiErrorCategory =
  | 'authentication'
  | 'cancelled'
  | 'capacity'
  | 'conflict'
  | 'network'
  | 'not-found'
  | 'protocol'
  | 'server'
  | 'timeout'
  | 'validation';

/**
 * 원격 body 원문을 보존하지 않는 Control API 오류다.
 *
 * <p>서버가 반환한 RFC 9457 문서에서는 allow-list 형식의 code와 field만 남긴다.
 * detail·title·stack·요청 body는 DOM과 로그로 전달하지 않는다.</p>
 */
export class ApiError extends Error {
  /** HTTP 상태와 고정 분류를 가진 안전한 오류를 만든다. */
  constructor(
    message: string,
    readonly category: ApiErrorCategory,
    readonly retryable: boolean,
    readonly status?: number,
    readonly code?: string,
    readonly field?: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/** 인증이 없거나 서버가 현재 토큰을 거부했을 때 사용하는 안전한 오류다. */
export class ControlAuthenticationError extends ApiError {
  /** 토큰 원문 없이 재인증 필요 상태를 만든다. */
  constructor(status: 401 | 403 = 401) {
    super('운영자 인증이 필요합니다. 토큰을 다시 입력하세요.', 'authentication', false, status,
      'CONTROL_API_UNAUTHORIZED');
    this.name = 'ControlAuthenticationError';
  }
}

/** 페이지가 알 수 없는 예외 원문을 렌더링하지 않도록 안전한 메시지만 고른다. */
export function safeErrorMessage(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

/** GET Query가 일시적인 network·timeout·429·5xx에만 한 번 재시도하게 한다. */
export function shouldRetryApiQuery(failureCount: number, error: unknown): boolean {
  return failureCount < 1 && error instanceof ApiError && error.retryable;
}

/** Web 사용자 의도 하나를 응답 유실 재시도와 결속할 임의 key를 만든다. */
export function createIdempotencyKey(): string {
  return `op-web-${globalThis.crypto.randomUUID()}`;
}

/**
 * 현재 토큰을 Authorization header에 결속한다.
 *
 * @param headers 호출자가 요청한 기존 header
 * @returns 운영자 인증이 추가된 새 Headers
 */
function authorizedHeaders(headers?: HeadersInit): Headers {
  const token = getControlApiToken();
  if (token === null) {
    throw new ControlAuthenticationError();
  }
  const result = new Headers(headers);
  // 호출자가 임의 Authorization 값을 주입해 중앙 인증 경계를 우회하지 못하게 덮어쓴다.
  result.set('Authorization', `Bearer ${token}`);
  return result;
}

/**
 * Control API를 호출하고 인증 실패를 전역 경계에 전달한다.
 *
 * @param path /api/v1 아래 상대 경로
 * @param init fetch 옵션
 * @returns HTTP 응답
 */
async function controlFetch<T>(
  path: string,
  init: RequestInit | undefined,
  read: (response: Response) => Promise<T>,
  timeoutMs = DEFAULT_TIMEOUT_MS,
): Promise<T> {
  const method = (init?.method ?? 'GET').toUpperCase();
  const boundary = createRequestBoundary(init?.signal, timeoutMs);
  try {
    const response = await fetch(BASE + path, {
      ...init,
      signal: boundary.signal,
      headers: authorizedHeaders(init?.headers),
    });
    if (response.status === 401 || response.status === 403) {
      rejectControlApiToken();
      throw new ControlAuthenticationError(response.status);
    }
    return await read(response);
  } catch (error) {
    if (error instanceof ApiError) {
      throw error;
    }
    if (isAbortError(error) || boundary.signal.aborted) {
      if (boundary.didTimeout()) {
        throw new ApiError('API 요청 시간이 초과되었습니다.', 'timeout', method === 'GET');
      }
      throw new DOMException('요청이 취소되었습니다.', 'AbortError');
    }
    throw new ApiError(
      '서버에 연결할 수 없습니다. OutcomeProbe 서버가 실행 중인지 확인하세요.',
      'network',
      method === 'GET',
    );
  } finally {
    boundary.cleanup();
  }
}

/** 오류가 AbortSignal 취소에서 발생했는지 판정한다. */
function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}

/** caller 취소와 제품 timeout을 하나의 signal로 결속하고 listener를 정리한다. */
function createRequestBoundary(callerSignal: AbortSignal | null | undefined, timeoutMs: number) {
  const controller = new AbortController();
  let timedOut = false;
  const abortFromCaller = () => controller.abort(callerSignal?.reason);
  if (callerSignal?.aborted) {
    abortFromCaller();
  } else {
    callerSignal?.addEventListener('abort', abortFromCaller, { once: true });
  }
  const timer = window.setTimeout(() => {
    timedOut = true;
    controller.abort(new DOMException('요청 시간이 초과되었습니다.', 'TimeoutError'));
  }, timeoutMs);
  return {
    signal: controller.signal,
    didTimeout: () => timedOut,
    cleanup: () => {
      window.clearTimeout(timer);
      callerSignal?.removeEventListener('abort', abortFromCaller);
    },
  };
}

/** Content-Length와 실제 stream byte를 모두 확인해 chunked 응답 우회를 막는다. */
async function readBoundedBytes(response: Response, maximumBytes: number): Promise<Uint8Array> {
  const contentLength = response.headers.get('content-length');
  if (contentLength !== null) {
    const declared = Number(contentLength);
    if (!Number.isSafeInteger(declared) || declared < 0 || declared > maximumBytes) {
      throw new ApiError('응답 크기가 허용 범위를 초과했습니다.', 'protocol', false,
        response.status, 'RESPONSE_TOO_LARGE');
    }
  }
  if (!response.body) {
    return new Uint8Array();
  }
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) {
        break;
      }
      total += chunk.value.byteLength;
      if (total > maximumBytes) {
        throw new ApiError('응답 크기가 허용 범위를 초과했습니다.', 'protocol', false,
          response.status, 'RESPONSE_TOO_LARGE');
      }
      chunks.push(chunk.value);
    }
  } finally {
    await reader.cancel().catch(() => undefined);
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return bytes;
}

/** 제한된 UTF-8 JSON 응답을 파싱한다. */
async function readJson<T>(response: Response, maximumBytes = MAX_JSON_BYTES): Promise<T> {
  const contentType = response.headers.get('content-type') ?? '';
  if (!contentType.toLowerCase().includes('application/json')
      && !contentType.toLowerCase().includes('application/problem+json')) {
    throw new ApiError('예상하지 못한 응답 형식입니다.', 'protocol', false, response.status,
      'RESPONSE_CONTENT_TYPE_INVALID');
  }
  try {
    return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(
      await readBoundedBytes(response, maximumBytes),
    )) as T;
  } catch (error) {
    if (error instanceof ApiError) {
      throw error;
    }
    throw new ApiError('응답 JSON 형식이 올바르지 않습니다.', 'protocol', false, response.status,
      'RESPONSE_JSON_INVALID');
  }
}

/** HTTP 실패에서 allow-list 진단만 남기고 body 원문은 폐기한다. */
async function httpError(response: Response): Promise<ApiError> {
  let code: string | undefined;
  let field: string | undefined;
  const contentType = (response.headers.get('content-type') ?? '').toLowerCase();
  if (contentType.includes('application/problem+json')) {
    try {
      const problem = await readJson<Record<string, unknown>>(response, MAX_PROBLEM_BYTES);
      if (typeof problem.code === 'string' && /^[A-Z][A-Z0-9_]{0,127}$/u.test(problem.code)) {
        code = problem.code;
      }
      if (typeof problem.field === 'string'
          && /^[A-Za-z0-9_.\[\]-]{1,256}$/u.test(problem.field)) {
        field = problem.field;
      }
    } catch {
      // 실패 body는 진단 보조 수단일 뿐이므로 malformed·oversized 원문을 다시 노출하지 않는다.
    }
  }
  const status = response.status;
  if (status === 400 || status === 413 || status === 422) {
    return new ApiError(
      status === 413 ? '요청 크기가 허용 범위를 초과했습니다.' : '입력값을 확인하세요.',
      'validation', false, status, code, field,
    );
  }
  if (status === 404) {
    return new ApiError('요청한 리소스를 찾을 수 없습니다.', 'not-found', false, status, code, field);
  }
  if (status === 409) {
    return new ApiError('이미 처리된 요청과 충돌했습니다.', 'conflict', false, status, code, field);
  }
  if (status === 429) {
    return new ApiError('서버 실행 용량이 가득 찼습니다. 잠시 뒤 다시 시도하세요.',
      'capacity', true, status, code, field);
  }
  if (status >= 500) {
    return new ApiError('서버 내부 오류가 발생했습니다.', 'server', true, status, code, field);
  }
  return new ApiError(`API 요청이 실패했습니다. HTTP ${status}`, 'protocol', false, status, code, field);
}

/** path segment를 예상 형식으로 검증한 뒤 URL encoding한다. */
function runPath(runId: string): string {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu.test(runId)) {
    throw new ApiError('Run 식별자 형식이 올바르지 않습니다.', 'validation', false, undefined,
      'RUN_ID_INVALID', 'runId');
  }
  return encodeURIComponent(runId);
}

/** server가 반환한 runId도 navigation 전에 같은 UUID 계약으로 검증한다. */
function validatedRunId(value: unknown): string {
  if (typeof value !== 'string') {
    throw new ApiError('Run 생성 응답 형식이 올바르지 않습니다.', 'protocol', false, undefined,
      'RUN_RESPONSE_INVALID');
  }
  runPath(value);
  return value;
}

/**
 * JSON 응답을 검증하고 파싱한다.
 *
 * <p>timeout·네트워크 오류·비-JSON 응답을 구분된 메시지로 던진다.</p>
 *
 * @param path API 경로
 * @param init fetch 옵션
 * @returns 파싱된 JSON
 */
export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  return controlFetch(path, init, async (response) => {
    if (!response.ok) {
      throw await httpError(response);
    }
    return readJson<T>(response);
  });
}

/**
 * Run 목록을 조회한다.
 *
 * @param runId 조회할 Run 식별자
 * @returns Run 요약
 */
export function getRun(runId: string): Promise<RunSummary> {
  return apiFetch<RunSummary>(`/runs/${runPath(runId)}`);
}

/**
 * Run의 Step 목록을 조회한다.
 *
 * @param runId Run 식별자
 * @returns Step 요약 목록
 */
export function getSteps(runId: string): Promise<StepSummary[]> {
  return apiFetch<StepSummary[]>(`/runs/${runPath(runId)}/steps`);
}

/**
 * Run의 Evidence Timeline을 조회한다.
 *
 * @param runId Run 식별자
 * @returns Timeline 항목 목록
 */
export function getTimeline(runId: string): Promise<TimelineEvent[]> {
  return apiFetch<TimelineEvent[]>(`/runs/${runPath(runId)}/timeline`);
}

/**
 * Run JSON report를 조회한다.
 *
 * @param runId Run 식별자
 * @returns report 문서
 */
export function getReport(runId: string): Promise<RunReport> {
  return apiFetch<RunReport>(`/runs/${runPath(runId)}/reports/json`);
}

/** 한 번의 변경 의도를 응답 유실 뒤에도 식별하기 위한 선택 옵션이다. */
export interface MutationIntentOptions {
  idempotencyKey?: string;
}

/** 선택적 멱등성 key를 검증하고 요청 header에 결속한다. */
function mutationHeaders(options?: MutationIntentOptions): HeadersInit {
  const headers = new Headers({ 'Content-Type': 'application/json' });
  if (options?.idempotencyKey !== undefined) {
    if (options.idempotencyKey.length < 16
        || options.idempotencyKey.length > 128
        || !/^[A-Za-z0-9._~+/-]+={0,2}$/u.test(options.idempotencyKey)) {
      throw new ApiError('요청 식별자 형식이 올바르지 않습니다.', 'validation', false, undefined,
        'IDEMPOTENCY_KEY_INVALID', 'Idempotency-Key');
    }
    headers.set('Idempotency-Key', options.idempotencyKey);
  }
  return headers;
}

/**
 * Run 목록을 조회한다.
 *
 * @returns Run 요약 배열
 */
export function listRuns(): Promise<RunSummary[]> {
  return apiFetch<RunSummary[]>('/runs');
}

/**
 * Overview 대시보드 통계를 조회한다.
 *
 * @returns 24시간 상태별 Run 수·최다 실패 Scenario·정체 Run 수
 */
export function getStats(): Promise<OverviewStats> {
  return apiFetch<OverviewStats>('/stats/overview');
}

/**
 * Run 생성을 요청한다.
 *
 * @param scenarioName Scenario 이름
 * @param scenarioVersion Scenario 버전
 * @param targetProfile Target Profile 이름
 * @param seed 결정론 seed
 * @param variables 변수 override
 * @returns 생성된 runId
 */
export async function createRun(
  scenarioName: string,
  scenarioVersion: string,
  targetProfile: string,
  seed: number | undefined,
  variables: Record<string, string>,
  options?: MutationIntentOptions,
): Promise<string> {
  if (seed !== undefined && !Number.isSafeInteger(seed)) {
    throw new ApiError('Seed는 안전한 정수여야 합니다.', 'validation', false, undefined,
      'SEED_INVALID', 'seed');
  }
  const created = await apiFetch<{ runId: string }>('/runs', {
    method: 'POST',
    headers: mutationHeaders(options),
    body: JSON.stringify({
      scenarioRef: { name: scenarioName, version: scenarioVersion },
      targetProfile,
      ...(seed === undefined ? {} : { seed }),
      variables,
    }),
  });
  return validatedRunId(created.runId);
}

/**
 * Scenario artifact 목록을 조회한다.
 *
 * @returns Scenario 요약 배열
 */
export function getScenarios(): Promise<ArtifactSummary[]> {
  return apiFetch<ArtifactSummary[]>('/artifacts?kind=SCENARIO');
}

/**
 * Artifact 원본 YAML을 조회한다.
 *
 * @param kind Artifact 종류
 * @param name Artifact 이름
 * @param version Artifact 버전
 * @returns 원본 YAML 문자열
 */
export async function getArtifactRaw(
  kind: string,
  name: string,
  version: string,
): Promise<string> {
  if (kind !== 'PROVIDER_CONTRACT' && kind !== 'SCENARIO' && kind !== 'TARGET_PROFILE') {
    throw new ApiError('Artifact 종류가 올바르지 않습니다.', 'validation', false, undefined,
      'ARTIFACT_KIND_INVALID', 'kind');
  }
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/u.test(name)
      || !/^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$/u.test(version)) {
    throw new ApiError('Artifact 식별자 형식이 올바르지 않습니다.', 'validation', false, undefined,
      'ARTIFACT_ID_INVALID');
  }
  const path = `/artifacts/${encodeURIComponent(kind)}/${encodeURIComponent(name)}/${encodeURIComponent(version)}/raw`;
  return controlFetch(path, { headers: { Accept: 'text/plain, application/yaml, text/yaml' } }, async (response) => {
    if (!response.ok) {
      throw await httpError(response);
    }
    const contentType = (response.headers.get('content-type') ?? '').toLowerCase();
    if (!contentType.includes('text/plain') && !contentType.includes('yaml')) {
      throw new ApiError('예상하지 못한 Artifact 응답 형식입니다.', 'protocol', false, response.status,
        'RESPONSE_CONTENT_TYPE_INVALID');
    }
    try {
      return new TextDecoder('utf-8', { fatal: true }).decode(
        await readBoundedBytes(response, MAX_RAW_ARTIFACT_BYTES),
      );
    } catch (error) {
      if (error instanceof ApiError) {
        throw error;
      }
      throw new ApiError('Artifact가 올바른 UTF-8 문서가 아닙니다.', 'protocol', false,
        response.status, 'ARTIFACT_UTF8_INVALID');
    }
  });
}

/**
 * Target Profile artifact 목록을 조회한다.
 *
 * @returns Target Profile 요약 배열
 */
export function getProfiles(): Promise<ArtifactSummary[]> {
  return apiFetch<ArtifactSummary[]>('/artifacts?kind=TARGET_PROFILE');
}

/**
 * Target Profile의 DNS·allowlist·secretRef·도달성 사전검사를 실행한다.
 *
 * @param profileName 검사할 Profile 이름
 * @returns secret 값이 제거된 구조화 결과
 */
export function verifyTargetProfile(profileName: string): Promise<TargetProfilePreflight> {
  if (!/^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$/u.test(profileName)) {
    throw new ApiError('Target Profile 이름 형식이 올바르지 않습니다.', 'validation', false,
      undefined, 'TARGET_PROFILE_NAME_INVALID', 'name');
  }
  return apiFetch<TargetProfilePreflight>(
    `/target-profiles/${encodeURIComponent(profileName)}/verify`,
    { method: 'POST' },
  );
}

/**
 * YAML 문서를 검증한다.
 *
 * @param yaml 원본 YAML
 * @returns 검증 결과
 */
export function validateArtifact(yaml: string): Promise<Record<string, unknown>> {
  assertArtifactRequest(yaml);
  return apiFetch<Record<string, unknown>>('/artifacts/validate', {
    method: 'POST',
    headers: { 'Content-Type': 'text/plain' },
    body: yaml,
  });
}

/**
 * YAML 문서를 import한다.
 *
 * @param yaml 원본 YAML
 * @returns import 결과
 */
export function importArtifact(yaml: string): Promise<Record<string, unknown>> {
  assertArtifactRequest(yaml);
  return apiFetch<Record<string, unknown>>('/artifacts/import', {
    method: 'POST',
    headers: { 'Content-Type': 'text/plain' },
    body: yaml,
  });
}

/** 브라우저가 파일 전체를 전송하기 전에 UTF-8 실제 byte 상한을 확인한다. */
export function assertArtifactRequest(yaml: string): void {
  if (yaml.includes('\u0000')) {
    throw new ApiError('Artifact에 NUL 문자를 사용할 수 없습니다.', 'validation', false, undefined,
      'ARTIFACT_NUL_FORBIDDEN');
  }
  if (new TextEncoder().encode(yaml).byteLength > MAX_RAW_ARTIFACT_BYTES) {
    throw new ApiError('Artifact는 UTF-8 기준 512 KiB 이하여야 합니다.', 'validation', false, 413,
      'ARTIFACT_REQUEST_TOO_LARGE');
  }
}

/**
 * 인증 header가 포함된 Run SSE 스트림을 관찰한다.
 *
 * @param runId Run 식별자
 * @param signal 화면 이탈 시 요청을 취소할 signal
 * @param onEvent 수신한 SSE event 이름 callback
 * @returns 스트림이 종료될 때 완료되는 Promise
 */
export async function observeRunStream(
  runId: string,
  signal: AbortSignal,
  onEvent: (eventName: string) => void,
): Promise<void> {
  return controlFetch(`/runs/${runPath(runId)}/stream`, {
    headers: { Accept: 'text/event-stream' },
    signal,
  }, async (response) => {
    if (!response.ok) {
      throw await httpError(response);
    }
    const contentType = (response.headers.get('content-type') ?? '').toLowerCase();
    if (!contentType.includes('text/event-stream')) {
      throw new ApiError('Run 스트림 응답 형식이 올바르지 않습니다.', 'protocol', false,
        response.status, 'SSE_CONTENT_TYPE_INVALID');
    }
    if (!response.body) {
      throw new ApiError('Run 스트림 응답 본문이 없습니다.', 'protocol', false,
        response.status, 'SSE_BODY_MISSING');
    }
    await readEventStream(response.body, onEvent);
  }, SSE_ABSOLUTE_TIMEOUT_MS);
}

/** SSE reader를 idle·frame 상한 안에서 소비하고 종료 시 반드시 cancel한다. */
async function readEventStream(
  body: ReadableStream<Uint8Array>,
  onEvent: (eventName: string) => void,
): Promise<void> {
  const reader = body.getReader();
  const decoder = new TextDecoder('utf-8', { fatal: true });
  const encoder = new TextEncoder();
  let buffered = '';
  try {
    while (true) {
      const chunk = await readSseChunk(reader);
      if (chunk.done) {
        buffered += decoder.decode();
        break;
      }
      if (chunk.value.byteLength > MAX_SSE_FRAME_BYTES) {
        throw new ApiError('Run 스트림 frame이 허용 범위를 초과했습니다.', 'protocol', false,
          undefined, 'SSE_FRAME_TOO_LARGE');
      }
      buffered += decoder.decode(chunk.value, { stream: true });
      const frames = buffered.split(/\r?\n\r?\n/u);
      buffered = frames.pop() ?? '';
      if (encoder.encode(buffered).byteLength > MAX_SSE_FRAME_BYTES) {
        throw new ApiError('Run 스트림 frame이 허용 범위를 초과했습니다.', 'protocol', false,
          undefined, 'SSE_FRAME_TOO_LARGE');
      }
      for (const frame of frames) {
        if (encoder.encode(frame).byteLength > MAX_SSE_FRAME_BYTES) {
          throw new ApiError('Run 스트림 frame이 허용 범위를 초과했습니다.', 'protocol', false,
            undefined, 'SSE_FRAME_TOO_LARGE');
        }
        const eventLine = frame.split(/\r?\n/u).find((line) => line.startsWith('event:'));
        const eventName = eventLine?.slice('event:'.length).trim();
        if (eventName && /^[A-Za-z][A-Za-z0-9_-]{0,63}$/u.test(eventName)) {
          onEvent(eventName);
        }
      }
    }
  } catch (error) {
    if (error instanceof ApiError || isAbortError(error)) {
      throw error;
    }
    throw new ApiError('Run 스트림 형식이 올바르지 않습니다.', 'protocol', false,
      undefined, 'SSE_STREAM_INVALID');
  } finally {
    await reader.cancel().catch(() => undefined);
  }
}

/** 다음 SSE chunk가 idle 제한 안에 도착하도록 제한한다. */
async function readSseChunk(
  reader: ReadableStreamDefaultReader<Uint8Array>,
): Promise<ReadableStreamReadResult<Uint8Array>> {
  let timer = 0;
  try {
    return await Promise.race([
      reader.read(),
      new Promise<never>((_resolve, reject) => {
        timer = window.setTimeout(() => reject(new ApiError(
          'Run 스트림 응답이 중단되었습니다.', 'timeout', true, undefined, 'SSE_IDLE_TIMEOUT',
        )), SSE_IDLE_TIMEOUT_MS);
      }),
    ]);
  } finally {
    window.clearTimeout(timer);
  }
}

/**
 * 서버 실행 중 설정을 조회한다.
 *
 * @returns 버전·동작 모드·보존 기간·telemetry 여부 등 설정 집합
 */
export function getSettings(): Promise<ServerSettings> {
  return apiFetch<ServerSettings>('/settings');
}

/**
 * report를 인증 header로 내려받고 브라우저 저장 대화상자를 연다.
 *
 * @param runId Run 식별자
 * @param kind report 종류(json·junit·bundle)
 * @returns 다운로드가 준비되면 완료되는 Promise
 */
export async function downloadReport(
  runId: string,
  kind: 'json' | 'junit' | 'bundle',
): Promise<void> {
  const encodedRunId = runPath(runId);
  const path = kind === 'bundle'
    ? `/runs/${encodedRunId}/bundle`
    : `/runs/${encodedRunId}/reports/${kind}`;
  return controlFetch(path, undefined, async (response) => {
    if (!response.ok) {
      throw await httpError(response);
    }
    const contentType = (response.headers.get('content-type') ?? '').toLowerCase();
    const validType = kind === 'json'
      ? contentType.includes('application/json')
      : kind === 'junit'
        ? contentType.includes('application/xml') || contentType.includes('text/xml')
        : contentType.includes('application/zip');
    if (!validType) {
      throw new ApiError('Report 응답 형식이 올바르지 않습니다.', 'protocol', false,
        response.status, 'REPORT_CONTENT_TYPE_INVALID');
    }
    const bytes = await readBoundedBytes(response, MAX_DOWNLOAD_BYTES);
    const blobBytes = new Uint8Array(bytes.byteLength);
    blobBytes.set(bytes);
    const blob = new Blob([blobBytes.buffer], { type: contentType });
    const objectUrl = URL.createObjectURL(blob);
    const link = document.createElement('a');
    try {
      link.href = objectUrl;
      link.download = kind === 'json' ? 'report.json' : kind === 'junit' ? 'junit.xml' : 'bundle.zip';
      document.body.appendChild(link);
      link.click();
    } finally {
      link.remove();
      // click 처리 전에 URL을 폐기하는 브라우저별 race를 피하되 다음 task에서 즉시 회수한다.
      window.setTimeout(() => URL.revokeObjectURL(objectUrl), 0);
    }
  });
}

/**
 * 활성 Run 취소를 요청한다.
 *
 * @param runId 취소할 Run 식별자
 */
export async function cancelRun(runId: string): Promise<void> {
  await apiFetch<Record<string, unknown>>(`/runs/${runPath(runId)}/cancel`, { method: 'POST' });
}

/**
 * terminal Run을 같은 입력으로 재실행한다.
 *
 * @param runId 재실행할 Run 식별자
 * @returns 새 Run 식별자
 */
export async function rerunRun(runId: string, options?: MutationIntentOptions): Promise<string> {
  const body = await apiFetch<{ runId: string }>(`/runs/${runPath(runId)}/rerun`, {
    method: 'POST',
    headers: mutationHeaders(options),
  });
  return validatedRunId(body.runId);
}
