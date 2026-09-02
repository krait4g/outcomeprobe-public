# OutcomeProbe v0.1.0-rc 외부 Target 연결 가이드

OutcomeProbe를 자신의 애플리케이션에 연결하는 절차입니다. OutcomeProbe의 Java
코드를 수정할 필요가 없습니다.

내장 reference consumer로 대표 기능을 먼저 확인하려면 [Docker-only Quick Start](../QUICKSTART.md)를
사용합니다. `.env` 설정 뒤 `docker compose up -d --build --wait --wait-timeout 600`으로
장기 실행 service 5개를 healthy로 만들고,
`docker compose run --rm -T --no-deps --build outcomeprobe-bootstrap`으로 골든 패스 다섯 artifact를 import합니다.
Web은 첫 명령 뒤 열리지만 두 번째 명령 전에는 empty state이므로 Quick Start 버튼은 두 번째
명령 완료 뒤 사용합니다. transient bootstrap은 non-root이며 같은 `.env` token을
`OUTCOMEPROBE_CONTROL_API_TOKEN` 환경변수로 받고 argv·URL·로그에 남기지 않습니다. host
Java·Gradle·Node.js·bash·curl·jq가 필요하지 않습니다.
두 번째 명령의 `--no-deps`는 첫 명령으로 띄운 service의 rebuild·recreate를 막습니다.
Overview의 두 버튼은 timeout/probe Scenario 전용입니다. 공개 duplicate-callback Scenario는
Scenarios 목록의 `payment-success-callback-duplicate@1.0.0` 상세에서 Target을 바꿔
실행합니다. 기본 bootstrap이 직접 등록하는 범위는 위 두 payment Scenario와 두 Reference
Consumer입니다.
실제 서비스형 연결을 별도로 연습하려면 `examples/realtime-track`의 opt-in HTTP/JSON
conformance kit를 사용합니다. 이 키트는 기본 payment Quick Start에 자동으로 포함되지 않으며,
저장소가 소유한 black-box Target fixture에 1개 Contract·1개 Profile·3개 Scenario를 실행합니다.
통과 결과는 공개 연결 계약의 재현 가능성을 뜻할 뿐 실제 제3자·운영 서비스 검증을 뜻하지
않습니다. 결정과 한계는 [ADR 0020](adr/0020-user-service-connection-kit.md)에 고정합니다.

## 전제

- OutcomeProbe 서버가 로컬 또는 CI 네트워크에서 실행 중
- Target 애플리케이션이 HTTP API를 제공 (상태 조회 endpoint 포함)
- Docker Compose Quick Start로 서버 실행 방법을 먼저 확인

## 공개 user-service conformance kit

`examples/realtime-track`은 사용자가 자신의 HTTP/JSON 서비스용 Artifact를 만들 때 복사할 수
있는 최소 구조를 제공합니다.

- `provider-contract.yaml`: 현행 ProviderContract schema에 맞춘 명시적 Contract
- `target-profile.compose.yaml`: OutcomeProbe server 컨테이너에서 해석되는 service DNS와
  host·port·path allowlist
- `scenario-single-event.yaml`: 단일 입력의 최종 상태 수렴
- `scenario-duplicate-3x.yaml`: 같은 입력 3회의 멱등 수렴
- `scenario-out-of-order.yaml`: 과거 입력으로 최신 상태가 회귀하지 않음

실행은 `compose.realtime-track.yaml`을 명시적으로 추가한 격리 Compose project에서만 수행합니다.
Target fixture의 host port는 열지 않고 server→Target과 Target→Emulator 방향을 각각 Docker
service DNS로 고정합니다. kit runner는 세 YAML을 validate/import하고 세 Scenario를 모두
`PASSED`로 확인한 뒤 report·JUnit·bundle SHA-256과 정규화 summary를 만듭니다.

기본 `docker compose up`이나 `outcomeprobe-bootstrap`은 이 fixture를 기동·import하지 않습니다.
실제 사용자 서비스에 적용할 때는 Target Profile의 `baseUri`, `allowedHosts`, `allowedPorts`,
`allowedPaths`와 secretRef만 자신의 격리 network에 맞게 바꾸고 Scenario의 business assertion은
서비스가 외부에 제공하는 read-only 상태 조회 계약에 맞춥니다. 컨테이너 안의 `localhost`는
다른 서비스 주소가 아니므로 사용하지 않습니다.

