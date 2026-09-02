# OutcomeProbe v0.1.0-rc Docker Quick Start

이 경로는 source-only `v0.1.0-rc`를 로컬·CI 또는 격리된 팀 환경에서 빌드하고 대표 결제
장애 여정을 확인하기 위한
단일 노드 Quick Start입니다. Docker Engine 또는 Docker Desktop과 Docker Compose
2.30 이상만 필요하며 host Java·Gradle·Node·bash·curl·jq는 사용하지 않습니다.

## 1. 환경 설정

저장소 루트에서 `.env.example`을 `.env`로 복사한 뒤 다음
세 값을 서로 다른 임의 값으로 채웁니다. 실제 값은 Git에 커밋하지 마세요.

- `OUTCOMEPROBE_DB_PASSWORD`: PostgreSQL 비밀번호
- `OUTCOMEPROBE_DEMO_CALLBACK_SECRET`: Reference Consumer callback 서명 key
- `OUTCOMEPROBE_CONTROL_API_TOKEN`: 32~512자의 제한된 token68 운영자 token

Linux/macOS:

```bash
cp .env.example .env
```

Windows PowerShell:

```powershell
Copy-Item .env.example .env
```

## 2. Docker 두 명령으로 기동·bootstrap

```bash
docker compose up -d --build --wait --wait-timeout 600
docker compose run --rm -T --no-deps --build outcomeprobe-bootstrap
```

첫 명령은 PostgreSQL, server, Web, 취약·복원력 Reference Consumer 다섯 개를 healthy로
기동합니다. Web은 이 시점에 `Docker bootstrap` 안내가 있는 안전한 빈 상태를 표시하고 대표
실행 제어를 비활성화합니다. 조회 실패는 빈 목록과 별도로 표시됩니다. 두 번째 명령은 기존 장기
service를 rebuild·recreate하지 않고 transient non-root bootstrap 컨테이너에서 다음 개별
YAML 다섯 개를 모두 검증하고 순서대로 import한 뒤 컨테이너를 즉시 제거합니다.

1. `generic-payment@1.1.0` ProviderContract
2. `vulnerable-consumer@1.0.0` TargetProfile
3. `resilient-consumer@1.0.0` TargetProfile
4. `payment-timeout-after-commit@1.0.0` Scenario
5. `payment-success-callback-duplicate@1.0.0` Scenario

두 번째 명령이 성공한 뒤 Web의 대표 실행 버튼을 사용합니다. 같은 명령을 다시 실행해도
동일 content hash의 기존 정의를 재사용하므로 Artifact가 중복되지 않습니다. token은
기존 Compose 환경 계약으로 transient bootstrap에 전달되며 argv·URL·로그에는 넣지 않습니다.

## 3. 대표 기능 확인

1. <http://127.0.0.1:3000>을 엽니다.
2. `.env`의 `OUTCOMEPROBE_CONTROL_API_TOKEN`을 운영자 token 입력란에 입력합니다.
3. **취약 Consumer로 timeout-after-commit 실행**을 누르고 `FAILED`를 확인합니다.
4. **복원력 Consumer로 timeout-after-commit 실행**을 누르고 `PASSED`를 확인합니다.
5. Run 상세에서 Provider commit과 response fault가 분리된 Timeline 및
   `report.json`, `junit.xml`, `bundle.zip`을 확인합니다.

Overview의 두 버튼은 `payment-timeout-after-commit` 전용입니다. Callback 중복 경로는
**Scenarios → `payment-success-callback-duplicate@1.0.0` → 상세**에서
`vulnerable-consumer`와 `resilient-consumer`를 각각 선택해 실행합니다. 취약 Target은 같은
성공 Callback의 side effect를 3회 적용해 `FAILED`, 복원력 Target은 eventId 중복 제거로
`ledgerPostCount=1`을 유지해 `PASSED`여야 합니다. 두 Run 모두 Timeline에
`CALLBACK_DELIVERY_SUCCEEDED`가 정확히 3건 있어야 합니다.

모든 목록은 loading·empty·error를 구분합니다. import와 Run 변경 요청은 single-flight이고
진행 상태를 표시하며 자동 재시도하지 않습니다. 응답 유실 뒤 같은 실행 의도를 확인할 때는
서버 `Idempotency-Key`가 같은 Run ID를 반환해야 합니다. 저장된 운영자 token도 서버 확인 전에는
인증 완료로 취급하지 않습니다. 390×844 모바일 화면과 키보드에서도 인증, Scenario 실행,
Run 상세를 사용할 수 있어야 합니다.

