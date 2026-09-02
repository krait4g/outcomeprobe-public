# OutcomeProbe

[English README](README.md)

**외부 연동을 의도적으로 깨뜨리고, 최종 업무 상태가 올바르게 수렴하는지 증명합니다.**

OutcomeProbe는 외부 HTTP API나 Webhook이 모호하게 실패했을 때 애플리케이션이 어떻게
동작하는지 검증하는 self-hosted 워크벤치입니다. Provider가 실제로 확정한 상태, 호출자가
관측한 HTTP 결과, Callback 전달 결과, Target 애플리케이션의 최종 상태를 서로 분리해
기록하고 판정합니다.

> 현재 단계: v0.1.0-rc. 지원하는 운영 기준선은 로컬·테스트·CI 환경의 단일 운영자,
> 단일 Server/PostgreSQL 구성입니다. 인터넷에 노출하는 운영 서비스가 아닙니다.
>
> 이 공개 저장소는 source-only입니다. Checkout한 source에서 Server와 CLI를 빌드해야 하며,
> 이 release candidate에는 사전 빌드 공개 artifact가 포함되지 않습니다.

## 왜 필요한가

일반적인 Mock은 주로 정상 흐름을 검증합니다.

~~~text
요청 → 200 OK → Webhook → 완료
~~~

실제 외부 연동은 서로 다른 경계에서 실패합니다.

~~~text
Provider는 성공 commit → HTTP 응답은 유실
Callback v2 도착 → 과거 Callback v1이 나중에 도착
같은 Callback이 세 번 도착
Callback 누락 → 애플리케이션이 PROCESSING에 멈춤
~~~

OutcomeProbe는 다음 네 가지 사실을 따로 모델링합니다.

1. **Provider truth** — Provider가 실제로 확정한 상태
2. **HTTP observation** — Target 애플리케이션이 호출 결과로 관측한 값
3. **Callback delivery** — Webhook의 지연·중복·역순·누락·서명 오류
4. **Target final state** — 애플리케이션이 최종적으로 믿고 있는 업무 상태

Run은 설정된 최종 상태 assertion이 통과해야만 성공합니다. HTTP 2xx만으로 업무 성공을
판정하지 않습니다.

## 제공 기능

- 상태를 먼저 commit한 뒤 HTTP 응답을 유실·지연·오류로 바꿀 수 있는 Provider Emulator
- 중복·지연·역순·누락·서명 오류를 결정적으로 구성하는 Callback 전달
- eventual consistency를 검사하는 읽기 전용 HTTP/JSON State Probe
- 버전이 있는 개별 YAML Artifact: ProviderContract, TargetProfile, Scenario
- Target 요청의 host·port·path·method allowlist
- 환경변수 또는 read-only secret directory를 사용하는 secretRef
- 운영자용 Web UI와 CI용 CLI
- JSON, standalone HTML, JUnit XML, Timeline Evidence, 엄격한 Run Bundle
- PostgreSQL 기반 Run 상태, lease, CAS, idempotency, append-only Evidence 경계

0.1.0 core는 ScenarioPack archive와 directory import를 지원하지 않습니다. Artifact는
개별 YAML 파일로 검증하고 import합니다.

## Docker-only 빠른 시작

### 준비물

- Docker Engine 또는 Docker Desktop
- Docker Compose 2.30 이상
- 아래 clone 명령을 사용할 때만 Git

Source를 받은 뒤 Quick Start에는 host Java, Gradle, Node.js, Bash, curl, jq가 필요하지
않습니다. Git 대신 GitHub source download를 사용할 수도 있지만 압축을 푼 저장소
root에서 명령을 실행해야 합니다.

### 1. 공개 Source 저장소 Clone

~~~bash
git clone https://github.com/krait4g/outcomeprobe-public.git
cd outcomeprobe-public
~~~

### 2. 로컬 설정 만들기

Linux 또는 macOS:

~~~bash
cp .env.example .env
~~~

Windows PowerShell:

~~~powershell
Copy-Item .env.example .env
~~~

.env에서 다음 세 값을 서로 다른 임의의 난수 값으로 설정합니다.

- OUTCOMEPROBE_DB_PASSWORD
- OUTCOMEPROBE_DEMO_CALLBACK_SECRET
- OUTCOMEPROBE_CONTROL_API_TOKEN

운영자 token은 32~512자의 token68 문자열이어야 합니다. .env를 Git에 추가하지 말고 실제
Target 서비스 자격 증명과 값을 공유하지 마세요.