공개 realtime 예시의 `realtime-target-token`은 환경변수
`OUTCOMEPROBE_SECRET_REALTIME_TARGET_TOKEN`에 매핑되며 Authorization header 전체값
`Bearer <token>`을 받습니다. `realtime-callback-signing-key`는
`OUTCOMEPROBE_SECRET_REALTIME_CALLBACK_SIGNING_KEY`에 매핑됩니다. 실제 값은
`.env.secrets.example`에 쓰지 말고 `.env.secrets` 또는 read-only secret directory에서 서로 다른
값으로 제공하세요.

키트 검증기는 `user-service-kit-summary.json`과 세 Run의 sanitized report·JUnit·bundle을
결과 디렉터리에 만듭니다. 읽기 전용 재검증은 파일 allowlist, 크기, SHA-256, ZIP 안전 경로와
secret 원문 부재를 확인합니다. 실제 사용자 서비스의 URL·credential·응답, raw timeline,
service log, HTTP trace와 token은 결과에 포함하지 마세요.

## Control API 운영자 인증

모든 `/api/v1/**` 요청은 서버의 `OUTCOMEPROBE_CONTROL_API_TOKEN`과 정확히 같은 Bearer
token을 요구합니다. token은 32~512자의 제한된 token68 문자열이며 Target secret이나
`OUTCOMEPROBE_EMULATOR_TOKEN`과 공유하지 않습니다.

```bash
export OUTCOMEPROBE_CONTROL_API_TOKEN='<서버와 같은 값>'
printf 'header = "Authorization: Bearer %s"\n' "$OUTCOMEPROBE_CONTROL_API_TOKEN" |
  curl --config - --fail --silent http://localhost:8080/api/v1/settings
```

CLI는 이 환경변수 또는 권한을 제한한 `--token-file`을 사용합니다. Web은 로그인 화면에서
token을 받아 현재 탭의 `sessionStorage`에만 보관합니다. token을 URL, shell argument,
Target Profile, Scenario 또는 로그에 넣지 마세요. `/actuator/health/**`와 `/actuator/info`는
probe를 위해 공개되고 Prometheus는 Control API token으로 보호됩니다. `/emulator/**`의 선택적
ingress token은 별도 `X-OutcomeProbe-Emulator-Token` header를 사용합니다.

## 1단계 — Provider Base URL을 Emulator alias로 변경

Target 애플리케이션이 외부 Provider를 호출하는 base URL을 OutcomeProbe
Emulator alias로 바꿉니다.

```text
기존: https://api.payment-provider.example
변경: http://<outcomeprobe-server>:8080/emulator/mockpay
```

alias 이름은 Provider Contract의 `emulatorAlias`와 일치해야 합니다.

Target Profile의 `baseUri`는 브라우저나 host가 아니라 `outcomeprobe-server` 컨테이너에서
접근 가능한 주소여야 합니다. 같은 Compose network에 Target을 붙이면 service DNS를 사용하고,
별도 시스템이면 해당 격리 환경에서 명시적으로 허용한 안정적 주소를 사용하세요. `localhost`는
server 컨테이너 자신을 뜻하므로 host 애플리케이션 주소로 사용할 수 없습니다. 기본 Reference
Consumer의 internal DNS를 외부 Target용 공개 endpoint로 재사용하지 않습니다.

## 2단계 — Target Profile 등록

아래 형식의 YAML을 인증된 `POST /api/v1/artifacts/import` 또는 UI Import 화면으로
등록합니다.

```yaml
apiVersion: outcomeprobe.io/v1alpha1
kind: TargetProfile
metadata:
  name: my-app
  displayName: My Application
  version: 1.0.0
spec:
  baseUri: http://my-app-host:8088
  allowedPaths:
    - /api/**
    - /test-support/**
    - /webhooks/**
  allowedMethods: [GET, POST, PUT, PATCH, DELETE]
  callbackPaths:
    paymentSucceeded: /webhooks/payments
  secretHeaders:
    Authorization:
      secretRef: my-app-token
  redaction:
    headers: [Authorization, Cookie]
    jsonPaths:
      - $.customer.email
```

