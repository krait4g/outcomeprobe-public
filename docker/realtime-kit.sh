#!/bin/sh
set -eu

umask 077
server_url=${OUTCOMEPROBE_SERVER_URL:-http://outcomeprobe-server:8080}
cli_jar=/opt/outcomeprobe/outcomeprobe-cli.jar
helper=/opt/outcomeprobe/create_user_service_kit_summary.py
fixture_root=/opt/outcomeprobe/realtime-track
work_dir=$(mktemp -d /tmp/outcomeprobe-realtime-kit.XXXXXX)
current_stage=initialization

cleanup() {
  primary_status=$?
  cleanup_status=0
  trap - EXIT HUP INT TERM
  if [ "$primary_status" -ne 0 ]; then
    printf 'OutcomeProbe realtime user-service kit failed at stage: %s\n' "$current_stage" >&2
  fi
  case "$work_dir" in
    /tmp/outcomeprobe-realtime-kit.*) ;;
    *) cleanup_status=1 ;;
  esac
  if [ "$cleanup_status" -eq 0 ]; then
    rm -rf -- "$work_dir" || cleanup_status=1
    [ ! -e "$work_dir" ] || cleanup_status=1
  fi
  if [ "$primary_status" -eq 0 ] && [ "$cleanup_status" -ne 0 ]; then
    exit 1
  fi
  exit "$primary_status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

control_token=${OUTCOMEPROBE_CONTROL_API_TOKEN:-}
target_token=${OUTCOMEPROBE_REALTIME_TARGET_TOKEN:-}
callback_secret=${OUTCOMEPROBE_REALTIME_CALLBACK_SECRET:-}
if [ "${#control_token}" -lt 32 ]; then
  printf '%s\n' 'required credential is missing or too short: OUTCOMEPROBE_CONTROL_API_TOKEN' >&2
  exit 2
fi
if [ "${#target_token}" -lt 32 ]; then
  printf '%s\n' 'required credential is missing or too short: OUTCOMEPROBE_REALTIME_TARGET_TOKEN' >&2
  exit 2
fi
if [ "${#callback_secret}" -lt 32 ]; then
  printf '%s\n' 'required credential is missing or too short: OUTCOMEPROBE_REALTIME_CALLBACK_SECRET' >&2
  exit 2
fi
if [ "$control_token" = "$target_token" ] \
  || [ "$control_token" = "$callback_secret" ] \
  || [ "$target_token" = "$callback_secret" ]; then
  printf '%s\n' 'runtime credentials must be distinct' >&2
  exit 2
fi
if [ -e /evidence/realtime-track-run-receipt.json ] || [ -e /evidence/runs ]; then
  printf '%s\n' 'evidence destination must be empty' >&2
  exit 2
fi

current_stage=validate
for artifact in \
  provider-contract.yaml \
  target-profile.compose.yaml \
  scenario-single-event.yaml \
  scenario-duplicate-3x.yaml \
  scenario-out-of-order.yaml
do
  java -jar "$cli_jar" validate "$fixture_root/$artifact" --server "$server_url" \
    >"$work_dir/validate-$artifact.log" 2>&1
done
current_stage=import
for artifact in \
  provider-contract.yaml \
  target-profile.compose.yaml \
  scenario-single-event.yaml \
  scenario-duplicate-3x.yaml \
  scenario-out-of-order.yaml
do
  java -jar "$cli_jar" import "$fixture_root/$artifact" --server "$server_url" \
    >"$work_dir/import-$artifact.log" 2>&1
done

current_stage=doctor
java -jar "$cli_jar" doctor --server "$server_url" --target realtime-track-compose \
  >"$work_dir/doctor.log" 2>&1

run_scenario() {
  key=$1
  name=$2
  seed=$3
  java -jar "$cli_jar" run \
    --scenario "$name@1.0.0" \
    --target realtime-track-compose \
    --seed "$seed" \
    --wait \
    --output json \
    --server "$server_url" \
    >"$work_dir/$key-report.json" 2>"$work_dir/$key-run.log"
  run_id=$(python "$helper" extract-run-id --report "$work_dir/$key-report.json")
  java -jar "$cli_jar" report "$run_id" --format junit \
    --out "$work_dir/$key-junit.xml" --server "$server_url" \
    >"$work_dir/$key-junit.log" 2>&1
  java -jar "$cli_jar" bundle "$run_id" --out "$work_dir/$key-bundle.zip" \
    --server "$server_url" >"$work_dir/$key-bundle.log" 2>&1
}

current_stage=run-single
run_scenario single realtime-track-single-event 90001
current_stage=run-duplicate
run_scenario duplicate realtime-track-duplicate-3x 90002
current_stage=run-out-of-order
run_scenario out-of-order realtime-track-out-of-order 90003

current_stage=normalize-collect
python "$helper" collect \
  --directory "$work_dir" \
  --server-url "$server_url" \
  --assets-output /evidence/runs \
  --output /evidence/realtime-track-run-receipt.json

current_stage=normalize-permissions
find /evidence/runs -type d -exec chmod 0777 {} +
find /evidence/runs -type f -exec chmod 0444 {} +
chmod 0444 /evidence/realtime-track-run-receipt.json

current_stage=complete
printf '%s\n' 'OutcomeProbe realtime user-service kit: PASS'
