#!/usr/bin/env bash
set -Eeuo pipefail

# 공개 payment 골든 패스 두 개를 취약·복원력 Target에서 실행하고, 같은 Run ID의
# Timeline과 JSON report를 결속한다. host에는 curl·jq가 필요하며 Docker bootstrap은 별도 경계다.

SERVER_URL="${OUTCOMEPROBE_SERVER_URL:-http://localhost:8080}"
FIXTURE_ROOT="${OUTCOMEPROBE_CORE_FIXTURE_ROOT:-examples/golden-path/payment}"
CONTROL_API_TOKEN="${OUTCOMEPROBE_CONTROL_API_TOKEN:-}"
SUMMARY_OUTPUT="${OUTCOMEPROBE_DEMO_SUMMARY:-}"
EVIDENCE_OUTPUT="${OUTCOMEPROBE_DEMO_EVIDENCE_ROOT:-}"

if [[ ! "$CONTROL_API_TOKEN" =~ ^[A-Za-z0-9._~+/-]+={0,2}$ || \
      ${#CONTROL_API_TOKEN} -lt 32 || ${#CONTROL_API_TOKEN} -gt 512 ]]; then
  echo '[FAIL] OUTCOMEPROBE_CONTROL_API_TOKEN에 유효한 32~512자 token을 설정하세요.' >&2
  exit 1
fi
for command_name in awk curl grep jq mktemp seq sha256sum; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "[FAIL] '$command_name' 명령이 필요합니다." >&2
    exit 1
  }
done

temporary_parent=${TMPDIR:-/tmp}
temporary_root=$(mktemp -d "$temporary_parent/outcomeprobe-payment-demo.XXXXXX")
case "$temporary_root" in
  "$temporary_parent"/outcomeprobe-payment-demo.*) ;;
  *)
    echo '[FAIL] payment demo temporary directory boundary is invalid.' >&2
    exit 1
    ;;
esac
evidence_staging="$temporary_root/payment-network-runs"
evidence_publish_armed=0
summary_publish_armed=0

remove_temporary_root() {
  case "$temporary_root" in
    "$temporary_parent"/outcomeprobe-payment-demo.*)
      rm -rf -- "$temporary_root" || return 1
      [[ ! -e "$temporary_root" && ! -L "$temporary_root" ]]
      ;;
    *) return 1 ;;
  esac
}

