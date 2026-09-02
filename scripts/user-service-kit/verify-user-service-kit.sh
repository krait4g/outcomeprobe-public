#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  printf '%s\n' \
    'usage:' \
    '  verify-user-service-kit.sh --repository <repo> --output <absent-dir> --source-sha <sha>' \
    '  verify-user-service-kit.sh --verify-summary <summary> --source-sha <sha>' >&2
  exit 2
}

repository=''
output=''
summary=''
source_sha=''
while (($#)); do
  case "$1" in
    --repository) (($# >= 2)) || usage; repository=$2; shift 2 ;;
    --output) (($# >= 2)) || usage; output=$2; shift 2 ;;
    --verify-summary) (($# >= 2)) || usage; summary=$2; shift 2 ;;
    --source-sha) (($# >= 2)) || usage; source_sha=$2; shift 2 ;;
    *) usage ;;
  esac
done
[[ $source_sha =~ ^([0-9a-f]{40}|[0-9a-f]{64})$ ]] || usage

python_command=${PYTHON_COMMAND:-python3}
script_root=$(cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
helper=$script_root/create_user_service_kit_summary.py
mapfile -t ambient_keys < <(compgen -e | grep '^OUTCOMEPROBE_' || true)

isolated() {
  local -a command=(env)
  local key
  for key in "${ambient_keys[@]}"; do
    command+=(-u "$key")
  done
  "${command[@]}" "$@"
}

if [[ -n $summary ]]; then
  [[ -z $repository && -z $output && -f $summary && ! -L $summary ]] || usage
  isolated "$python_command" "$helper" verify --summary "$summary" --source-sha "$source_sha"
  printf '%s\n' 'OutcomeProbe user-service kit summary: PASS'
  exit 0
fi

[[ -n $repository && -n $output ]] || usage
repository=$(cd -P -- "$repository" && pwd)
helper=$repository/scripts/user-service-kit/create_user_service_kit_summary.py
for required in \
  "$repository/compose.yaml" \
  "$repository/compose.realtime-track.yaml" \
  "$repository/.dockerignore" \
  "$repository/docker/realtime-kit.Dockerfile" \
  "$repository/docker/realtime-kit.sh" \
  "$repository/examples/realtime-track/provider-contract.yaml" \
  "$repository/examples/realtime-track/target-profile.compose.yaml" \
  "$repository/examples/realtime-track/scenario-single-event.yaml" \
  "$repository/examples/realtime-track/scenario-duplicate-3x.yaml" \
  "$repository/examples/realtime-track/scenario-out-of-order.yaml" \
  "$helper"
do
  [[ -f $required && ! -L $required ]] || {
    printf '%s\n' 'required user-service kit file is missing or unsafe' >&2
    exit 2
  }
done
for required_ignore in \
  '.user-service-kit.runtime.*/' \
  '**/.user-service-kit.runtime.*/' \
  '.user-service-kit.staging.*/' \
  '**/.user-service-kit.staging.*/'
do
  grep -Fqx -- "$required_ignore" "$repository/.dockerignore" || {
    printf '%s\n' 'Docker build context does not exclude user-service kit temporary trees' >&2
    exit 2
  }
done
[[ ! -e $repository/.env.secrets ]] || {
  printf '%s\n' 'remove the optional .env.secrets file before bounded kit verification' >&2
  exit 2
}
command -v docker >/dev/null || { printf '%s\n' 'docker is required' >&2; exit 2; }
isolated docker compose version >/dev/null || {
  printf '%s\n' 'docker compose is required' >&2
  exit 2
}
[[ ! -e $output && ! -L $output ]] || {
  printf '%s\n' 'output must not exist' >&2
  exit 2
}
output_parent=$(dirname -- "$output")
mkdir -p -- "$output_parent"
output_parent=$(cd -P -- "$output_parent" && pwd)
output=$output_parent/$(basename -- "$output")

runtime_root=$(mktemp -d "${TMPDIR:-/tmp}/outcomeprobe-user-service-kit.runtime.XXXXXX")
runtime_root=$(cd -P -- "$runtime_root" && pwd)
case "$runtime_root/" in
  "$repository/"*)
    rmdir -- "$runtime_root"
    printf '%s\n' 'temporary credential root must be outside the repository' >&2
    exit 2
    ;;
esac
staging_output=$(mktemp -d "$output_parent/.user-service-kit.staging.XXXXXX")
evidence_dir=$runtime_root/evidence
mkdir -- "$evidence_dir"
chmod 0777 "$evidence_dir"
env_file=$runtime_root/runtime.env
project="outcomeprobe-userkit-$$-$RANDOM"
project=${project,,}
runtime_cleaned=false
published=false

compose() {
  isolated docker compose --env-file "$env_file" -p "$project" \
    -f "$repository/compose.yaml" \
    -f "$repository/compose.realtime-track.yaml" "$@"
}

cleanup_runtime() {
  local cleanup_code=0 containers networks volumes
  if [[ $runtime_cleaned != true ]]; then
    if [[ -f $env_file ]]; then
      compose down --volumes --remove-orphans >"$runtime_root/down.log" 2>&1 || cleanup_code=1
    fi
    containers=$(isolated docker ps -aq --filter "label=com.docker.compose.project=$project") \
      || cleanup_code=1
    if [[ -n $containers ]]; then
      isolated docker rm -f $containers >>"$runtime_root/down.log" 2>&1 || cleanup_code=1
    fi
    networks=$(isolated docker network ls -q --filter "label=com.docker.compose.project=$project") \
      || cleanup_code=1
    if [[ -n $networks ]]; then
      isolated docker network rm $networks >>"$runtime_root/down.log" 2>&1 || cleanup_code=1
    fi
    volumes=$(isolated docker volume ls -q --filter "label=com.docker.compose.project=$project") \
      || cleanup_code=1
    if [[ -n $volumes ]]; then
      isolated docker volume rm $volumes >>"$runtime_root/down.log" 2>&1 || cleanup_code=1
    fi
    containers=$(isolated docker ps -aq --filter "label=com.docker.compose.project=$project") \
      || cleanup_code=1
    networks=$(isolated docker network ls -q --filter "label=com.docker.compose.project=$project") \
      || cleanup_code=1
    volumes=$(isolated docker volume ls -q --filter "label=com.docker.compose.project=$project") \
      || cleanup_code=1
    if [[ -n $containers || -n $networks || -n $volumes ]]; then
      cleanup_code=1
    fi
    if [[ $cleanup_code -eq 0 ]]; then
      runtime_cleaned=true
    fi
  fi
  return "$cleanup_code"
}

remove_owned_tree() {
  local target=$1
  local expected_parent=$2
  [[ -n $target && $target != / && -d $target && ! -L $target ]] || return 1
  [[ $(cd -P -- "$(dirname -- "$target")" && pwd) == "$expected_parent" ]] || return 1
  rm -rf -- "$target"
  [[ ! -e $target && ! -L $target ]]
}

finish() {
  local status=$?
  trap - EXIT HUP INT TERM
  if [[ $runtime_cleaned != true ]]; then
    cleanup_runtime || status=1
  fi
  unset db_password demo_callback_secret control_token emulator_token target_token callback_secret
  if [[ -d $runtime_root ]]; then
    remove_owned_tree "$runtime_root" "$(cd -P -- "$(dirname -- "$runtime_root")" && pwd)" \
      || status=1
  fi
  if [[ -d $staging_output ]]; then
    remove_owned_tree "$staging_output" "$output_parent" || status=1
  fi
  if [[ $status -ne 0 && $published == true && -d $output ]]; then
    remove_owned_tree "$output" "$output_parent" || status=1
  fi
  exit "$status"
}
trap finish EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

random_secret() {
  isolated "$python_command" -c 'import secrets; print(secrets.token_hex(32))'
}
db_password=$(random_secret)
demo_callback_secret=$(random_secret)
control_token=$(random_secret)
emulator_token=$(random_secret)
target_token=$(random_secret)
callback_secret=$(random_secret)
if [[ $(printf '%s\n' \
    "$db_password" "$demo_callback_secret" "$control_token" \
    "$emulator_token" "$target_token" "$callback_secret" | sort -u | wc -l) -ne 6 ]]; then
  printf '%s\n' 'generated credentials were not unique' >&2
  exit 1
fi

{
  printf 'OUTCOMEPROBE_DB_NAME=outcomeprobe\n'
  printf 'OUTCOMEPROBE_DB_USER=outcomeprobe\n'
  printf 'OUTCOMEPROBE_DB_PASSWORD=%s\n' "$db_password"
  printf 'OUTCOMEPROBE_DEMO_CALLBACK_SECRET=%s\n' "$demo_callback_secret"
  printf 'OUTCOMEPROBE_CONTROL_API_TOKEN=%s\n' "$control_token"
  printf 'OUTCOMEPROBE_EMULATOR_TOKEN=%s\n' "$emulator_token"
  printf 'OUTCOMEPROBE_REALTIME_TARGET_TOKEN=%s\n' "$target_token"
  printf 'OUTCOMEPROBE_REALTIME_CALLBACK_SECRET=%s\n' "$callback_secret"
  printf 'OUTCOMEPROBE_RETENTION_DAYS=14\n'
  printf 'OUTCOMEPROBE_MAX_CONCURRENT_RUNS=10\n'
  printf 'OUTCOMEPROBE_DEMO_PROFILE_SEEDING_ENABLED=false\n'
  printf 'OUTCOMEPROBE_LOG_LEVEL=INFO\n'
  printf 'OUTCOMEPROBE_SERVER_PORT=0\n'
  printf 'OUTCOMEPROBE_WEB_PORT=0\n'
} >"$env_file"
chmod 0600 "$env_file"

compose up -d --build --wait --wait-timeout 600 \
  postgres realtime-track-target outcomeprobe-server >"$runtime_root/up.log" 2>&1

target_container=$(compose ps -q realtime-track-target)
[[ -n $target_container ]] || { printf '%s\n' 'realtime Target container is missing' >&2; exit 1; }

assert_hardened() {
  local container=$1
  local expected_network=$2
  local user readonly cap_drop security_opt pids networks
  user=$(isolated docker inspect --format '{{.Config.User}}' "$container")
  readonly=$(isolated docker inspect --format '{{.HostConfig.ReadonlyRootfs}}' "$container")
  cap_drop=$(isolated docker inspect --format '{{json .HostConfig.CapDrop}}' "$container")
  security_opt=$(isolated docker inspect --format '{{json .HostConfig.SecurityOpt}}' "$container")
  pids=$(isolated docker inspect --format '{{.HostConfig.PidsLimit}}' "$container")
  networks=$(isolated docker inspect \
    --format '{{range $name, $_ := .NetworkSettings.Networks}}{{$name}}{{"\n"}}{{end}}' \
    "$container")
  [[ $user == 10001:10001 && $readonly == true && $cap_drop == '["ALL"]' \
    && $security_opt == '["no-new-privileges:true"]' && $pids == 128 \
    && $networks == "$expected_network" ]] || {
    printf '%s\n' 'container hardening or network boundary mismatch' >&2
    return 1
  }
}

assert_hardened "$target_container" "${project}_app"
server_container=$(compose ps -q outcomeprobe-server)
[[ -n $server_container ]] || { printf '%s\n' 'OutcomeProbe server container is missing' >&2; exit 1; }
missing_auth_status=$(isolated docker exec "$server_container" curl -sS -o /dev/null -w '%{http_code}' \
  http://realtime-track-target:8181/test-support/outcomeprobe/radar-state/auth-check)
wrong_auth_token='synthetic-'
wrong_auth_token+='wrong-auth-'
wrong_auth_token+='fixture-value'
wrong_auth_status=$(isolated docker exec "$server_container" curl -sS -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer $wrong_auth_token" \
  http://realtime-track-target:8181/test-support/outcomeprobe/radar-state/auth-check)
unset wrong_auth_token
[[ $missing_auth_status == 401 && $wrong_auth_status == 401 ]] || {
  printf '%s\n' 'realtime Target accepted missing or invalid authorization' >&2
  exit 1
}
host_binding=$(isolated docker inspect \
  --format '{{with index .NetworkSettings.Ports "8181/tcp"}}{{json .}}{{end}}' \
  "$target_container")
[[ -z $host_binding || $host_binding == null ]] || {
  printf '%s\n' 'realtime Target has an unexpected host binding' >&2
  exit 1
}

compose build outcomeprobe-realtime-kit >"$runtime_root/kit-build.log" 2>&1
runner_name="${project}-realtime-kit"
compose run -d --no-deps --name "$runner_name" \
  outcomeprobe-realtime-kit >"$runtime_root/kit-start.log" 2>&1
assert_hardened "$runner_name" "${project}_app"
runner_evidence_mount=$(isolated docker inspect --format \
  '{{range .Mounts}}{{if eq .Destination "/evidence"}}{{.Type}}|{{.RW}}|{{.Name}}{{end}}{{end}}' \
  "$runner_name")
[[ $runner_evidence_mount == "volume|true|${project}_realtime-kit-evidence" ]] || {
  printf '%s\n' 'runner evidence volume boundary mismatch' >&2
  exit 1
}
runner_process=$(isolated docker inspect --format '{{json .Args}}' "$runner_name")
for secret in "$control_token" "$target_token" "$callback_secret"; do
  [[ $runner_process != *"$secret"* ]] || {
    printf '%s\n' 'runner argv contains a credential' >&2
    exit 1
  }
done
runner_exit=$(isolated docker wait "$runner_name")
isolated docker logs "$runner_name" >"$runtime_root/kit.log" 2>&1
if [[ $runner_exit != 0 ]]; then
  safe_evidence_detail=$(grep -E '^user-service kit evidence verification failed: [A-Za-z0-9 ._:/-]+$' \
    "$runtime_root/kit.log" | tail -n 1 || true)
  safe_python_error=$(grep -E '^(PermissionError|FileNotFoundError|FileExistsError|OSError|TypeError|ValueError):' \
    "$runtime_root/kit.log" | tail -n 1 | cut -d: -f1 || true)
  safe_diagnostic=$(grep -E '^OutcomeProbe realtime user-service kit failed at stage: (initialization|validate|import|doctor|run-single|run-duplicate|run-out-of-order|normalize-collect|normalize-permissions)$' \
    "$runtime_root/kit.log" | tail -n 1 || true)
  if [[ -n $safe_evidence_detail ]]; then
    printf '%s\n' "$safe_evidence_detail" >&2
  elif [[ -n $safe_python_error ]]; then
    printf 'user-service kit evidence Python failure: %s\n' "$safe_python_error" >&2
  else
    printf '%s\n' "${safe_diagnostic:-user-service kit runner failed without a safe stage diagnostic}" >&2
  fi
  exit 1
fi
isolated docker cp "$runner_name:/evidence/." "$evidence_dir"
isolated docker rm "$runner_name" >"$runtime_root/kit-remove.log"
runner_residue=$(isolated docker ps -aq \
  --filter "label=com.docker.compose.project=$project" \
  --filter 'label=com.docker.compose.service=outcomeprobe-realtime-kit')
[[ -z $runner_residue ]] || { printf '%s\n' 'transient runner cleanup failed' >&2; exit 1; }
compose logs --no-color postgres realtime-track-target outcomeprobe-server \
  >"$runtime_root/services.log" 2>&1

for secret in \
  "$db_password" "$demo_callback_secret" "$control_token" \
  "$emulator_token" "$target_token" "$callback_secret"
do
  if grep -R -F -q --exclude=runtime.env -- "$secret" "$runtime_root"; then
    printf '%s\n' 'runtime evidence or logs contain a generated credential' >&2
    exit 1
  fi
done
[[ -f $evidence_dir/realtime-track-run-receipt.json && -d $evidence_dir/runs ]] || {
  printf '%s\n' 'kit did not produce its bounded runtime receipt' >&2
  exit 1
}

cleanup_runtime
containers=$(isolated docker ps -aq --filter "label=com.docker.compose.project=$project" \
  | wc -l | tr -d '[:space:]')
networks=$(isolated docker network ls -q --filter "label=com.docker.compose.project=$project" \
  | wc -l | tr -d '[:space:]')
volumes=$(isolated docker volume ls -q --filter "label=com.docker.compose.project=$project" \
  | wc -l | tr -d '[:space:]')
[[ $containers == 0 && $networks == 0 && $volumes == 0 ]] || {
  printf '%s\n' 'compose cleanup boundary is incomplete' >&2
  exit 1
}

cp -R -- "$evidence_dir/runs" "$staging_output/runs"
rm -f -- "$env_file"
[[ ! -e $env_file ]] || { printf '%s\n' 'temporary credential file cleanup failed' >&2; exit 1; }
unset db_password demo_callback_secret control_token emulator_token target_token callback_secret

isolated "$python_command" "$helper" finalize \
  --receipt "$evidence_dir/realtime-track-run-receipt.json" \
  --source-sha "$source_sha" \
  --output "$staging_output/user-service-kit-summary.json"
isolated "$python_command" "$helper" verify \
  --summary "$staging_output/user-service-kit-summary.json" \
  --source-sha "$source_sha"

mv -T -n -- "$staging_output" "$output"
[[ -d $output && ! -e $staging_output ]] || {
  printf '%s\n' 'atomic evidence publication failed' >&2
  exit 1
}
published=true
printf '%s\n' 'OutcomeProbe user-service kit runtime: PASS'