### 3. Stack 기동과 Reference Artifact import

~~~bash
docker compose up -d --build --wait --wait-timeout 600
docker compose run --rm -T --no-deps --build outcomeprobe-bootstrap
~~~

첫 명령은 PostgreSQL, Server, Web UI, 취약·복원력 Reference Consumer를 기동합니다.
두 번째 명령은 ProviderContract 1개, TargetProfile 2개, 결제 Scenario 2개를 검증하고
import합니다. 같은 내용으로 다시 실행해도 중복 생성하지 않으며 성공 후 bootstrap
container를 제거합니다.

### 4. 대표 여정 실행

1. http://127.0.0.1:3000을 엽니다.
2. .env의 OUTCOMEPROBE_CONTROL_API_TOKEN 값을 입력합니다.
3. 취약 Consumer에 timeout-after-commit을 실행하고 FAILED를 확인합니다. Provider는
   commit했지만 Target이 상태를 복구하지 못했으므로 의도한 제품 판정입니다.
4. 같은 Scenario를 복원력 Consumer에 실행하고 PASSED를 확인합니다.
5. Run 상세에서 Timeline을 확인하고 JSON, JUnit, HTML 또는 Bundle을 내려받습니다.

Overview의 대표 버튼은 timeout-after-commit 흐름입니다. Callback 중복 처리는
payment-success-callback-duplicate Scenario 상세에서 두 Target에 각각 실행합니다. 취약
Target은 동일 side effect를 여러 번 반영하므로 실패해야 하고, 복원력 Target은 event를
중복 제거하므로 통과해야 합니다.

### 5. 종료

PostgreSQL 데이터를 보존하는 일반 종료:

~~~bash
docker compose down --remove-orphans
~~~

의도적으로 빈 DB에서 다시 시작할 때만 사용하는 데이터 삭제:

~~~bash
docker compose down --volumes --remove-orphans
~~~

두 번째 명령은 PostgreSQL volume을 영구 삭제합니다. 일반 재시작이나 첫 번째 문제 해결
명령으로 사용하지 마세요.

자세한 문제 해결은 [QUICKSTART.md](QUICKSTART.md)를 참고하세요.

## HTTP/JSON 서비스 연결

OutcomeProbe Java 코드를 수정하지 않고도 서비스를 연결할 수 있지만 Target은 다음 테스트
계약을 제공해야 합니다.

- 외부 Provider base URL을 OutcomeProbe Emulator로 바꿀 수 있어야 합니다.
- 업무 동작을 시작하는 HTTP endpoint가 있어야 합니다.
- 최종 상태를 관측하는 읽기 전용 HTTP GET endpoint가 있어야 합니다.
- Callback Scenario에는 Webhook endpoint가 필요합니다.
- OutcomeProbe와 Target이 선택한 테스트 network에서 서로 접근 가능해야 합니다.
  Container 안의 localhost는 해당 container 자신을 뜻합니다.
- Target credential은 secretRef로 제공해야 합니다.
- 운영 데이터가 아닌 격리된 테스트 데이터만 사용해야 합니다.

기본 연결 순서는 다음과 같습니다.

1. ProviderContract에 Provider operation과 Callback 계약을 정의합니다.
2. TargetProfile에 Target base URI와 최소 권한 network allowlist를 정의합니다.
3. Scenario에 trigger, 장애, Callback 일정, Probe, 업무 assertion을 정의합니다.
4. 세 YAML을 각각 validate하고 import합니다.
5. TargetProfile을 verify한 뒤 Scenario를 실행하고 판정과 Evidence를 확인합니다.

저장소 소유 realtime 예제는 단일 이벤트, 중복 이벤트, 역순 전달을 보여 줍니다.

[examples/realtime-track/README.md](examples/realtime-track/README.md)

전체 연결·network 계약은
[docs/integration-guide.md](docs/integration-guide.md)를 참고하세요.

## CI에서 CLI 사용

현재 source로 CLI를 빌드합니다.

~~~bash
./gradlew :cli:shadowJar
~~~

서버와 같은 운영자 token을 환경변수로 전달하고 개별 Artifact를 실행합니다.

~~~bash
export OUTCOMEPROBE_CONTROL_API_TOKEN='<서버와 같은 값>'

