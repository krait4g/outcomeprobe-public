# OutcomeProbe v0.1.0-rc 공개 소스 범위

이 저장소는 OutcomeProbe `v0.1.0-rc`의 source-only 배포판입니다. 문서의 실행 절차는
미리 빌드된 바이너리나 컨테이너 이미지를 전제로 하지 않으며, 저장소의 소스와 Dockerfile에서
필요한 구성 요소를 직접 빌드합니다.

## 포함 범위

- server, CLI, Web, Reference Consumer의 빌드 가능한 소스
- Gradle wrapper, version catalog, Checkstyle 및 Web 테스트 설정
- PostgreSQL, server, Web, 취약·복원력 Reference Consumer를 실행하는 Docker Compose 구성
- payment 골든 패스를 등록하는 non-root transient bootstrap
- `ProviderContract`, `TargetProfile`, `Scenario` 개별 YAML 예제
- 별도 opt-in 방식의 fixture-backed HTTP/JSON 서비스 연결 키트
- 구성·네트워크·secret 경계를 점검하는 검증 스크립트

빠르게 기능을 확인하려면 [Docker-only Quick Start](QUICKSTART.md)를 사용합니다. 자신의
HTTP/JSON 서비스에 연결하려면 [외부 Target 연결 가이드](docs/integration-guide.md)와
[`examples/realtime-track`](examples/realtime-track/README.md) 예제를 사용합니다.

## Artifact 계약

지원되는 Artifact 종류는 다음 세 가지입니다.

- `ProviderContract`: 외부 Provider 동작과 Emulator 규칙
- `TargetProfile`: 검사 대상의 base URI, 허용 경로·메서드, secret 참조
- `Scenario`: fault 주입, callback, probe 및 최종 판정 절차

Contract, Profile, Scenario는 각각의 YAML 파일로 명시적으로 validate/import합니다. 이 버전은
bundle manifest, 디렉터리 import, Contract·Scenario 자동 등록을 지원하지 않습니다. 지원하지
않는 kind나 입력 형태는 저장하거나 일부만 처리하지 않고 입력 오류로 거부합니다.

## Payment 골든 패스

`examples/golden-path/payment`에는 다음 두 장애 흐름을 위한 다섯 개 Artifact가 있습니다.

1. `payment-timeout-after-commit`: Provider가 작업을 commit한 뒤 응답이 유실된 상황에서
   Target이 Provider 조회 Probe로 최종 상태에 수렴하는지 검사합니다.
2. `payment-success-callback-duplicate`: 같은 성공 callback을 세 번 전달했을 때 Target의
   business side effect가 한 번만 적용되는지 검사합니다.

각 Scenario를 `vulnerable-consumer`와 `resilient-consumer`에 실행해 동작 차이를 비교합니다.
취약 Target의 `FAILED`는 제품 실행 오류가 아니라 재현하려는 결함이 검출되었다는 판정이며,
복원력 Target은 최종 상태와 side effect 불변 조건을 만족해 `PASSED`가 되어야 합니다.

## HTTP/JSON 서비스 연결 키트

`examples/realtime-track`은 기본 payment 환경과 분리된 opt-in conformance kit입니다.
ProviderContract 1개, TargetProfile 1개, Scenario 3개로 단일 입력, 세 번의 중복 입력, 역순
입력의 최종 상태 수렴을 검사합니다. 저장소가 제공하는 black-box Target fixture를 사용하므로
사용자가 자신의 서비스에 연결하기 전에 network·인증·HMAC·probe 계약을 재현할 수 있습니다.

이 키트의 통과는 공개된 fixture와 OutcomeProbe 사이의 HTTP/JSON 계약이 성립한다는 뜻입니다.
제3자 서비스나 사용자의 운영 배포가 검증되었다는 뜻은 아닙니다. 실제 적용 시 Profile의
Docker-visible 주소, allowlist, `secretRef`와 read-only business assertion을 자신의 격리된
환경에 맞게 교체해야 합니다.

## 런타임과 보안 경계

Docker Quick Start는 `.env`의 세 필수 secret을 서로 다른 값으로 설정한 뒤 다음 두 명령으로
실행합니다.

```bash
docker compose up -d --build --wait --wait-timeout 600
docker compose run --rm -T --no-deps --build outcomeprobe-bootstrap
```

첫 명령은 장기 실행 service 다섯 개를 기동하고, 두 번째 명령은 다섯 payment Artifact를
validate/import한 뒤 bootstrap 컨테이너를 제거합니다. bootstrap을 다시 실행해도 같은 content
hash의 Artifact는 중복 생성하지 않습니다. Web은 bootstrap 전의 빈 상태와 조회 오류를 서로
다르게 표시하며, 저장된 운영자 token을 서버가 확인하기 전에는 인증 완료로 취급하지 않습니다.

server는 시작할 때 32~512자의 제한된 token68 문자열인
`OUTCOMEPROBE_CONTROL_API_TOKEN`을 요구합니다. 모든 `/api/v1/**` 요청과 Prometheus 요청은
이 값을 `Authorization: Bearer`로 전달해야 합니다. health/info endpoint는 health probe를 위해
공개되며, Emulator의 선택적 ingress token과 Target secret은 운영자 token과 분리합니다.
CLI는 같은 환경변수 또는 권한을 제한한 `--token-file`을 사용합니다. token 원문을 URL, 명령행
option, YAML 또는 로그에 넣지 마세요.

기본 Compose에서 server와 Web만 `127.0.0.1`에 bind됩니다. PostgreSQL과 두 Reference
Consumer는 host port를 열지 않고 Compose network 안에서만 접근합니다. 이 단일 운영자 구성은
TLS, OIDC, RBAC, 멀티테넌시, rate limiting 또는 HA를 제공하지 않으므로 인터넷에 직접
노출하는 운영 구성이 아닙니다. 공유 환경에서는 인증 reverse proxy, TLS 및 network policy를
별도로 구성해야 합니다.

## source-only 사용 계약

- Docker Quick Start에는 Docker Engine 또는 Docker Desktop과 Docker Compose 2.30 이상만
  필요합니다.
- CLI를 host에서 사용하려면 JDK 21로 `./gradlew :cli:shadowJar`를 실행한 뒤
  `java -jar cli/build/libs/outcomeprobe-cli-0.1.0.jar ...` 형태로 실행합니다.
- Web과 server를 host에서 개발하려면 각 모듈의 소스 빌드 절차를 따릅니다.
- booking·generic 예제 코드는 기본 bootstrap과 지원되는 payment 골든 패스에 포함되지 않습니다.
- 실제 외부 Target은 사용자가 관리하는 별도 통합 범위입니다. 자격 증명과 운영 데이터는 이
  저장소나 검증 결과에 포함하지 마세요.