- `allowedPaths` 밖으로는 어떤 요청도 나가지 않습니다(SSRF 방어).
- `callbackPaths`는 Contract의 callback key를 Target의 Webhook 경로에 연결합니다.
- secret 값은 문서에 쓸 수 없습니다. `secretRef`는 소문자 kebab-case만 허용하며,
  `my-app-token`은 `OUTCOMEPROBE_SECRET_MY_APP_TOKEN` 환경변수로 해석됩니다.
- Docker Compose에서는 `.env.secrets.example`을 `.env.secrets`로 복사한 뒤 실제 값을
  넣으세요. `.env.secrets`는 Git에서 제외되고 `outcomeprobe-server`에만 선택적으로
  전달됩니다. 실제 값을 `.env.example`, YAML, Git에 넣으면 안 됩니다.
- Quick Start의 `OUTCOMEPROBE_DEMO_CALLBACK_SECRET`은 Reference Consumer와의 로컬 데모
  전용 fallback입니다. 같은 `callback-signing-key`에 canonical 환경변수 또는 mounted file이
  있으면 그 값이 우선합니다. 실제 Target 연결에서는 데모 fallback을 운영 secret으로
  재사용하지 마세요. built-in Reference Consumer로 callback 여정을 실행하는 동안 canonical
  환경변수나 mounted `callback-signing-key`를 함께 설정한다면, Reference Consumer가 받는
  `OUTCOMEPROBE_DEMO_CALLBACK_SECRET`과 값이 정확히 같아야 합니다. 값이 다르면 서버가 만든
  HMAC과 Consumer 검증 값이 달라지므로 해당 여정은 의도대로 실패합니다.
- 파일 mount를 사용하려면 secretRef와 같은 파일명(예: `my-app-token`)으로 값을 만든 뒤
  `OUTCOMEPROBE_SECRETS_HOST_DIRECTORY`를 설정하고 다음 override를 함께 사용합니다.

```bash
docker compose -f compose.yaml -f compose.secrets-directory.yaml up -d
```

위 override는 host secret directory를 서버 컨테이너의
`/run/outcomeprobe-secrets`에 read-only로 mount합니다. 환경변수 값이 같은 ref에 있으면
mounted file보다 우선합니다.

기본 Compose에서는 server·Web 포트만 loopback에 bind됩니다. 두 Reference Consumer는 host에
publish되지 않고 Compose `app` network의 `rr-vulnerable:8091`·`rr-resilient:8092`라는
host 비공개 Docker DNS로 접근합니다. 이는 server-only network ACL을 의미하지 않으며
PostgreSQL도 host에 publish되지 않습니다. Control API에는 단일 운영자
Bearer 인증이 있지만
TLS·OIDC·RBAC·멀티테넌시·rate limit은 없습니다. reverse proxy TLS와 별도 network policy
없이 외부 인터페이스로 포트를 다시 publish하는 구성은 지원하지 않습니다.

token을 교체할 때는 서버 환경변수를 바꾸고 서버를 재기동합니다. 그 즉시 이전 token은
401로 거부되므로 Web의 `토큰 변경`으로 새 값을 입력하고 CLI 환경변수나 token 파일도 함께
교체하세요. 두 token을 동시에 허용하는 무중단 rotation은 지원하지 않습니다.

clean checkout에서 아래 명령을 실행하면 비기본 server·Web 포트, consumer host publish 0,
`app` network DNS, DB identity·설정 결속, Emulator token, timeout/probe와 duplicate-callback의
취약 `FAILED`·복원력 `PASSED`, 로그의 secret 원문 0건, 소유 Docker 자원 cleanup까지
동적으로 검증합니다.
실제 Target secret이 담긴 `.env.secrets`가 있으면 검증기는 이를 읽지 않고 중단합니다.

```bash
bash scripts/config-contract/verify-compose-runtime.sh
```

Run variables에 token·password·Authorization 같은 secret을 넣지 마세요. Secret은 반드시
Target Profile의 `secretRef`로 전달해야 합니다. Scenario 기본값과 Run override를 합친
최종 변수는 최대 100개이고, key는 UTF-8 128바이트 이하, 개별 값은 UTF-8 JSON 기준
256KiB 이하입니다. 병합 변수와 치환된 Step은 저장 전에 다시 검사되므로 absolute URL,
fragment, 민감 query parameter를 변수 치환으로 만들 수 없습니다. 일반 이름에 담긴 임의
문자열의 실제 의미까지 자동 판별하는 기능은 아니므로 사용자가 secretRef 원칙을 지켜야 합니다.