java -jar cli/build/libs/outcomeprobe-cli-0.1.0.jar doctor
java -jar cli/build/libs/outcomeprobe-cli-0.1.0.jar import provider-contract.yaml
java -jar cli/build/libs/outcomeprobe-cli-0.1.0.jar import target-profile.yaml
java -jar cli/build/libs/outcomeprobe-cli-0.1.0.jar run-file scenario.yaml \
  --target my-app \
  --wait \
  --junit build/test-results/outcomeprobe.xml
~~~

CLI는 권한을 제한한 --token-file도 지원합니다. token을 URL이나 명령행 token 인수에 넣지
마세요.

CI 종료 코드 계약:

| 코드 | 의미 |
|---:|---|
| 0 | 모든 assertion 통과 |
| 1 | Scenario assertion 실패 |
| 2 | CLI 또는 Artifact 입력 오류 |
| 3 | Server·network·OutcomeProbe 오류 |
| 4 | Run 취소 |

공개 v0.1.0-rc 저장소는 source-only입니다. Checkout한 source에서 CLI를 빌드하며, 사전 빌드된
공개 CLI artifact를 제공한다고 주장하지 않습니다.

## 아키텍처

OutcomeProbe는 Modular Monolith입니다. Scenario compile, 실행, Provider Emulator,
Callback 전달, Probe, Evidence, Report, Target Profile을 명시적인 모듈로 분리하면서 하나의
Server와 PostgreSQL을 공유합니다.

초기 버전에서 분산 control plane을 추가하지 않고 transaction, row lock, lease, Evidence
정합성을 코드와 SQL에서 드러내기 위한 선택입니다. 대가로 현재 운영 기준선은 Server process
하나와 PostgreSQL 하나이며 HA를 주장하지 않습니다.

## 보안·운영 경계

기본 Compose는 Web UI와 Server만 127.0.0.1에 publish합니다. PostgreSQL과 두 Reference
Consumer는 host port를 열지 않습니다.

Control API와 Prometheus는 하나의 static Bearer 운영자 token을 요구합니다. Health와 info는
로컬 probe를 위해 공개되고 Emulator ingress는 별도의 선택 token을 사용합니다. 이는 최소
단일 운영자 경계이며 범용 인증 시스템이 아닙니다.

기본 stack을 인터넷에 직접 노출하지 마세요. 현재 제공하지 않는 기능은 다음과 같습니다.

- TLS termination, OIDC, RBAC, multitenancy, rate limit, 사용자별 감사
- 두 token을 동시에 허용하는 무중단 rotation
- Production traffic gateway
- HA와 PostgreSQL 자동 failover
- 지원 계약으로서의 custom CA 또는 mTLS 연동
- Scenario YAML의 사용자 script·shell 실행
- Callback exactly-once 보장

서비스를 연결하기 전에 [SECURITY.md](SECURITY.md)를 확인하세요.

## 지원 범위

| v0.1.0-rc 지원 | 주장하지 않는 범위 |
|---|---|
| 로컬과 통제된 CI | 인터넷에 노출한 Production hosting |
| 단일 운영자·Server·PostgreSQL | HA, failover, RBAC, multitenancy |
| HTTP/JSON Target 호출과 GET State Probe | Kafka·MQTT·gRPC·TCP·DB·SSH 전용 Target |
| Static Bearer header와 HMAC Callback 예제 | 동적 OAuth, custom CA, mTLS |
| 포함된 결제 Reference flow | 실제 결제·예약 처리 |
| 저장소 소유 HTTP/JSON conformance fixture | 독립적인 운영 사용자의 검증 |
| at-least-once Callback 동작 검증 | exactly-once 전달 보장 |

## 문서

- [Docker-only Quick Start](QUICKSTART.md)
- [외부 Target 연결](docs/integration-guide.md)
- [보안 정책](SECURITY.md)
- [기여 가이드](CONTRIBUTING.md)

## 개발

~~~bash
./gradlew clean build
npm --prefix web ci
npm --prefix web run test
npm --prefix web run build
~~~

Docker 기반 integration·E2E 검증에는 Docker가 필요합니다. Pull Request를 열기 전에
[CONTRIBUTING.md](CONTRIBUTING.md)를 확인하세요.

## 라이선스

Apache License 2.0입니다. 제3자 고지는
[THIRD-PARTY-NOTICES](THIRD-PARTY-NOTICES)와
[THIRD-PARTY-LICENSES.json](THIRD-PARTY-LICENSES.json)에 있습니다.