cleanup() {
  local primary_status=$?
  local cleanup_status=0
  trap - EXIT INT TERM

  if ! remove_temporary_root; then
    printf '%s\n' '[FAIL] payment demo temporary evidence cleanup failed.' >&2
    cleanup_status=1
  fi

  if [[ $primary_status -ne 0 || $cleanup_status -ne 0 ]]; then
    if [[ $summary_publish_armed -eq 1 && -n "$SUMMARY_OUTPUT" ]]; then
      rm -f -- "$SUMMARY_OUTPUT" || cleanup_status=1
      [[ ! -e "$SUMMARY_OUTPUT" && ! -L "$SUMMARY_OUTPUT" ]] || cleanup_status=1
    fi
    if [[ $evidence_publish_armed -eq 1 && -n "$EVIDENCE_OUTPUT" ]]; then
      rm -rf -- "$EVIDENCE_OUTPUT" || cleanup_status=1
      [[ ! -e "$EVIDENCE_OUTPUT" && ! -L "$EVIDENCE_OUTPUT" ]] || cleanup_status=1
    fi
  fi

  if [[ $primary_status -ne 0 ]]; then
    exit "$primary_status"
  fi
  exit "$cleanup_status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [[ -n "$SUMMARY_OUTPUT" || -n "$EVIDENCE_OUTPUT" ]]; then
  [[ -n "$SUMMARY_OUTPUT" && -n "$EVIDENCE_OUTPUT" ]] || {
    echo '[FAIL] demo summary and evidence outputs must be configured together.' >&2
    exit 1
  }
  [[ "$SUMMARY_OUTPUT" != / && "$EVIDENCE_OUTPUT" != / ]] || {
    echo '[FAIL] demo output path must not be the filesystem root.' >&2
    exit 1
  }
  [[ ! -e "$SUMMARY_OUTPUT" && ! -L "$SUMMARY_OUTPUT" ]] || {
    echo '[FAIL] demo summary output already exists.' >&2
    exit 1
  }
  [[ ! -e "$EVIDENCE_OUTPUT" && ! -L "$EVIDENCE_OUTPUT" ]] || {
    echo '[FAIL] demo evidence output already exists.' >&2
    exit 1
  }
  summary_parent=$(dirname -- "$SUMMARY_OUTPUT")
  evidence_parent=$(dirname -- "$EVIDENCE_OUTPUT")
  mkdir -p -- "$summary_parent" "$evidence_parent"
  summary_parent=$(cd "$summary_parent" && pwd -P)
  evidence_parent=$(cd "$evidence_parent" && pwd -P)
  [[ "$summary_parent" == "$evidence_parent" ]] || {
    echo '[FAIL] demo summary and evidence outputs must share one parent directory.' >&2
    exit 1
  }
  SUMMARY_OUTPUT="$summary_parent/$(basename -- "$SUMMARY_OUTPUT")"
  EVIDENCE_OUTPUT="$evidence_parent/$(basename -- "$EVIDENCE_OUTPUT")"
  [[ ! -e "$SUMMARY_OUTPUT" && ! -L "$SUMMARY_OUTPUT" &&
     ! -e "$EVIDENCE_OUTPUT" && ! -L "$EVIDENCE_OUTPUT" ]] || {
    echo '[FAIL] canonical demo output path already exists.' >&2
    exit 1
  }
fi
mkdir -p -- "$evidence_staging"

control_curl() {
  # token은 process argv나 URL에 넣지 않고 curl config stdin으로만 전달한다.
  printf 'header = "Authorization: Bearer %s"\n' "$CONTROL_API_TOKEN" |
    curl --config - "$@"
}

import_artifact() {
  local file=$1
  control_curl --fail --silent --show-error --max-time 30 \
    -X POST "$SERVER_URL/api/v1/artifacts/import" \
    -H 'Content-Type: text/yaml' --data-binary "@$file" >/dev/null
}

for artifact in \
  "$FIXTURE_ROOT/provider-contract.yaml" \
  "$FIXTURE_ROOT/target-profile-vulnerable.yaml" \
  "$FIXTURE_ROOT/target-profile-resilient.yaml" \
  "$FIXTURE_ROOT/scenario.yaml" \
  "$FIXTURE_ROOT/scenario-callback-duplicate.yaml"
do
  import_artifact "$artifact"
done

run_one() {
  local key=$1
  local scenario=$2
  local target=$3
  local payment_id=$4
  local expected_status=$5
  local seed=$6
  local create_response="$temporary_root/$key-create.json"
  local run_response="$temporary_root/$key-run.json"
  local timeline="$temporary_root/$key-timeline.json"
  local report="$temporary_root/$key-report.json"
  local result="$temporary_root/$key-result.json"
  local evidence_directory="$evidence_staging/$key"
  local evidence_timeline="$evidence_directory/timeline.json"
  local evidence_report="$evidence_directory/report.json"
  local request run_id status callback_event_id timeline_sha256 report_sha256

  request=$(jq -cn \
    --arg scenario "$scenario" --arg target "$target" --arg payment "$payment_id" \
    --argjson seed "$seed" \
    '{scenarioRef:{name:$scenario,version:"1.0.0"},targetProfile:$target,
      seed:$seed,variables:{paymentId:$payment}}')
  control_curl --fail --silent --show-error --max-time 30 \
    -X POST "$SERVER_URL/api/v1/runs" -H 'Content-Type: application/json' \
    -d "$request" >"$create_response"
  run_id=$(jq -er '.runId | select(test(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
  ))' "$create_response")

  status=RUNNING
  for _ in $(seq 1 180); do
    control_curl --fail --silent --show-error --max-time 15 \
      "$SERVER_URL/api/v1/runs/$run_id" >"$run_response"
    status=$(jq -er '.status' "$run_response")
    case "$status" in
      PASSED|FAILED|ERROR|CANCELLED) break ;;
    esac
    sleep 1
  done
  [[ "$status" == "$expected_status" ]] || {
    echo "[FAIL] $scenario/$target expected $expected_status but was $status" >&2
    return 1
  }

  control_curl --fail --silent --show-error --max-time 30 \
    "$SERVER_URL/api/v1/runs/$run_id/timeline" >"$timeline"
  control_curl --fail --silent --show-error --max-time 30 \
    "$SERVER_URL/api/v1/runs/$run_id/reports/json" >"$report"

  jq -e --arg run "$run_id" --arg scenario "$scenario" --arg target "$target" \
    'length > 0 and
     ([.[].sequence] == ([.[].sequence] | sort)) and
     (([.[].sequence] | unique | length) == length) and
     ([.[] | select(.type == "RUN_STARTED")] | length) == 1 and
     ([.[] | select(.type == "RUN_PASSED" or .type == "RUN_FAILED")] | length) == 1' \
    "$timeline" >/dev/null
  jq -e --arg run "$run_id" --arg scenario "$scenario" --arg target "$target" \
    --arg status "$expected_status" '
      .runId == $run and .scenario.name == $scenario and
      .targetProfile == $target and .status == $status
    ' "$report" >/dev/null

  if [[ "$scenario" == 'payment-timeout-after-commit' ]]; then
    jq -e --arg target "$target" '
      .summary.providerCommits == 1 and .summary.callbackDeliveries == 0 and
      .summary.callbackOmitted == 1 and .summary.probeAttempts >= 1 and
      (if $target == "resilient-consumer" then true
       else (.failures | length) >= 1 end)
    ' "$report" >/dev/null
  else
    callback_event_id=evt-pay-2001-success
    jq -e '.summary.providerCommits == 1 and .summary.callbackDeliveries == 3' \
      "$report" >/dev/null
    jq -e --arg event "$callback_event_id" '
      ([.[] | select(.type == "CALLBACK_DELIVERY_SUCCEEDED" and
        .attributes.eventId == $event)] | length) == 3
    ' "$timeline" >/dev/null
  fi

  mkdir -- "$evidence_directory"
  jq -S . "$timeline" >"$evidence_timeline"
  jq -S . "$report" >"$evidence_report"
  if grep -Fq -f <(printf '%s\n' "$CONTROL_API_TOKEN") -- \
      "$evidence_timeline" "$evidence_report"; then
    echo "[FAIL] $scenario/$target evidence contains the control token." >&2
    return 1
  fi
  timeline_sha256=$(sha256sum "$evidence_timeline" | awk '{print $1}')
  report_sha256=$(sha256sum "$evidence_report" | awk '{print $1}')

  jq -n \
    --arg runId "$run_id" --arg status "$status" --arg expectedStatus "$expected_status" \
    --arg targetProfile "$target" --arg paymentId "$payment_id" \
    --arg timelinePath "payment-network-runs/$key/timeline.json" \
    --arg reportPath "payment-network-runs/$key/report.json" \
    --arg timelineSha256 "$timeline_sha256" --arg reportSha256 "$report_sha256" \
    --arg callbackEventId "${callback_event_id:-}" \
    --slurpfile timeline "$timeline" --slurpfile report "$report" '
    {
      runId:$runId,status:$status,expectedStatus:$expectedStatus,
      targetProfile:$targetProfile,paymentId:$paymentId,
      timelinePath:$timelinePath,reportPath:$reportPath,
      timelineSha256:$timelineSha256,reportSha256:$reportSha256,
      callbackEventId:(if $callbackEventId == "" then null else $callbackEventId end),
      timelineEventCount:($timeline[0] | length),
      providerCommitCount:($report[0].summary.providerCommits),
      responseFaultCount:([$timeline[0][] | select(.type == "PROVIDER_RESPONSE_FAULT_APPLIED")] | length),
      callbackDeliveryCount:($report[0].summary.callbackDeliveries),
      callbackOmittedCount:($report[0].summary.callbackOmitted),
      probeAttemptCount:($report[0].summary.probeAttempts),
      providerQueryCount:([$timeline[0][] | select(.type == "PROVIDER_QUERY_RECEIVED")] | length)
    }
  ' >"$result"
  printf '%s\n' "[PASS] $scenario/$target -> $status ($run_id)"
}