## 3단계 — 상태 조회(Probe) endpoint 준비

`probe.eventually` Step이 Target의 최종 상태를 읽을 HTTP/JSON endpoint가
필요합니다. 운영 조회 API 또는 테스트 전용 read-only API를 사용하세요.
가능하면 다음 정보를 노출하세요.

```json
{
  "status": "SUCCEEDED",
  "version": 3,
  "providerQueryCount": 1,
  "ledgerPostCount": 1,
  "processedEventIds": ["evt-1"]
}
```

상태만 노출해도 검증은 가능하지만, side effect 횟수를 노출하면 중복 처리
결함을 검출할 수 있습니다.

## 4단계 — Scenario 작성·실행

자신의 ProviderContract·TargetProfile·Scenario를 각각 작성합니다. `v0.1.0-rc` 서버는
Contract와 Scenario를 자동 seed하지 않으며, 세 Artifact를 개별 YAML 파일로 명시 import합니다.
Scenario에는 절대 absolute URL을 쓰지 마세요. 경로는 항상 Target Profile
기준 상대 경로입니다.

공개판은 source-only이므로 JDK 21 환경에서 저장소 루트의 Gradle wrapper로 CLI JAR를 먼저
빌드합니다.

```bash
./gradlew :cli:shadowJar
```

```bash
# 자신의 외부 Target artifact를 참조 순서대로 개별 import
java -jar cli/build/libs/outcomeprobe-cli-0.1.0.jar import provider-contract.yaml
java -jar cli/build/libs/outcomeprobe-cli-0.1.0.jar import target-profile.yaml
java -jar cli/build/libs/outcomeprobe-cli-0.1.0.jar import my-scenario.yaml

# 단일 Scenario 실행 (CI용)
java -jar cli/build/libs/outcomeprobe-cli-0.1.0.jar run-file my-scenario.yaml \
  --target my-app --wait --junit report.xml

# 또는 이미 import한 Scenario 실행
java -jar cli/build/libs/outcomeprobe-cli-0.1.0.jar run \
  --scenario my-scenario@1.0.0 \
  --target my-app --seed 42001 --wait
```

`v0.1.0-rc` CLI는 directory와 번들형 Scenario import를 입력 오류(exit 2)로 거부합니다.
제거된 번들 API도 404이며, 기존 DB 호환용 내부 discriminator를 지원 기능으로 해석하지 않습니다.

Quick Start의 명시적 transient bootstrap은 `examples/golden-path/payment`의
`ProviderContract` 1개·`TargetProfile` 2개·`Scenario` 2개만 명시적으로 import합니다. 사용자가 만든
외부 Target artifact는 import하지 않습니다. `docker compose run --rm -T --no-deps --build
outcomeprobe-bootstrap`을 다시 실행하면 동일 content hash는 중복 생성하지 않으며 컨테이너는
매번 즉시 제거됩니다. 과거 volume의 artifact와 충돌하고 보존할 데이터가 없을 때만
`docker compose down --volumes --remove-orphans`로 초기화하세요. 이 명령은 PostgreSQL 데이터를
삭제합니다.

종료 코드: 0=PASS, 1=FAIL, 2=입력 오류, 3=서버 오류, 4=취소.

## 5단계 — Webhook 서명

Contract의 `signature` 설정에 따라 OutcomeProbe가 `X-OutcomeProbe-Signature`
header에 HMAC_SHA256(원본 body, secret) 16진수를 실어 보냅니다. Target은
원본 body 바이트로 서명을 검증하고, 실패 시 401로 거부하고 상태를 바꾸지
말아야 합니다.

## 문제 해결

| 증상 | 확인 사항 |
|---|---|
| Run이 `PROVIDER_ALIAS_BUSY`로 실패 | 같은 alias를 쓰는 다른 Run이 진행 중인지 확인 |
| `TARGET_PATH_NOT_ALLOWED` | Target Profile의 `allowedPaths`에 경로가 있는지 확인 |
| 전달이 DEAD로 끝남 | Target Webhook 응답 코드와 `retryOnStatus` 설정 확인 |
| Probe가 404만 받음 | Probe 경로가 `allowedPaths`에 있고 endpoint가 존재하는지 확인 |
