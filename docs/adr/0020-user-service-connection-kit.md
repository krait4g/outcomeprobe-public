# ADR 0020: HTTP/JSON 서비스 연결을 fixture-backed conformance kit로 제공한다

- 상태: 승인
- 적용 버전: `v0.1.0-rc`
- 날짜: 2026-08-13

## 맥락

Target Profile, `secretRef`, State Probe의 개념만 설명해서는 사용자가 자신의 서비스 연결을
안전하게 재현하기 어렵다. Artifact가 현행 schema와 맞는지, OutcomeProbe server와 Target이
Docker network의 양방향 HTTP 경계에서 통신하는지, 중복·역순 입력 뒤 최종 상태가 수렴하는지
실행 가능한 예제로 확인할 필요가 있다.

반대로 저장소 밖의 운영 서비스나 실제 자격 증명을 프로젝트 검증에 연결하면 재현성과
안전성을 잃는다. 프로젝트가 관리하지 않는 서비스의 가용성이나 동작을 보증할 수도 없다.

## 결정

### 1. fixture-backed conformance kit

`examples/realtime-track`을 source-only 배포판에 포함한다. 이 키트는 사용자 서비스와 같은
network·HTTP 경계를 사용하는 독립 Target fixture로 다음 흐름을 검증한다.

1. 단일 event가 최종 조회 상태로 수렴한다.
2. 같은 event를 세 번 받아도 식별자와 side effect가 하나로 수렴한다.
3. 최신 event 뒤 도착한 과거 event가 최종 상태를 되돌리지 않는다.

Artifact 계약은 ProviderContract 1개, TargetProfile 1개, Scenario 3개다. 모든 Artifact는
개별 YAML이며 bundle manifest나 directory import를 도입하지 않는다.

키트 통과는 OutcomeProbe와 저장소가 제공한 fixture 사이의 HTTP/JSON 계약이 성립한다는
뜻이다. 제3자 서비스, 사용자의 운영 배포, production traffic 또는 외부 사용자 환경을
검증했다는 뜻이 아니다.

### 2. network와 실행 경계

기본 payment Quick Start는 그대로 유지한다. 사용자가 `compose.realtime-track.yaml`을
명시적으로 추가한 경우에만 Target fixture와 일회성 kit runner를 생성한다.

- Target이 Provider Emulator를 호출하는 URL과 OutcomeProbe server가 Target을 호출하는
  Target Profile URL은 서로 다른 방향의 경계다.
- 두 방향 모두 Compose service DNS와 명시 port를 사용한다. 컨테이너 안의 `localhost`를
  다른 service 주소로 사용하지 않는다.
- Target host port는 공개하지 않는다. server와 Target은 명시적으로 공유한 `app` network에서만
  통신한다.
- 상태 판정 endpoint는 read-only이며 fixture에서만 활성화한다. 전체 내부 상태나 secret은
  응답하지 않는다.
- 기본 Compose의 server·Web loopback bind, PostgreSQL 비공개 network와 단일 운영자 인증
  경계를 유지한다.

### 3. secret과 입력 경계

Control API token, Emulator ingress token, Target 인증 값과 callback 서명 값은 서로 다른
목적이다. YAML에는 secret 원문을 넣지 않고 Target Profile의 `secretRef`만 둔다. 실제 값은
Git에서 제외한 환경변수 또는 read-only mounted file로 해석한다.

검증기는 Artifact, Run, sanitized report·JUnit·bundle, summary와 service log에서 token,
Authorization 값, password와 callback key 원문이 발견되지 않는지 검사한다. raw HTTP trace,
브라우저 저장소, 환경변수 dump와 secret 원문은 결과 디렉터리에 저장하지 않는다.

### 4. 소스 구성과 검증 결과

source-only 배포판에는 다음 파일을 함께 제공한다.

- 이 ADR과 [외부 Target 연결 가이드](../integration-guide.md)
- secret 환경변수·directory mount 예시
- realtime Target·kit runner Docker source와 opt-in Compose overlay
- schema-valid 1/1/3 Artifact와 [실행 가이드](../../examples/realtime-track/README.md)
- 결과를 생성하고 읽기 전용으로 재검증하는 script

성공한 검증 결과 디렉터리는 summary 1개와
`runs/<scenario-name>/{report.json,junit.xml,bundle.zip}` 9개만 가진다. verifier는 다음을
fail-closed로 확인한다.

- Artifact validation·import 수량은 1/1/3이며 세 Scenario의 Run ID는 서로 다르고 상태는
  모두 `PASSED`다.
- single, duplicate, out-of-order의 최종 business 값이 각 Scenario의 assertion과 일치한다.
- 누락되거나 잘못된 Target 인증은 401로 거부되고, 정상 callback HMAC은 통과하며 위조된
  HMAC은 상태를 바꾸지 않는다.
- 각 파일의 SHA-256을 실제 byte에서 다시 계산한다.
- 결과는 일반 파일 10개 allowlist와 크기 제한을 지키며, ZIP entry는 상대 안전 경로만 쓴다.
- bundle과 정규화 문서에 plaintext secret이 없다.
- 키트가 만든 container, network, volume과 임시 자격 증명이 실행 뒤 남지 않는다.

실제 사용자 URL, credential, service log, HTTP trace, raw timeline과 브라우저 저장소는
검증 결과에 포함하지 않는다. 사용자는 자신의 격리 환경에서 주소·allowlist·`secretRef`와
read-only business assertion을 교체한 뒤 같은 세 흐름을 실행한다.

## 결과

- 저장소의 소스만으로 연결 예제와 보안 설정을 함께 재현할 수 있다.
- payment Quick Start와 HTTP/JSON conformance kit는 opt-in Compose overlay로 분리된다.
- 실제 외부 서비스 연결은 사용자가 관리하는 별도 통합 범위로 남는다.
- HTTP/JSON 이외 프로토콜, production ingress, TLS·OIDC·RBAC, 멀티테넌시와 실제 사용자
  서비스의 가용성은 이 결정의 지원 범위가 아니다.

## 대안

### 문서만 제공

Artifact가 현행 schema에서 동작하는지, Docker 양방향 network와 결과 export가 실제로
연결되는지 검증하지 못하므로 채택하지 않았다.

### 외부 운영 서비스를 프로젝트 검증에 직접 연결

재현할 수 없고 자격 증명·가용성·데이터 경계가 섞이므로 채택하지 않았다.

### 기본 Quick Start에 Target fixture를 항상 추가

두 명령과 다섯 service로 구성된 payment 입문 경로를 복잡하게 만들므로 opt-in overlay로
분리했다.