run_one timeout-vulnerable payment-timeout-after-commit vulnerable-consumer \
  pay-network-timeout-vulnerable FAILED 42001
run_one timeout-resilient payment-timeout-after-commit resilient-consumer \
  pay-network-timeout-resilient PASSED 42001
run_one callback-vulnerable payment-success-callback-duplicate vulnerable-consumer \
  pay-network-callback-vulnerable FAILED 42002
run_one callback-resilient payment-success-callback-duplicate resilient-consumer \
  pay-network-callback-resilient PASSED 42002

summary_temporary="$temporary_root/payment-network-summary.json"
jq -n \
  --slurpfile timeoutVulnerable "$temporary_root/timeout-vulnerable-result.json" \
  --slurpfile timeoutResilient "$temporary_root/timeout-resilient-result.json" \
  --slurpfile callbackVulnerable "$temporary_root/callback-vulnerable-result.json" \
  --slurpfile callbackResilient "$temporary_root/callback-resilient-result.json" '
  {
    schemaVersion:2,result:"PASS",
    scenarios:{
      timeoutAfterCommit:{name:"payment-timeout-after-commit",
        vulnerable:$timeoutVulnerable[0],resilient:$timeoutResilient[0]},
      callbackDuplicate:{name:"payment-success-callback-duplicate",
        vulnerable:$callbackVulnerable[0],resilient:$callbackResilient[0]}
    }
  }
' >"$summary_temporary"

if [[ -n "$SUMMARY_OUTPUT" ]]; then
  mv -T -n -- "$evidence_staging" "$EVIDENCE_OUTPUT"
  [[ ! -e "$evidence_staging" && ! -L "$evidence_staging" &&
     -d "$EVIDENCE_OUTPUT" && ! -L "$EVIDENCE_OUTPUT" ]] || {
    echo '[FAIL] demo evidence output was not published exactly.' >&2
    exit 1
  }
  evidence_publish_armed=1
  mv -T -n -- "$summary_temporary" "$SUMMARY_OUTPUT"
  [[ ! -e "$summary_temporary" && ! -L "$summary_temporary" &&
     -f "$SUMMARY_OUTPUT" && ! -L "$SUMMARY_OUTPUT" ]] || {
    echo '[FAIL] demo summary output was not published exactly.' >&2
    exit 1
  }
  summary_publish_armed=1
else
  jq -c . "$summary_temporary"
fi

echo '[PASS] payment network matrix: timeout/probe + duplicate callback, vulnerable FAILED / resilient PASSED'
