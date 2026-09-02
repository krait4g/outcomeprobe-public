import { useQuery } from '@tanstack/react-query';
import { getSettings } from '../api/client';

/**
 * 서버 실행 중 설정과 동작 모드를 보여주는 Settings 화면이다.
 *
 * <p>책임: 사용자가 이 서버가 production gateway가 아닌 local·test·CI 전용
 * 워크벤치이며, telemetry를 외부로 보내지 않고, 설정이 환경변수 기반으로
 * 동작한다는 사실을 한눈에 확인하게 한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: 이 화면은 읽기 전용이다. v1에서 설정 변경은
 * 환경변수·application.yaml 수정과 재기동으로만 반영된다.</p>
 */
export function SettingsPage() {
  const settingsQuery = useQuery({
    queryKey: ['settings'],
    queryFn: getSettings,
  });

  const settings = settingsQuery.data;

  return (
    <div>
      <h2>Settings</h2>
      <p className="tagline">
        서버 실행 중 설정입니다. 변경은 환경변수·구성 파일과 재기동으로 반영되며,
        이 화면은 읽기 전용입니다.
      </p>

      {settingsQuery.isError ? (
        <p className="error">설정을 불러오지 못했습니다. 서버가 실행 중인지 확인하세요.</p>
      ) : null}

      <section>
        <h3>서버 정보</h3>
        <table>
          <tbody>
            <Row label="버전" value={settings?.version} />
            <Row label="지원 DSL" value={settings?.dslVersions?.join(', ')} />
            <Row label="동작 모드" value={settings?.mode} />
            <Row label="저장소" value={settings?.storage} />
            <Row
              label="Production gateway"
              value={settings ? (settings.productionGateway ? '예' : '아니요 (local·CI 전용)') : undefined}
            />
          </tbody>
        </table>
      </section>

      <section>
        <h3>실행 설정</h3>
        <table>
          <tbody>
            <Row
              label="Run 보존 기간"
              value={settings ? `${settings.retentionDays}일 (outcomeprobe.retention.days)` : undefined}
            />
            <Row
              label="동시 실행 Run 상한"
              value={settings ? `${settings.maxConcurrentRuns}개 (outcomeprobe.limits.max-concurrent-runs)` : undefined}
            />
            <Row
              label="Control API 인증"
              value={settings ? (settings.controlApiAuthenticationRequired ? '필수 (단일 운영자 Bearer)' : '비활성') : undefined}
            />
            <Row
              label="Emulator ingress token"
              value={settings ? (settings.emulatorTokenRequired ? '설정됨 (인증 필요)' : '미설정 (인증 불요)') : undefined}
            />
          </tbody>
        </table>
      </section>

      <section>
        <h3>Telemetry</h3>
        <table>
          <tbody>
            <Row
              label="사용 통계 전송"
              value={settings ? (settings.telemetryEnabled ? 'on' : 'off (기본값)') : undefined}
            />
          </tbody>
        </table>
        {settings?.telemetryNote ? <p className="tagline">{settings.telemetryNote}</p> : null}
      </section>

      <section>
        <h3>고정 안전 기본값</h3>
        <p className="tagline">
          아래 값은 어댑터에 고정된 안전 상수로, v1에서는 별도로 구성할 수 없습니다.
        </p>
        <table>
          <tbody>
            <Row label="Target 응답 body 상한" value="1 MiB" />
            <Row label="Target 연결 timeout" value="2초" />
            <Row label="Target 요청 timeout" value="10초" />
            <Row label="Run worker polling" value="250ms" />
            <Row label="Delivery worker polling" value="150ms" />
            <Row label="SSRF host 검사" value="항상 on (metadata·link-local·multicast 차단)" />
          </tbody>
        </table>
      </section>
    </div>
  );
}

/** Settings 행 컴포넌트 속성이다. */
interface RowProps {
  /** 설정 항목 이름 */
  label: string;
  /** 표시할 값, undefined면 로딩 플레이스홀더 */
  value: string | undefined;
}

/**
 * Settings 테이블의 한 행을 렌더링한다.
 *
 * @param props 행 속성
 * @returns 테이블 행 엘리먼트
 */
function Row({ label, value }: RowProps) {
  return (
    <tr>
      <th scope="row">{label}</th>
      <td>{value ?? '–'}</td>
    </tr>
  );
}
