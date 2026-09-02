#!/usr/bin/env bash
set -Eeuo pipefail

# Compose 운영 계약을 실제 컨테이너로 검증한다. 모든 생성 자원은 고유 project label로
# 소유권을 한정하고, 임시 credential 원문은 stdout/stderr에 쓰지 않는다.

repository=${1:-.}
compose_file=${2:-compose.yaml}
summary_output=${3:-}
web_e2e_enabled=${OUTCOMEPROBE_WEB_E2E_ENABLED:-false}
web_e2e_source_sha=${OUTCOMEPROBE_WEB_E2E_SOURCE_SHA:-}

fail() {
  printf '[FAIL] %s\n' "$1" >&2
  exit 1
}

pass() {
  printf '[PASS] %s\n' "$1"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "필수 명령을 찾을 수 없습니다: $1"
}

python_command=${PYTHON_COMMAND:-python3}

for command_name in awk bash cmp cp curl docker find grep jq mktemp mv rm sha256sum tail tr "$python_command"; do
  require_command "$command_name"
done

case "$web_e2e_enabled" in
  true)
    require_command node
    require_command npm
    [[ "$web_e2e_source_sha" =~ ^[0-9a-f]{40}$ ]] || fail 'Web UX evidence requires an exact lowercase source SHA.'
    [[ -n "$summary_output" ]] || fail 'Web UX evidence requires a summary output root.'
    ;;
  false) ;;
  *) fail 'OUTCOMEPROBE_WEB_E2E_ENABLED must be true or false.' ;;
esac

repository=$(cd "$repository" && pwd -P)
[[ -f "$repository/$compose_file" ]] || fail "Compose 파일을 찾을 수 없습니다: $compose_file"
cd "$repository"

if [[ -e "$repository/.env.secrets" || -L "$repository/.env.secrets" ]]; then
  fail 'runtime 계약 검증은 실제 .env.secrets가 없는 clean checkout에서만 실행할 수 있습니다.'
fi

# 호출자 job의 OUTCOMEPROBE_* 값이 --env-file보다 높은 우선순위로 침투하지 않게 한다.
sanitized_environment=(env)
while IFS= read -r variable_name; do
  case "$variable_name" in
    OUTCOMEPROBE_*) sanitized_environment+=(-u "$variable_name") ;;
  esac
done < <(compgen -e)

docker info >/dev/null 2>&1 || fail 'Docker daemon에 연결할 수 없습니다.'
docker compose version >/dev/null 2>&1 || fail 'Docker Compose를 사용할 수 없습니다.'

umask 077
temporary_parent=${TMPDIR:-/tmp}
temporary_root=$(mktemp -d "$temporary_parent/outcomeprobe-compose-contract.XXXXXX")
case "$temporary_root" in
  "$temporary_parent"/outcomeprobe-compose-contract.*) ;;
  *) fail '임시 디렉터리 경계가 올바르지 않습니다.' ;;
esac

summary_parent=
summary_staging=
web_ux_summary_staging=
web_ux_summary_output=
payment_network_evidence_staging=
payment_network_evidence_output=
payment_network_evidence_published=0
summary_published=0
web_ux_summary_published=0

