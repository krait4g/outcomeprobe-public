# OutcomeProbe v0.1.0-rc Realtime Target 연결 키트

이 디렉터리는 OutcomeProbe를 실제 사용자 서비스와 같은 HTTP 경계에 연결하는 최소 예제입니다.
키트는 공개 예제 5개(ProviderContract 1, TargetProfile 1, Scenario 3), opt-in Compose
overlay, 비 root 일회성 CLI 컨테이너, 자체 포함 Target fixture로 구성됩니다.

## 빠른 검증

저장소 루트에서 Docker Engine, Docker Compose v2, Git, Bash, Python 3가 있는 환경에서 실행합니다.
검증기는 서로 다른 임시 자격 증명을 생성하고, 세 시나리오를 순차 실행하고, Compose 리소스와
임시 자격 증명을 정리한 뒤 정규화된 증거만 지정한 디렉터리에 남깁니다.

```bash
scripts/user-service-kit/verify-user-service-kit.sh \
  --repository "$PWD" \
  --output "$PWD/build/user-service-kit-evidence" \
  --source-sha "$(git rev-parse HEAD)"
```

`--output`은 아직 존재하지 않는 디렉터리여야 합니다. 이전 결과를 보존하려면 실행마다 다른
경로를 지정하세요.

`--source-sha`는 결과 metadata에 기록할 40자 또는 64자 소문자 hex 식별자입니다. 일반적인
저장소 실행에서는 위와 같이 현재 `HEAD`를 전달합니다. 이 값은 사용자가 전달한 label이므로
검증기만으로 Git provenance나 변경 파일 부재를 증명하지는 않습니다.

이미 생성된 증거는 Docker 없이 읽기 전용으로 다시 검증할 수 있습니다.

```bash
scripts/user-service-kit/verify-user-service-kit.sh \
  --verify-summary build/user-service-kit-evidence/user-service-kit-summary.json \
  --source-sha "$(git rev-parse HEAD)"
```

## 키트 Artifact

- `provider-contract.yaml`: `realtime-track@1.0.0`, raw-body HMAC-SHA256 callback 계약
- `target-profile.compose.yaml`: `realtime-track-compose`, 정확한 DNS alias/port/path/method allowlist
- `scenario-single-event.yaml`: Target token, 정상 HMAC, 위조 HMAC 거부와 상태 불변성
- `scenario-duplicate-3x.yaml`: 동일 callback 3회 수신, 한 번만 처리/적용
- `scenario-out-of-order.yaml`: 최신 event 이후 도착한 과거 event가 상태를 되돌리지 않음

Target Profile은 `http://realtime-track-target:8181`만 허용합니다. `Authorization` 값은
`realtime-target-token` secretRef로, callback 서명 키는
`realtime-callback-signing-key` secretRef로만 참조합니다. 값 자체는 YAML, report, JUnit,
bundle, summary에 저장하지 않습니다.
검증기는 Target의 Authorization 누락과 고정된 잘못된 값을 각각 401로 거부하는지도 확인합니다.

## Compose 경계

`compose.realtime-track.yaml`은 기본 `compose.yaml`에 명시적으로 추가하는 opt-in overlay입니다.
Target은 `app` 네트워크의 `realtime-track-target` alias에서만 수신하며 host port를 publish하지
않습니다. Target과 일회성 CLI는 비 root, read-only root filesystem, dropped capabilities,
`no-new-privileges`, bounded tmpfs/pid 설정으로 실행됩니다.

직접 구성할 때 필요한 값은 모두 서로 다른 32자 이상의 secret이어야 합니다.

```bash
export OUTCOMEPROBE_DB_PASSWORD='replace-with-at-least-32-random-characters-01'
export OUTCOMEPROBE_DEMO_CALLBACK_SECRET='replace-with-at-least-32-random-characters-02'
export OUTCOMEPROBE_CONTROL_API_TOKEN='replace-with-at-least-32-random-characters-03'
export OUTCOMEPROBE_REALTIME_TARGET_TOKEN='replace-with-at-least-32-random-characters-04'
export OUTCOMEPROBE_REALTIME_CALLBACK_SECRET='replace-with-at-least-32-random-characters-05'
docker compose -f compose.yaml -f compose.realtime-track.yaml \
  up -d --build --wait --wait-timeout 600 \
  postgres realtime-track-target outcomeprobe-server
```

포함된 검증기는 위 수동 절차, 일회성 CLI 실행, 결과 정규화, secret scan, cleanup을 하나의
fail-closed 작업으로 수행하므로 재현 가능한 결과가 필요할 때 사용을 권장합니다.

## 검증 결과 계약

성공한 output은 symlink 없는 정확히 10개 파일만 포함합니다.

```text
user-service-kit-summary.json
runs/single/{report.json,junit.xml,bundle.zip}
runs/duplicate/{report.json,junit.xml,bundle.zip}
runs/out-of-order/{report.json,junit.xml,bundle.zip}
```

summary는 `schemaVersion: 1`, `result: PASS`, `sourceSha`, 1/1/3 artifact inventory,
서로 다른 run ID 3개, 각 파일의 SHA-256과 0 container/network/volume cleanup을 기록합니다.
report와 JUnit은 canonical allowlist 필드만 가진 정규화 문서이며,
각 scenario의 `business` 필드는 single-event의 위조 서명 거부와 정상 처리, duplicate의
3회 수신/1회 적용, out-of-order의 최신 version/좌표 보존을 exact 값으로 고정합니다.
각 bundle은 `manifest.json`, 같은 `report.json`, 같은 `junit.xml`만 포함합니다. raw timeline,
request/response log, token, callback secret은 output에 포함되지 않습니다. 읽기 전용 검증은
파일 수, 크기, symlink, ZIP path/expanded size, canonical bytes, SHA-256을 다시 확인합니다.

## Fixture 한계와 실제 서비스 전환

`target-fixture/`는 표준 라이브러리만 사용하는 메모리 기반 black-box 테스트 서버입니다.
운영 서비스, 영속 저장소, 멀티 테넌시 또는 인터넷 노출을 위한 구현이 아닙니다. fixture의
HTTP는 Docker 내부 `app` 네트워크로만 제한되어 TLS를 생략합니다. 실제 서비스 연결 시에는
HTTPS 인증서 검증, 운영 전용 최소 권한 자격 증명, egress/DNS allowlist, callback replay 방지,
rate limit, 감사 로그 redaction, 운영과 분리된 read-only probe를 별도로 적용해야 합니다.

실패 시 검증기는 summary를 만들지 않고 원인을 출력한 뒤 Compose 리소스를 정리합니다. 수동으로
시작한 리소스는 다음 명령으로 제거할 수 있습니다.

```bash
docker compose -f compose.yaml -f compose.realtime-track.yaml down --volumes --remove-orphans
unset OUTCOMEPROBE_DB_PASSWORD OUTCOMEPROBE_DEMO_CALLBACK_SECRET
unset OUTCOMEPROBE_CONTROL_API_TOKEN OUTCOMEPROBE_REALTIME_TARGET_TOKEN
unset OUTCOMEPROBE_REALTIME_CALLBACK_SECRET
```