취약 결과의 `FAILED`는 제품 오류가 아니라 의도한 판정입니다. Provider는 성공으로
commit했지만 취약 Consumer가 유실된 응답 뒤 최종 상태를 복구하지 못했음을 뜻합니다.

상태를 확인하려면 Docker 명령만 사용합니다.

```bash
docker compose ps -a
```

장기 service 다섯 개는 healthy여야 합니다. bootstrap은 `--rm`으로 제거되므로
`outcomeprobe-bootstrap`의 `Exited` 행이 남아 있지 않아야 합니다.

## 재실행·정리

동일 DB에서 bootstrap만 다시 검증하려면 다음 명령을 사용합니다.

```bash
docker compose run --rm -T --no-deps --build outcomeprobe-bootstrap
```

일반 정리는 데이터를 보존합니다.

```bash
docker compose down --remove-orphans
```

`docker compose down --volumes --remove-orphans`는 PostgreSQL 데이터를 영구 삭제합니다.
이전 개발판이 자동 seed한 Profile과 content hash 충돌이 발생하고 보존할 데이터가 없을
때만 명시적으로 사용하세요.

## 실제 서비스형 연결을 연습하는 opt-in kit

기본 두 명령과 다섯 service를 완료한 뒤 별도의 HTTP/JSON 서비스 연결 경계를 확인하려면
`examples/realtime-track/README.md`를 사용합니다. 이 경로는 `compose.realtime-track.yaml`을
명시적으로 추가해야만 repository-owned black-box Target fixture와 일회성 kit runner를
기동합니다. ProviderContract 1개·TargetProfile 1개·Scenario 3개가 단일·3회 중복·역순
입력의 최종 상태 수렴을 검사합니다.

기본 `outcomeprobe-bootstrap`은 realtime Artifact를 import하지 않습니다. opt-in kit 통과는
공개 HTTP/JSON 연결 절차의 conformance 증거이지 실제 제3자·운영 서비스나 외부 사용자 실행
증거가 아닙니다. 자신의 서비스에 적용할 때는 [외부 Target 연결 가이드](docs/integration-guide.md)에
따라 Profile의 Docker-visible 주소·allowlist·secretRef와 read-only business assertion을
명시적으로 교체하세요.

키트의 검증 스크립트는 세 Scenario의 `PASSED` 판정과 business 결과, Target 인증 거부,
callback HMAC, secret redaction, 결과 파일 hash 및 실행 뒤 Docker resource 정리를 함께
검사합니다. 이 결과를 자신의 서비스에 대한 보증으로 사용하지 말고, 같은 계약을 자신의
격리 환경에서 별도로 실행하세요.

## 지원 경계

- UI와 server의 두 published port만 기본적으로 `127.0.0.1`에 열립니다. 두 Reference
  Consumer는 host port가 없고 Compose `app` network의 `rr-vulnerable:8091`·
  `rr-resilient:8092`라는 host 비공개 Docker DNS로 접근합니다. 이는 server-only ACL을
  뜻하지 않습니다. PostgreSQL host port도 없습니다.
- 이 구성은 TLS·OIDC·RBAC·멀티테넌시·rate limit·HA를 제공하지 않습니다.
- 인터넷에 직접 노출하지 말고, 공유 환경에서는 인증 reverse proxy와 network policy를
  별도로 구성하세요.
- 번들형 Scenario 배포, 디렉터리 import, 자동 Contract·Scenario seed는 지원하지 않습니다.
- booking·generic Consumer 코드는 기본 bootstrap에 등록되지 않습니다. Quick Start가 직접
  안내하는 골든 패스는 위 payment Scenario 2개입니다.
- 실제 외부 Target 연결은 별도 통합 범위이며, 이 Quick Start는 포함된 Reference Consumer만
  검증합니다. opt-in realtime kit도 저장소 소유 fixture와의 HTTP/JSON conformance만 검증합니다.
- Compose의 `app` network는 container discovery를 위한 연결 경계이지 server만 접근할 수 있는
  network ACL이 아닙니다. 더 강한 격리가 필요하면 별도 network policy를 적용하세요.
- Web은 잘못된 token, 비어 있는 초기 상태와 server 연결 오류를 구분하지만 운영 monitoring이나
  자동 장애 복구를 대신하지 않습니다.