if [[ -n "$summary_output" ]]; then
  summary_parent=$(dirname -- "$summary_output")
  mkdir -p -- "$summary_parent"
  summary_parent=$(cd "$summary_parent" && pwd -P)
  summary_output="$summary_parent/$(basename -- "$summary_output")"
  case "$summary_output" in
    "$temporary_root"|"$temporary_root"/*)
      fail '요약 증거는 검증용 임시 디렉터리 밖에 기록해야 합니다.'
      ;;
  esac
  [[ ! -e "$summary_output" && ! -L "$summary_output" ]] || \
    fail '요약 증거 출력 경로가 이미 존재합니다.'
  payment_network_evidence_output="$summary_parent/payment-network-runs"
  if [[ "$web_e2e_enabled" == true ]]; then
    web_ux_summary_output="$summary_parent/web-ux-summary.json"
    [[ ! -e "$web_ux_summary_output" && ! -L "$web_ux_summary_output" ]] || \
      fail 'Web UX receipt output already exists.'
  fi
  [[ ! -e "$payment_network_evidence_output" && ! -L "$payment_network_evidence_output" ]] || \
    fail 'payment network 증거 출력 경로가 이미 존재합니다.'
fi

remove_temporary_root() {
  case "$temporary_root" in
    "$temporary_parent"/outcomeprobe-compose-contract.*)
      rm -rf -- "$temporary_root" || return 1
      [[ ! -e "$temporary_root" && ! -L "$temporary_root" ]]
      ;;
    *) return 1 ;;
  esac
}

cleanup_temporary_only() {
  local primary_status=$?
  trap - EXIT INT TERM
  if ! remove_temporary_root; then
    printf '[FAIL] 임시 디렉터리를 안전하게 정리하지 못했습니다.\n' >&2
    [[ $primary_status -ne 0 ]] || primary_status=1
  fi
  exit "$primary_status"
}

trap cleanup_temporary_only EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

run_nonce=$("$python_command" -c 'import secrets; print(secrets.token_hex(10))' | tr -d '\r')
project="op-contract-$run_nonce"
environment_file="$temporary_root/runtime.env"
rendered_config="$temporary_root/compose-config.json"
profiled_config="$temporary_root/compose-config-profiled.json"
lifecycle_log="$temporary_root/compose-lifecycle.log"
demo_log="$temporary_root/demo-matrix.log"
payment_network_summary="$temporary_root/payment-network-summary.json"
payment_network_evidence="$temporary_root/payment-network-runs"
service_log="$temporary_root/service.log"
settings_json="$temporary_root/settings.json"
profiles_json="$temporary_root/target-profiles.json"
vulnerable_profile_verify="$temporary_root/vulnerable-profile-verify.json"
resilient_profile_verify="$temporary_root/resilient-profile-verify.json"
artifacts_json="$temporary_root/artifacts.json"
artifacts_after_rerun="$temporary_root/artifacts-after-rerun.json"
server_image_jar="$temporary_root/source-server.jar"
unauthorized_body="$temporary_root/emulator-unauthorized.json"
authorized_body="$temporary_root/emulator-authorized.json"
control_unauthorized_body="$temporary_root/control-unauthorized.json"
control_wrong_body="$temporary_root/control-wrong-token.json"
timeout_vulnerable_state="$temporary_root/timeout-vulnerable-state.json"
timeout_resilient_state="$temporary_root/timeout-resilient-state.json"
callback_vulnerable_state="$temporary_root/callback-vulnerable-state.json"
callback_resilient_state="$temporary_root/callback-resilient-state.json"
web_fresh_report="$temporary_root/web-fresh-report.json"
web_post_report="$temporary_root/web-post-report.json"
web_observations="$temporary_root/web-observations.json"
web_e2e_log="$temporary_root/web-e2e.log"
payment_network_summary_json=

db_password=$("$python_command" -c 'import secrets; print("db-" + secrets.token_hex(32))' | tr -d '\r')
callback_secret=$("$python_command" -c 'import secrets; print("callback-" + secrets.token_hex(32))' | tr -d '\r')
emulator_token=$("$python_command" -c 'import secrets; print("emulator-" + secrets.token_hex(32))' | tr -d '\r')
control_api_token=$("$python_command" -c 'import secrets; print("control-" + secrets.token_hex(32))' | tr -d '\r')
retention_days=23
max_concurrent_runs=3
db_name=contract_db
db_user=contract_user

readarray -t published_ports < <("$python_command" - <<'PY' | tr -d '\r'
import socket

sockets = []
try:
    for _ in range(2):
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.bind(("127.0.0.1", 0))
        sockets.append(sock)
    for sock in sockets:
        print(sock.getsockname()[1])
finally:
    for sock in sockets:
        sock.close()
PY
)
[[ ${#published_ports[@]} -eq 2 ]] || fail '임시 loopback port를 할당하지 못했습니다.'
server_port=${published_ports[0]}
web_port=${published_ports[1]}

{
  printf 'OUTCOMEPROBE_SERVER_PORT=%s\n' "$server_port"
  printf 'OUTCOMEPROBE_WEB_PORT=%s\n' "$web_port"
  printf 'OUTCOMEPROBE_DB_NAME=%s\n' "$db_name"
  printf 'OUTCOMEPROBE_DB_USER=%s\n' "$db_user"
  printf 'OUTCOMEPROBE_DB_PASSWORD=%s\n' "$db_password"
  printf 'OUTCOMEPROBE_DEMO_CALLBACK_SECRET=%s\n' "$callback_secret"
  printf 'OUTCOMEPROBE_CONTROL_API_TOKEN=%s\n' "$control_api_token"
  printf 'OUTCOMEPROBE_EMULATOR_TOKEN=%s\n' "$emulator_token"
  printf 'OUTCOMEPROBE_RETENTION_DAYS=%s\n' "$retention_days"
  printf 'OUTCOMEPROBE_MAX_CONCURRENT_RUNS=%s\n' "$max_concurrent_runs"
  printf 'OUTCOMEPROBE_DEMO_PROFILE_SEEDING_ENABLED=false\n'
  printf 'OUTCOMEPROBE_LOG_LEVEL=WARN\n'
} >"$environment_file"

compose=("${sanitized_environment[@]}" docker compose --project-name "$project" \
  --env-file "$environment_file" -f "$compose_file")

owned_ids() {
  local kind=$1
  case "$kind" in
    container)
      docker ps -aq --filter "label=com.docker.compose.project=$project"
      ;;
    network)
      docker network ls -q --filter "label=com.docker.compose.project=$project"
      ;;
    volume)
      docker volume ls -q --filter "label=com.docker.compose.project=$project"
      ;;
    *) return 2 ;;
  esac
}

owned_count() {
  local kind=$1
  local rows
  rows=$(owned_ids "$kind") || return 1
  if [[ -z "$rows" ]]; then
    printf '0\n'
  else
    awk 'NF { count++ } END { print count + 0 }' <<<"$rows"
  fi
}

collect_owned_ids() {
  local kind=$1
  local destination_name=$2
  local rows
  local -n destination=$destination_name
  destination=()
  rows=$(owned_ids "$kind") || return 1
  if [[ -n "$rows" ]]; then
    mapfile -t destination <<<"$rows"
  fi
}

print_redacted_tail() {
  local file=$1
  [[ -f "$file" ]] || return 0
  local line
  while IFS= read -r line; do
    line=${line//"$db_password"/<redacted>}
    line=${line//"$callback_secret"/<redacted>}
    line=${line//"$emulator_token"/<redacted>}
    line=${line//"$control_api_token"/<redacted>}
    printf '%s\n' "$line" >&2
  done < <(tail -n 120 "$file")
}

sha256_file() {
  local file=$1
  local digest
  digest=$(sha256sum "$file" | awk 'NR == 1 { print $1 }') || return 1
  [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || return 1
  printf '%s\n' "$digest"
}

assert_runtime_secret_free() {
  local grep_status
  if grep -Fq -f <(
      printf '%s\n' "$db_password" "$callback_secret" "$emulator_token" "$control_api_token"
    ) -- "$@"; then
    return 1
  else
    grep_status=$?
  fi
  [[ $grep_status -eq 1 ]]
}

verify_payment_network_assets() {
  local evidence_root=$1
  local network_json=$2
  local file_count directory_count node_count symlink_count
  local key scenario_key target_key expected_status expected_target expected_payment callback_event
  local record timeline_file report_file actual_timeline_hash actual_report_hash

  [[ -d "$evidence_root" && ! -L "$evidence_root" ]] || return 1
  file_count=$(find "$evidence_root" -mindepth 1 -type f -printf '.' |
    awk '{ total += length } END { print total + 0 }') || return 1
  directory_count=$(find "$evidence_root" -mindepth 1 -type d -printf '.' |
    awk '{ total += length } END { print total + 0 }') || return 1
  node_count=$(find "$evidence_root" -mindepth 1 -printf '.' |
    awk '{ total += length } END { print total + 0 }') || return 1
  symlink_count=$(find "$evidence_root" -mindepth 1 -type l -printf '.' |
    awk '{ total += length } END { print total + 0 }') || return 1
  [[ "$file_count" -eq 8 && "$directory_count" -eq 4 &&
     "$node_count" -eq 12 && "$symlink_count" -eq 0 ]] || return 1

  for key in timeout-vulnerable timeout-resilient callback-vulnerable callback-resilient; do
    case "$key" in
      timeout-vulnerable)
        scenario_key=timeoutAfterCommit
        target_key=vulnerable
        expected_status=FAILED
        expected_target=vulnerable-consumer
        expected_payment=pay-network-timeout-vulnerable
        callback_event=
        ;;
      timeout-resilient)
        scenario_key=timeoutAfterCommit
        target_key=resilient
        expected_status=PASSED
        expected_target=resilient-consumer
        expected_payment=pay-network-timeout-resilient
        callback_event=
        ;;
      callback-vulnerable)
        scenario_key=callbackDuplicate
        target_key=vulnerable
        expected_status=FAILED
        expected_target=vulnerable-consumer
        expected_payment=pay-network-callback-vulnerable
        callback_event=evt-pay-2001-success
        ;;
      callback-resilient)
        scenario_key=callbackDuplicate
        target_key=resilient
        expected_status=PASSED
        expected_target=resilient-consumer
        expected_payment=pay-network-callback-resilient
        callback_event=evt-pay-2001-success
        ;;
      *) return 1 ;;
    esac

    record=$(jq -cer --arg scenario "$scenario_key" --arg target "$target_key" \
      '.scenarios[$scenario][$target]' <<<"$network_json") || return 1
    jq -e --arg status "$expected_status" --arg target "$expected_target" \
      --arg payment "$expected_payment" \
      --arg timeline "payment-network-runs/$key/timeline.json" \
      --arg report "payment-network-runs/$key/report.json" \
      --arg callback "$callback_event" '
        (.runId | type == "string" and
          test("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) and
        .status == $status and .expectedStatus == $status and
        .targetProfile == $target and .paymentId == $payment and
        .timelinePath == $timeline and .reportPath == $report and
        (.timelineSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
        (.reportSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
        (if $callback == "" then .callbackEventId == null
         else .callbackEventId == $callback end)
      ' <<<"$record" >/dev/null || return 1

    timeline_file="$evidence_root/$key/timeline.json"
    report_file="$evidence_root/$key/report.json"
    [[ -f "$timeline_file" && ! -L "$timeline_file" &&
       -f "$report_file" && ! -L "$report_file" ]] || return 1
    jq -e 'type == "array"' "$timeline_file" >/dev/null || return 1
    jq -e 'type == "object"' "$report_file" >/dev/null || return 1
    actual_timeline_hash=$(sha256_file "$timeline_file") || return 1
    actual_report_hash=$(sha256_file "$report_file") || return 1
    [[ "$actual_timeline_hash" == "$(jq -er '.timelineSha256' <<<"$record")" &&
       "$actual_report_hash" == "$(jq -er '.reportSha256' <<<"$record")" ]] || return 1
  done
}

verify_payment_network_secret_free() {
  local evidence_root=$1
  local -a evidence_files=()
  local key
  for key in timeout-vulnerable timeout-resilient callback-vulnerable callback-resilient; do
    evidence_files+=(
      "$evidence_root/$key/timeline.json"
      "$evidence_root/$key/report.json"
    )
  done
  assert_runtime_secret_free "${evidence_files[@]}"
}

run_web_e2e_phase() {
  local phase=$1
  local report=$2
  if ! OUTCOMEPROBE_UI_URL="$web_url" \
      OUTCOMEPROBE_CONTROL_API_TOKEN="$control_api_token" \
      OUTCOMEPROBE_PLAYWRIGHT_JSON_OUTPUT="$report" \
      OUTCOMEPROBE_WEB_UX_OBSERVATIONS="$web_observations" \
      npm --prefix web run test:e2e -- --grep "@${phase}\\b" >>"$web_e2e_log" 2>&1; then
    print_redacted_tail "$web_e2e_log"
    return 1
  fi
  [[ -f "$report" && ! -L "$report" ]] || return 1
  assert_runtime_secret_free "$report" "$web_e2e_log" || return 1
}

prepare_pass_summary() {
  [[ -n "$summary_output" ]] || return 0
  [[ -n "$payment_network_summary_json" ]] || return 1
  summary_staging=$(mktemp "$summary_parent/.quick-start-summary.XXXXXX") || return 1
  payment_network_evidence_staging=$(
    mktemp -d "$summary_parent/.payment-network-runs.XXXXXX"
  ) || return 1
  cp -R -- "$payment_network_evidence"/. "$payment_network_evidence_staging"/ || return 1
  verify_payment_network_assets "$payment_network_evidence_staging" \
    "$payment_network_summary_json" || return 1
  verify_payment_network_secret_free "$payment_network_evidence_staging" || return 1

  jq -n --argjson network "$payment_network_summary_json" '
    {
      schemaVersion: 2,
      result: "PASS",
      source: {
        kind: "exact-checkout",
        quickStartExecuted: true
      },
      compose: {
        longRunningServiceCount: 5,
        allLongRunningServicesHealthy: true,
        publishedPortsLoopbackOnly: true,
        publishedPortCount: 2,
        consumerHostPortCount: 0,
        postgresHostPortCount: 0
      },
      bootstrap: {
        firstRun: "PASS",
        idempotentRerun: "PASS",
        residueContainerCount: 0
      },
      artifacts: {
        counts: {providerContract: 1, targetProfile: 2, scenario: 2},
        canonicalContentHashesBound: true,
        inventoryUnchangedAfterRerun: true
      },
      goldenPath: {
        scenarios: $network.scenarios,
        targetStatesValidated: true,
        evidenceRoot: "payment-network-runs",
        evidenceFileCount: 8
      },
      serverImageJar: {
        releaseFlavor: "core-only",
        packSupport: false,
        projectLicenseEntryCount: 1,
        thirdPartyNoticesEntryCount: 1,
        thirdPartyLicensesEntryCount: 1,
        licensesByteIdentical: true
      },
      secrets: {plaintextFindingCount: 0},
      cleanup: {
        containers: 0,
        networks: 0,
        volumes: 0,
        temporaryCredentialsRemoved: true
      },
      pushPerformed: false
    }
  ' >"$summary_staging" || return 1
  jq -e --argjson network "$payment_network_summary_json" '
    .schemaVersion == 2 and .result == "PASS" and
    .goldenPath.scenarios == $network.scenarios and
    .goldenPath.targetStatesValidated == true and
    .goldenPath.evidenceRoot == "payment-network-runs" and
    .goldenPath.evidenceFileCount == 8 and
    .secrets.plaintextFindingCount == 0 and
    .cleanup == {containers:0,networks:0,volumes:0,temporaryCredentialsRemoved:true}
  ' "$summary_staging" >/dev/null || return 1
  assert_runtime_secret_free "$summary_staging" || return 1
  if [[ "$web_e2e_enabled" == true ]]; then
    [[ -f "$web_fresh_report" && -f "$web_post_report" && -f "$web_observations" ]] || return 1
    web_ux_summary_staging="$summary_parent/.web-ux-summary.$run_nonce.tmp"
    [[ ! -e "$web_ux_summary_staging" && ! -L "$web_ux_summary_staging" ]] || return 1
    OUTCOMEPROBE_CONTROL_API_TOKEN="$control_api_token" \
      node scripts/core-release-staging/create-web-ux-summary.mjs \
        --fresh-report "$web_fresh_report" \
        --post-report "$web_post_report" \
        --observations "$web_observations" \
        --source-sha "$web_e2e_source_sha" \
        --output "$web_ux_summary_staging" || return 1
    assert_runtime_secret_free "$web_ux_summary_staging" || return 1
  fi
}

publish_pass_summary() {
  [[ -n "$summary_output" ]] || return 0
  [[ -n "$summary_staging" && -f "$summary_staging" && ! -L "$summary_staging" ]] || return 1
  [[ -n "$payment_network_evidence_staging" &&
     -d "$payment_network_evidence_staging" &&
     ! -L "$payment_network_evidence_staging" ]] || return 1
  [[ ! -e "$summary_output" && ! -L "$summary_output" &&
     ! -e "$payment_network_evidence_output" && ! -L "$payment_network_evidence_output" ]] || return 1

  if [[ "$web_e2e_enabled" == true ]]; then
    [[ -n "$web_ux_summary_staging" && -f "$web_ux_summary_staging" && ! -L "$web_ux_summary_staging" &&
       ! -e "$web_ux_summary_output" && ! -L "$web_ux_summary_output" ]] || return 1
    mv -T -n -- "$web_ux_summary_staging" "$web_ux_summary_output" || return 1
    web_ux_summary_staging=
    web_ux_summary_published=1
  fi

  mv -T -n -- "$payment_network_evidence_staging" \
    "$payment_network_evidence_output" || return 1
  [[ ! -e "$payment_network_evidence_staging" &&
     ! -L "$payment_network_evidence_staging" ]] || return 1
  payment_network_evidence_staging=
  payment_network_evidence_published=1

  mv -T -n -- "$summary_staging" "$summary_output" || return 1
  [[ ! -e "$summary_staging" && ! -L "$summary_staging" ]] || return 1
  summary_staging=
  summary_published=1

  verify_payment_network_assets "$payment_network_evidence_output" \
    "$payment_network_summary_json" || return 1
  verify_payment_network_secret_free "$payment_network_evidence_output" || return 1
  assert_runtime_secret_free "$summary_output" || return 1
  jq -e --argjson network "$payment_network_summary_json" '
    .schemaVersion == 2 and .result == "PASS" and
    .goldenPath.scenarios == $network.scenarios and
    .goldenPath.targetStatesValidated == true and
    .goldenPath.evidenceRoot == "payment-network-runs" and
    .goldenPath.evidenceFileCount == 8 and
    .secrets.plaintextFindingCount == 0 and
    .cleanup == {containers:0,networks:0,volumes:0,temporaryCredentialsRemoved:true}
  ' "$summary_output" >/dev/null || return 1
  if [[ "$web_e2e_enabled" == true ]]; then
    assert_runtime_secret_free "$web_ux_summary_output" || return 1
    jq -e --arg source "$web_e2e_source_sha" '
      .schemaVersion == 1 and .result == "PASS" and
      .source == {kind:"exact-checkout",sourceSha:$source} and
      .tests == {total:6,passed:6,failed:0,skipped:0,flaky:0,retries:0} and
      .goldenPath.runCount == 4 and .goldenPath.runIdempotencyKeysPresent == true and
      .goldenPath.canonicalSeedPreserved == true and
      .secrets.plaintextFindingCount == 0 and .pushPerformed == false
    ' "$web_ux_summary_output" >/dev/null || return 1
  fi
}

discard_pass_evidence() {
  local discard_status=0
  if [[ -n "$web_ux_summary_staging" ]]; then
    case "$web_ux_summary_staging" in
      "$summary_parent"/.web-ux-summary.*.tmp)
        rm -f -- "$web_ux_summary_staging" || discard_status=1
        [[ ! -e "$web_ux_summary_staging" && ! -L "$web_ux_summary_staging" ]] || discard_status=1
        ;;
      *) discard_status=1 ;;
    esac
    web_ux_summary_staging=
  fi
  if [[ $web_ux_summary_published -eq 1 ]]; then
    rm -f -- "$web_ux_summary_output" || discard_status=1
    [[ ! -e "$web_ux_summary_output" && ! -L "$web_ux_summary_output" ]] || discard_status=1
    web_ux_summary_published=0
  fi
  if [[ -n "$payment_network_evidence_staging" ]]; then
    case "$payment_network_evidence_staging" in
      "$summary_parent"/.payment-network-runs.*)
        rm -rf -- "$payment_network_evidence_staging" || discard_status=1
        [[ ! -e "$payment_network_evidence_staging" &&
           ! -L "$payment_network_evidence_staging" ]] || discard_status=1
        ;;
      *) discard_status=1 ;;
    esac
    payment_network_evidence_staging=
  fi
  if [[ -n "$summary_staging" ]]; then
    case "$summary_staging" in
      "$summary_parent"/.quick-start-summary.*)
        rm -f -- "$summary_staging" || discard_status=1
        [[ ! -e "$summary_staging" && ! -L "$summary_staging" ]] || discard_status=1
        ;;
      *) discard_status=1 ;;
    esac
    summary_staging=
  fi
  if [[ $summary_published -eq 1 ]]; then
    rm -f -- "$summary_output" || discard_status=1
    [[ ! -e "$summary_output" && ! -L "$summary_output" ]] || discard_status=1
    summary_published=0
  fi
  if [[ $payment_network_evidence_published -eq 1 ]]; then
    rm -rf -- "$payment_network_evidence_output" || discard_status=1
    [[ ! -e "$payment_network_evidence_output" &&
       ! -L "$payment_network_evidence_output" ]] || discard_status=1
    payment_network_evidence_published=0
  fi
  return "$discard_status"
}

cleanup() {
  local primary_status=$?
  local cleanup_status=0
  trap - EXIT INT TERM
  set +e

  "${compose[@]}" down --volumes --remove-orphans --timeout 15 \
    >>"$lifecycle_log" 2>&1
  if [[ $? -ne 0 ]]; then
    cleanup_status=1
  fi

  # down이 부분 실패해도 exact project label에 속한 자원만 emergency cleanup한다.
  local -a remaining_containers remaining_volumes remaining_networks
  if ! collect_owned_ids container remaining_containers; then
    cleanup_status=1
    remaining_containers=()
  fi
  if [[ ${#remaining_containers[@]} -gt 0 ]]; then
    docker rm -f "${remaining_containers[@]}" >>"$lifecycle_log" 2>&1 || cleanup_status=1
  fi
  if ! collect_owned_ids volume remaining_volumes; then
    cleanup_status=1
    remaining_volumes=()
  fi
  if [[ ${#remaining_volumes[@]} -gt 0 ]]; then
    docker volume rm "${remaining_volumes[@]}" >>"$lifecycle_log" 2>&1 || cleanup_status=1
  fi
  if ! collect_owned_ids network remaining_networks; then
    cleanup_status=1
    remaining_networks=()
  fi
  if [[ ${#remaining_networks[@]} -gt 0 ]]; then
    docker network rm "${remaining_networks[@]}" >>"$lifecycle_log" 2>&1 || cleanup_status=1
  fi

  local container_count network_count volume_count
  local query_failed=0
  container_count=$(owned_count container) || query_failed=1
  network_count=$(owned_count network) || query_failed=1
  volume_count=$(owned_count volume) || query_failed=1
  if [[ $query_failed -ne 0 ]]; then
    cleanup_status=1
    printf '[FAIL] cleanup 뒤 소유 자원 조회를 완료하지 못했습니다.\n' >&2
  elif [[ "$container_count" -ne 0 || "$network_count" -ne 0 || "$volume_count" -ne 0 ]]; then
    cleanup_status=1
    printf '[FAIL] cleanup 뒤 소유 자원이 남았습니다: containers=%s networks=%s volumes=%s\n' \
      "$container_count" "$network_count" "$volume_count" >&2
  fi

  if [[ $primary_status -eq 0 && $cleanup_status -eq 0 ]]; then
    if ! prepare_pass_summary; then
      cleanup_status=1
      printf '[FAIL] Quick Start PASS 요약·payment 증거를 staging하지 못했습니다.\n' >&2
    fi
  fi

  if ! remove_temporary_root; then
    cleanup_status=1
    printf '[FAIL] 임시 디렉터리를 안전하게 정리하지 못했습니다.\n' >&2
  fi

  if [[ $primary_status -eq 0 && $cleanup_status -eq 0 ]]; then
    if ! publish_pass_summary; then
      cleanup_status=1
      printf '[FAIL] Quick Start PASS 요약·payment 증거를 원자적으로 공개하지 못했습니다.\n' >&2
    fi
  fi

  if [[ $primary_status -ne 0 || $cleanup_status -ne 0 ]]; then
    if ! discard_pass_evidence; then
      cleanup_status=1
      printf '[FAIL] 부분 Quick Start 증거를 안전하게 제거하지 못했습니다.\n' >&2
    fi
  else
    pass 'cleanup containers/networks/volumes = 0/0/0, temporary credentials removed'
  fi

  if [[ $primary_status -ne 0 ]]; then
    exit "$primary_status"
  fi
  exit "$cleanup_status"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if ! "${compose[@]}" config --format json >"$rendered_config" 2>"$lifecycle_log"; then
  print_redacted_tail "$lifecycle_log"
  fail 'Compose config 렌더링이 실패했습니다.'
fi
if ! "${compose[@]}" --profile '*' config --format json >"$profiled_config" 2>>"$lifecycle_log"; then
  print_redacted_tail "$lifecycle_log"
  fail 'tools profile을 포함한 Compose config 렌더링이 실패했습니다.'
fi

jq -e '(.services | keys | length) == 5 and
  (.services | has("outcomeprobe-bootstrap") | not)' \
  "$rendered_config" >/dev/null || fail '기본 Compose service inventory가 5개가 아닙니다.'
jq -e '(.services | keys | length) == 6 and
  (.services | has("outcomeprobe-bootstrap"))' \
  "$profiled_config" >/dev/null || fail 'tools profile service inventory가 6개가 아닙니다.'

jq -e '
  (.services.postgres.ports // []) == [] and
  ([.services | to_entries[] | (.value.ports // [])[] |
      select((.host_ip // "") != "127.0.0.1")] | length) == 0 and
  ((.services["outcomeprobe-server"].ports // []) | length) == 1 and
  ((.services["outcomeprobe-web"].ports // []) | length) == 1 and
  ((.services["reference-consumer-vulnerable"].ports // []) | length) == 0 and
  ((.services["reference-consumer-resilient"].ports // []) | length) == 0 and
  ([.services | to_entries[] | select((.value.network_mode // "") == "host")] | length) == 0
' "$rendered_config" >/dev/null || fail 'published port의 loopback/DB 비노출 계약이 실패했습니다.'

jq -e '
  (.networks.data.internal == true) and
  (.services.postgres.networks | keys == ["data"]) and
  (.services["outcomeprobe-server"].networks | has("app") and has("data") and length == 2) and
  (.services["outcomeprobe-web"].networks | keys == ["app"]) and
  (.services["reference-consumer-vulnerable"].networks | keys == ["app"]) and
  (.services["reference-consumer-resilient"].networks | keys == ["app"])
' "$rendered_config" >/dev/null || fail 'app/data network 분리 계약이 실패했습니다.'

jq -e '
  .services.postgres.environment.POSTGRES_PASSWORD as $databaseSecret |
  .services["outcomeprobe-server"].environment.OUTCOMEPROBE_DEMO_CALLBACK_SECRET as $callbackSecret |
  .services["outcomeprobe-server"].environment.OUTCOMEPROBE_CONTROL_API_TOKEN as $controlToken |
  .services["outcomeprobe-server"].environment.OUTCOMEPROBE_EMULATOR_TOKEN as $emulatorToken |
  ($databaseSecret | type == "string" and length >= 32 and . != "change-me-local") and
  .services.postgres.environment.POSTGRES_DB == "contract_db" and
  .services.postgres.environment.POSTGRES_USER == "contract_user" and
  .services["outcomeprobe-server"].environment.OUTCOMEPROBE_DB_NAME == "contract_db" and
  .services["outcomeprobe-server"].environment.OUTCOMEPROBE_DB_USER == "contract_user" and
  .services["outcomeprobe-server"].environment.OUTCOMEPROBE_DB_PASSWORD == $databaseSecret and
  ($callbackSecret | type == "string" and length >= 32 and . != "local-demo-secret") and
  ($controlToken | type == "string" and length >= 32) and
  (.services["outcomeprobe-server"].environment |
    has("OUTCOMEPROBE_SECRET_CALLBACK_SIGNING_KEY") | not) and
  .services["reference-consumer-vulnerable"].environment.OUTCOMEPROBE_DEMO_CALLBACK_SECRET == $callbackSecret and
  .services["reference-consumer-resilient"].environment.OUTCOMEPROBE_DEMO_CALLBACK_SECRET == $callbackSecret and
  ($emulatorToken | type == "string" and length >= 32) and
  .services["reference-consumer-vulnerable"].environment.OUTCOMEPROBE_EMULATOR_TOKEN == $emulatorToken and
  .services["reference-consumer-resilient"].environment.OUTCOMEPROBE_EMULATOR_TOKEN == $emulatorToken and
  .services["outcomeprobe-server"].environment.OUTCOMEPROBE_RETENTION_DAYS == "23" and
  .services["outcomeprobe-server"].environment.OUTCOMEPROBE_MAX_CONCURRENT_RUNS == "3" and
  .services["outcomeprobe-server"].environment.OUTCOMEPROBE_DEMO_PROFILE_SEEDING_ENABLED == "false" and
  .services["outcomeprobe-server"].environment.OUTCOMEPROBE_LOG_LEVEL == "WARN"
' "$rendered_config" >/dev/null || fail 'secret과 runtime env 전파 계약이 실패했습니다.'

jq -e '
  .services["outcomeprobe-bootstrap"].profiles == ["tools"] and
  .services["outcomeprobe-bootstrap"].read_only == true and
  .services["outcomeprobe-bootstrap"].environment == {
    OUTCOMEPROBE_CONTROL_API_TOKEN: .services["outcomeprobe-server"].environment.OUTCOMEPROBE_CONTROL_API_TOKEN,
    OUTCOMEPROBE_SERVER_URL: "http://outcomeprobe-server:8080"
  } and
  .services["outcomeprobe-bootstrap"].tmpfs == [
    "/tmp:rw,noexec,nosuid,nodev,size=16m,mode=1777"
  ]
' "$profiled_config" >/dev/null || fail 'bootstrap profile·secret/env 계약이 실패했습니다.'

jq -e '
  (.services.postgres.healthcheck.test | join(" ") |
    contains("contract_user") and contains("contract_db")) and
  (.services["outcomeprobe-server"].environment |
    has("OUTCOMEPROBE_DEMO_CALLBACK_SECRET") and
    (has("OUTCOMEPROBE_SECRET_CALLBACK_SIGNING_KEY") | not))
' "$rendered_config" >/dev/null || fail 'DB healthcheck 또는 callback fallback precedence 계약이 실패했습니다.'
pass 'Compose config loopback·DB 비노출·network 분리·secret/env·callback precedence'

if ! "${compose[@]}" up -d --build --wait --wait-timeout 600 \
    >>"$lifecycle_log" 2>&1; then
  print_redacted_tail "$lifecycle_log"
  fail 'Compose stack이 제한 시간 안에 healthy가 되지 않았습니다.'
fi

mapfile -t project_containers < <(owned_ids container)
[[ ${#project_containers[@]} -eq 5 ]] || fail '기본 Compose 소유 container 수가 5가 아닙니다.'
server_container_id=
for container_id in "${project_containers[@]}"; do
  service_name=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}}' \
    "$container_id")
  state=$(docker inspect --format '{{.State.Status}}' "$container_id")
  health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' \
    "$container_id")
  [[ "$state" == 'running' && "$health" == 'healthy' ]] || \
    fail "service가 healthy runtime 상태가 아닙니다: $service_name"
  if [[ "$service_name" == 'outcomeprobe-server' ]]; then
    server_container_id=$container_id
  fi

  port_bindings=$(docker inspect --format '{{json .HostConfig.PortBindings}}' "$container_id")
  jq -e '[to_entries[]? | .value[]? |
      select((.HostIp // "") != "127.0.0.1")] | length == 0' \
    <<<"$port_bindings" >/dev/null || fail "runtime port가 loopback 밖에 열렸습니다: $service_name"
  if [[ "$service_name" == 'postgres' || "$service_name" == reference-consumer-* ]]; then
    jq -e 'length == 0' <<<"$port_bindings" >/dev/null || \
      fail "내부 전용 service에 published port가 있습니다: $service_name"
  fi
done
[[ -n "$server_container_id" ]] || fail 'source-built server container를 찾지 못했습니다.'
pass '기본 장기 service 5개 healthy, server/Web만 loopback publish, consumer/DB host port 0'

docker cp "$server_container_id:/app/outcomeprobe-server.jar" "$server_image_jar" \
  >>"$lifecycle_log" 2>&1 || fail 'source-built server JAR 추출이 실패했습니다.'
"$python_command" - "$server_image_jar" LICENSE THIRD-PARTY-NOTICES \
  THIRD-PARTY-LICENSES.json <<'PY' || \
  fail 'source-built server JAR의 core-only marker 또는 license payload 결속이 실패했습니다.'
import pathlib
import sys
import zipfile

jar_path, project_license_path, notices_path, licenses_path = map(pathlib.Path, sys.argv[1:])
expected_entries = {
    "META-INF/licenses/OUTCOMEPROBE-LICENSE": project_license_path,
    "META-INF/licenses/THIRD-PARTY-NOTICES": notices_path,
    "META-INF/licenses/THIRD-PARTY-LICENSES.json": licenses_path,
}
with zipfile.ZipFile(jar_path) as archive:
    names = archive.namelist()
    for entry, source in expected_entries.items():
        if names.count(entry) != 1:
            raise SystemExit(f"license entry count mismatch: {entry}")
        if archive.read(entry) != source.read_bytes():
            raise SystemExit(f"license entry bytes mismatch: {entry}")
    manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
    unfolded = manifest.replace("\r\n ", "").replace("\n ", "")
    lines = set(unfolded.replace("\r\n", "\n").splitlines())
    required = {
        "Implementation-Version: 0.1.0",
        "OutcomeProbe-Release-Flavor: core-only",
        "OutcomeProbe-Pack-Support: false",
    }
    if not required.issubset(lines):
        raise SystemExit("core-only manifest marker mismatch")
PY
pass 'source-built server JAR core-only·Pack false marker와 license payload exact 결속'

"${compose[@]}" exec -T postgres pg_isready -U "$db_user" -d "$db_name" \
  >>"$lifecycle_log" 2>&1 || fail 'PostgreSQL 내부 readiness가 실패했습니다.'
database_identity=$("${compose[@]}" exec -T postgres \
  psql -X -v ON_ERROR_STOP=1 -U "$db_user" -d "$db_name" \
    -Atc "SELECT current_database() || '|' || current_user;" 2>>"$lifecycle_log")
[[ "$database_identity" == "$db_name|$db_user" ]] || \
  fail 'PostgreSQL nondefault database/user identity가 실제 적용되지 않았습니다.'
"${compose[@]}" exec -T outcomeprobe-server \
  curl -fsS http://localhost:8080/actuator/health/readiness \
  >>"$lifecycle_log" 2>&1 || fail 'server 내부 readiness가 실패했습니다.'
"${compose[@]}" exec -T reference-consumer-vulnerable \
  curl -fsS http://localhost:8091/actuator/health/readiness \
  >>"$lifecycle_log" 2>&1 || fail 'vulnerable consumer 내부 readiness가 실패했습니다.'
"${compose[@]}" exec -T reference-consumer-resilient \
  curl -fsS http://localhost:8092/actuator/health/readiness \
  >>"$lifecycle_log" 2>&1 || fail 'resilient consumer 내부 readiness가 실패했습니다.'
"${compose[@]}" exec -T outcomeprobe-web \
  wget -qO- http://127.0.0.1:3000/ \
  >>"$lifecycle_log" 2>&1 || fail 'Web 내부 readiness가 실패했습니다.'
pass 'compose exec 내부 health/readiness'

# Windows Docker CLI의 CRLF와 Ubuntu의 LF를 같은 canonical 값으로 비교한다.
server_binding=$("${compose[@]}" port outcomeprobe-server 8080 | tr -d '\r')
web_binding=$("${compose[@]}" port outcomeprobe-web 3000 | tr -d '\r')
[[ "$server_binding" == "127.0.0.1:$server_port" ]] || fail 'server loopback binding이 exact하지 않습니다.'
[[ "$web_binding" == "127.0.0.1:$web_port" ]] || fail 'Web loopback binding이 exact하지 않습니다.'
server_url="http://127.0.0.1:$server_port"
web_url="http://127.0.0.1:$web_port"

control_curl() {
  # token은 process argv 대신 curl config stdin으로만 전달한다.
  printf 'header = "Authorization: Bearer %s"\n' "$control_api_token" |
    curl --config - "$@"
}

curl --fail --silent --show-error --max-time 15 \
  "$server_url/actuator/health/readiness" >/dev/null || fail 'server readiness가 실패했습니다.'
curl --fail --silent --show-error --max-time 15 "$web_url/" >/dev/null || fail 'Web health가 실패했습니다.'

control_unauthorized_status=$(curl --silent --show-error --max-time 15 \
  --output "$control_unauthorized_body" --write-out '%{http_code}' \
  "$server_url/api/v1/settings")
[[ "$control_unauthorized_status" == '401' ]] || \
  fail 'token 없는 Control API 요청이 401로 거부되지 않았습니다.'
control_wrong_status=$(
  printf 'header = "Authorization: Bearer wrong-%s"\n' "$run_nonce" |
    curl --silent --show-error --max-time 15 --config - \
      --output "$control_wrong_body" --write-out '%{http_code}' \
      "$server_url/api/v1/settings"
)
[[ "$control_wrong_status" == '401' ]] || \
  fail '잘못된 token의 Control API 요청이 401로 거부되지 않았습니다.'
if grep -Fq -- "$control_api_token" "$control_unauthorized_body" "$control_wrong_body"; then
  fail 'Control API 인증 실패 응답에 token 원문이 노출됐습니다.'
fi
control_curl --fail --silent --show-error --max-time 15 \
  "$server_url/api/v1/settings" >"$settings_json" || fail 'Settings 조회가 실패했습니다.'
jq -e --argjson retention "$retention_days" --argjson maximum "$max_concurrent_runs" '
  .mode == "local-test-workbench" and
  .productionGateway == false and
  .retentionDays == $retention and
  .maxConcurrentRuns == $maximum and
  .emulatorTokenRequired == true
' "$settings_json" >/dev/null || fail 'Settings runtime env 결속이 실패했습니다.'
if [[ "$web_e2e_enabled" == true ]]; then
  run_web_e2e_phase fresh "$web_fresh_report" || fail 'fresh Web onboarding E2E failed.'
  pass 'fresh Web login, empty guidance, invalid import, duplicate-click boundary'
fi
if ! "${compose[@]}" run --rm -T --no-deps --build outcomeprobe-bootstrap >>"$lifecycle_log" 2>&1; then
  print_redacted_tail "$lifecycle_log"
  fail 'Docker-only bootstrap 실행이 실패했습니다.'
fi
bootstrap_residue=$(docker ps -aq \
  --filter "label=com.docker.compose.project=$project" \
  --filter 'label=com.docker.compose.service=outcomeprobe-bootstrap') || \
  fail 'bootstrap container 제거 상태를 조회하지 못했습니다.'
[[ -z "$bootstrap_residue" ]] || fail 'bootstrap --rm 뒤 container가 남았습니다.'
if grep -Fq -- "$control_api_token" "$lifecycle_log"; then
  fail 'bootstrap lifecycle log에 Control API token 원문이 남았습니다.'
fi
pass 'Docker-only bootstrap 성공, --rm 뒤 container 0, lifecycle log token 원문 0'

control_curl --fail --silent --show-error --max-time 15 \
  "$server_url/api/v1/target-profiles" >"$profiles_json" || \
  fail 'bootstrap 뒤 Target Profile 목록 조회가 실패했습니다.'
jq -e '
  length == 2 and
  ([.[].name] | sort) == ["resilient-consumer", "vulnerable-consumer"]
' "$profiles_json" >/dev/null || fail 'demo profile opt-in 결속이 실패했습니다.'
control_curl --fail --silent --show-error --max-time 15 -X POST \
  "$server_url/api/v1/target-profiles/vulnerable-consumer/verify" \
  >"$vulnerable_profile_verify" || fail '취약 Target Profile Docker DNS 검증이 실패했습니다.'
control_curl --fail --silent --show-error --max-time 15 -X POST \
  "$server_url/api/v1/target-profiles/resilient-consumer/verify" \
  >"$resilient_profile_verify" || fail '복원력 Target Profile Docker DNS 검증이 실패했습니다.'
jq -e '.passed == true and .checks.dnsResolved == true and .checks.reachable == true and
  .checks.callbackPaths == ["paymentSucceeded"]' "$vulnerable_profile_verify" >/dev/null ||
  fail '취약 Target Profile 검증 증거가 올바르지 않습니다.'
jq -e '.passed == true and .checks.dnsResolved == true and .checks.reachable == true and
  .checks.callbackPaths == ["paymentSucceeded"]' "$resilient_profile_verify" >/dev/null ||
  fail '복원력 Target Profile 검증 증거가 올바르지 않습니다.'
control_curl --fail --silent --show-error --max-time 15 \
  "$server_url/api/v1/artifacts" >"$artifacts_json" || \
  fail 'canonical Artifact inventory 조회가 실패했습니다.'
provider_hash=$(sha256sum examples/golden-path/payment/provider-contract.yaml | awk '{print $1}')
vulnerable_hash=$(sha256sum examples/golden-path/payment/target-profile-vulnerable.yaml | awk '{print $1}')
resilient_hash=$(sha256sum examples/golden-path/payment/target-profile-resilient.yaml | awk '{print $1}')
scenario_hash=$(sha256sum examples/golden-path/payment/scenario.yaml | awk '{print $1}')
callback_scenario_hash=$(sha256sum examples/golden-path/payment/scenario-callback-duplicate.yaml | awk '{print $1}')
jq -e \
  --arg provider "$provider_hash" \
  --arg vulnerable "$vulnerable_hash" \
  --arg resilient "$resilient_hash" \
  --arg scenario "$scenario_hash" \
  --arg callbackScenario "$callback_scenario_hash" '
  length == 5 and
  ([.[] | {kind, name, version, contentHash, builtIn}] | sort_by(.kind, .name)) ==
    ([
      {kind:"PROVIDER_CONTRACT",name:"generic-payment",version:"1.1.0",contentHash:$provider,builtIn:false},
      {kind:"TARGET_PROFILE",name:"vulnerable-consumer",version:"1.0.0",contentHash:$vulnerable,builtIn:false},
      {kind:"TARGET_PROFILE",name:"resilient-consumer",version:"1.0.0",contentHash:$resilient,builtIn:false},
      {kind:"SCENARIO",name:"payment-timeout-after-commit",version:"1.0.0",contentHash:$scenario,builtIn:false},
      {kind:"SCENARIO",name:"payment-success-callback-duplicate",version:"1.0.0",contentHash:$callbackScenario,builtIn:false}
    ] | sort_by(.kind, .name))
' "$artifacts_json" >/dev/null || fail 'canonical Artifact 1/2/2 identity·hash inventory가 다릅니다.'
pass 'health 공개 / Control API 401·Bearer 인증 / Settings / canonical Artifact 1·2·2 결속'

if ! "${compose[@]}" run --rm -T outcomeprobe-bootstrap >>"$lifecycle_log" 2>&1; then
  print_redacted_tail "$lifecycle_log"
  fail 'bootstrap 멱등 재실행이 실패했습니다.'
fi
bootstrap_residue=$(docker ps -aq \
  --filter "label=com.docker.compose.project=$project" \
  --filter 'label=com.docker.compose.service=outcomeprobe-bootstrap') || \
  fail 'bootstrap 재실행 제거 상태를 조회하지 못했습니다.'
[[ -z "$bootstrap_residue" ]] || fail 'bootstrap 재실행 --rm 뒤 container가 남았습니다.'
control_curl --fail --silent --show-error --max-time 15 \
  "$server_url/api/v1/artifacts" >"$artifacts_after_rerun" || \
  fail 'bootstrap 재실행 뒤 Artifact inventory 조회가 실패했습니다.'
jq -S '[.[] | {kind,name,version,contentHash,builtIn}] | sort_by(.kind,.name)' \
  "$artifacts_json" >"$temporary_root/artifacts-before.canonical.json"
jq -S '[.[] | {kind,name,version,contentHash,builtIn}] | sort_by(.kind,.name)' \
  "$artifacts_after_rerun" >"$temporary_root/artifacts-after.canonical.json"
cmp -s "$temporary_root/artifacts-before.canonical.json" \
  "$temporary_root/artifacts-after.canonical.json" || \
  fail 'bootstrap 재실행이 Artifact identity·hash inventory를 변경했습니다.'
pass 'bootstrap 재실행 멱등, Artifact identity·hash inventory 불변'

if [[ "$web_e2e_enabled" == true ]]; then
  run_web_e2e_phase post "$web_post_report" || fail 'post-bootstrap Web golden path E2E failed.'
  [[ -f "$web_observations" && ! -L "$web_observations" ]] || fail 'Web run observations were not published.'
  assert_runtime_secret_free "$web_observations" || fail 'Web run observations contain a runtime secret.'
  pass 'post-bootstrap Web exact scenarios, four runs, auth/server recovery, mobile usability'
fi

emulator_probe="$server_url/emulator/contract-auth-probe/not-active"
unauthorized_status=$(curl --silent --show-error --max-time 15 --output "$unauthorized_body" \
  --write-out '%{http_code}' "$emulator_probe")
[[ "$unauthorized_status" == '401' ]] || fail 'token 없는 Emulator 요청이 401로 거부되지 않았습니다.'

authorized_status=$(
  printf 'header = "X-OutcomeProbe-Emulator-Token: %s"\n' "$emulator_token" |
    curl --silent --show-error --max-time 15 --output "$authorized_body" \
      --write-out '%{http_code}' --config - "$emulator_probe"
)
[[ "$authorized_status" == '404' ]] || fail '올바른 token의 Emulator probe가 service까지 도달하지 않았습니다.'
jq -e '.code == "PROVIDER_ALIAS_NOT_FOUND"' "$authorized_body" >/dev/null || \
  fail '올바른 token의 Emulator 응답 계약이 다릅니다.'
pass 'Emulator unauthorized 401 / authorized service reachability'

for service_port in \
  'reference-consumer-vulnerable:8091' \
  'reference-consumer-resilient:8092'
do
  service_name=${service_port%%:*}
  internal_port=${service_port##*:}
  invalid_callback_status=$("${compose[@]}" exec -T "$service_name" \
    curl --silent --show-error --max-time 15 --output /dev/null --write-out '%{http_code}' \
      -X POST "http://localhost:$internal_port/webhooks/payments" \
      -H 'Content-Type: application/json' \
      -H 'X-OutcomeProbe-Signature: sha256=0000000000000000000000000000000000000000000000000000000000000000' \
      --data-binary '{"type":"PAYMENT_SUCCEEDED"}') ||
    fail "$service_name invalid callback 검증 요청이 실패했습니다."
  [[ "$invalid_callback_status" == '401' ]] ||
    fail "$service_name invalid callback signature가 401로 거부되지 않았습니다."
done
pass 'Reference Consumer invalid callback HMAC 401 / valid signed callback은 golden path에서 검증'

if ! OUTCOMEPROBE_SERVER_URL="$server_url" \
    OUTCOMEPROBE_CONTROL_API_TOKEN="$control_api_token" \
    OUTCOMEPROBE_DEMO_SUMMARY="$payment_network_summary" \
    OUTCOMEPROBE_DEMO_EVIDENCE_ROOT="$payment_network_evidence" \
    bash scripts/demo-payment.sh \
    >"$demo_log" 2>&1; then
  print_redacted_tail "$demo_log"
  fail '대표 demo matrix가 실패했습니다.'
fi
jq -e '
  .schemaVersion == 2 and .result == "PASS" and
  ([.scenarios[].vulnerable.runId, .scenarios[].resilient.runId] | unique | length) == 4 and
  .scenarios.timeoutAfterCommit.vulnerable.status == "FAILED" and
  .scenarios.timeoutAfterCommit.resilient.status == "PASSED" and
  .scenarios.timeoutAfterCommit.vulnerable.providerCommitCount == 1 and
  .scenarios.timeoutAfterCommit.resilient.providerCommitCount == 1 and
  .scenarios.timeoutAfterCommit.vulnerable.responseFaultCount == 1 and
  .scenarios.timeoutAfterCommit.resilient.responseFaultCount == 1 and
  .scenarios.timeoutAfterCommit.vulnerable.callbackDeliveryCount == 0 and
  .scenarios.timeoutAfterCommit.resilient.callbackDeliveryCount == 0 and
  .scenarios.timeoutAfterCommit.vulnerable.callbackOmittedCount == 1 and
  .scenarios.timeoutAfterCommit.resilient.callbackOmittedCount == 1 and
  .scenarios.timeoutAfterCommit.vulnerable.probeAttemptCount >= 1 and
  .scenarios.timeoutAfterCommit.resilient.probeAttemptCount >= 1 and
  .scenarios.timeoutAfterCommit.resilient.providerQueryCount >= 1 and
  .scenarios.callbackDuplicate.vulnerable.status == "FAILED" and
  .scenarios.callbackDuplicate.resilient.status == "PASSED" and
  .scenarios.callbackDuplicate.vulnerable.providerCommitCount == 1 and
  .scenarios.callbackDuplicate.resilient.providerCommitCount == 1 and
  .scenarios.callbackDuplicate.vulnerable.responseFaultCount == 0 and
  .scenarios.callbackDuplicate.resilient.responseFaultCount == 0 and
  .scenarios.callbackDuplicate.vulnerable.callbackDeliveryCount == 3 and
  .scenarios.callbackDuplicate.resilient.callbackDeliveryCount == 3 and
  .scenarios.callbackDuplicate.vulnerable.probeAttemptCount >= 1 and
  .scenarios.callbackDuplicate.resilient.probeAttemptCount >= 1
' "$payment_network_summary" >/dev/null || fail 'payment network summary 계약이 올바르지 않습니다.'
payment_network_summary_json=$(jq -c . "$payment_network_summary") ||
  fail 'payment network summary를 메모리 증거로 고정하지 못했습니다.'
verify_payment_network_assets "$payment_network_evidence" \
  "$payment_network_summary_json" || fail 'payment Timeline/report 경로·hash 결속이 올바르지 않습니다.'
verify_payment_network_secret_free "$payment_network_evidence" ||
  fail 'payment Timeline/report에 runtime secret 원문이 남았습니다.'

"${compose[@]}" exec -T reference-consumer-vulnerable curl -fsS \
  http://localhost:8091/test-support/payments/pay-network-timeout-vulnerable \
  >"$timeout_vulnerable_state" || fail '취약 timeout Target 상태 조회가 실패했습니다.'
"${compose[@]}" exec -T reference-consumer-resilient curl -fsS \
  http://localhost:8092/test-support/payments/pay-network-timeout-resilient \
  >"$timeout_resilient_state" || fail '복원력 timeout Target 상태 조회가 실패했습니다.'
"${compose[@]}" exec -T reference-consumer-vulnerable curl -fsS \
  http://localhost:8091/test-support/payments/pay-network-callback-vulnerable \
  >"$callback_vulnerable_state" || fail '취약 callback Target 상태 조회가 실패했습니다.'
"${compose[@]}" exec -T reference-consumer-resilient curl -fsS \
  http://localhost:8092/test-support/payments/pay-network-callback-resilient \
  >"$callback_resilient_state" || fail '복원력 callback Target 상태 조회가 실패했습니다.'

jq -e '.paymentId == "pay-network-timeout-vulnerable" and
  .status == "FAILED" and .providerQueryCount == 0 and .ledgerPostCount == 0' \
  "$timeout_vulnerable_state" >/dev/null || fail '취약 timeout Target 상태가 기대와 다릅니다.'
jq -e '.paymentId == "pay-network-timeout-resilient" and
  .status == "SUCCEEDED" and .providerQueryCount >= 1 and .ledgerPostCount == 1' \
  "$timeout_resilient_state" >/dev/null || fail '복원력 timeout Target 상태가 기대와 다릅니다.'
jq -e '.paymentId == "pay-network-callback-vulnerable" and
  .status == "SUCCEEDED" and .ledgerPostCount == 4 and .processedEventCount == 0 and
  .processedEventIds == []' \
  "$callback_vulnerable_state" >/dev/null || fail '취약 callback side effect 상태가 기대와 다릅니다.'
jq -e '.paymentId == "pay-network-callback-resilient" and
  .status == "SUCCEEDED" and .ledgerPostCount == 1 and .processedEventCount == 1 and
  .processedEventIds == ["evt-pay-2001-success"]' "$callback_resilient_state" >/dev/null ||
  fail '복원력 callback dedupe 상태가 기대와 다릅니다.'

payment_network_enriched="$temporary_root/payment-network-summary.enriched.json"
jq -S \
  --slurpfile timeoutVulnerableState "$timeout_vulnerable_state" \
  --slurpfile timeoutResilientState "$timeout_resilient_state" \
  --slurpfile callbackVulnerableState "$callback_vulnerable_state" \
  --slurpfile callbackResilientState "$callback_resilient_state" '
    .scenarios.timeoutAfterCommit.vulnerable.targetState = $timeoutVulnerableState[0] |
    .scenarios.timeoutAfterCommit.resilient.targetState = $timeoutResilientState[0] |
    .scenarios.callbackDuplicate.vulnerable.targetState = $callbackVulnerableState[0] |
    .scenarios.callbackDuplicate.resilient.targetState = $callbackResilientState[0]
  ' "$payment_network_summary" >"$payment_network_enriched" ||
  fail 'payment Target 상태를 run 증거에 결속하지 못했습니다.'
mv -T -- "$payment_network_enriched" "$payment_network_summary" ||
  fail '결속한 payment network summary를 원자적으로 고정하지 못했습니다.'
jq -e \
  --slurpfile timeoutVulnerableState "$timeout_vulnerable_state" \
  --slurpfile timeoutResilientState "$timeout_resilient_state" \
  --slurpfile callbackVulnerableState "$callback_vulnerable_state" \
  --slurpfile callbackResilientState "$callback_resilient_state" '
    .schemaVersion == 2 and .result == "PASS" and
    .scenarios.timeoutAfterCommit.vulnerable.targetState == $timeoutVulnerableState[0] and
    .scenarios.timeoutAfterCommit.resilient.targetState == $timeoutResilientState[0] and
    .scenarios.callbackDuplicate.vulnerable.targetState == $callbackVulnerableState[0] and
    .scenarios.callbackDuplicate.resilient.targetState == $callbackResilientState[0] and
    .scenarios.timeoutAfterCommit.vulnerable.callbackEventId == null and
    .scenarios.timeoutAfterCommit.resilient.callbackEventId == null and
    .scenarios.callbackDuplicate.vulnerable.callbackEventId == "evt-pay-2001-success" and
    .scenarios.callbackDuplicate.resilient.callbackEventId == "evt-pay-2001-success"
  ' "$payment_network_summary" >/dev/null ||
  fail 'payment run·callback event·Target 상태 결속이 올바르지 않습니다.'
assert_runtime_secret_free "$payment_network_summary" ||
  fail 'Target 상태가 결속된 payment summary에 runtime secret 원문이 남았습니다.'
payment_network_summary_json=$(jq -c . "$payment_network_summary") ||
  fail '결속한 payment network summary를 메모리 증거로 고정하지 못했습니다.'
verify_payment_network_assets "$payment_network_evidence" \
  "$payment_network_summary_json" || fail '결속 후 payment Timeline/report hash가 달라졌습니다.'
pass 'payment network matrix 4 runs, exact Timeline/report, Target side effect convergence'

"${compose[@]}" logs --no-color >"$service_log" 2>&1 || fail 'service log 수집이 실패했습니다.'
assert_runtime_secret_free "$service_log" ||
  fail 'runtime secret 원문이 service log에 노출됐습니다.'
pass 'runtime service log secret 원문 0'
