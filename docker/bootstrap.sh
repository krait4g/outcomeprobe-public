#!/bin/sh
set -eu

# 공개 Quick Start의 canonical Artifact 다섯 개를 서버 validation core로 모두 확인한 뒤
# 참조 의존 순서대로 멱등 import한다. token 원문은 argv·로그로 전달하지 않는다.

server_url=${OUTCOMEPROBE_SERVER_URL:-http://outcomeprobe-server:8080}
cli_jar=/opt/outcomeprobe/outcomeprobe-cli.jar
fixture_root=/opt/outcomeprobe/golden-path/payment

if [ ! -f "$cli_jar" ] || [ ! -r "$cli_jar" ]; then
  printf '%s\n' 'bootstrap CLI를 읽을 수 없습니다.' >&2
  exit 2
fi
if [ -z "${OUTCOMEPROBE_CONTROL_API_TOKEN:-}" ]; then
  printf '%s\n' 'Control API token이 설정되지 않았습니다.' >&2
  exit 2
fi

provider_contract="$fixture_root/provider-contract.yaml"
vulnerable_profile="$fixture_root/target-profile-vulnerable.yaml"
resilient_profile="$fixture_root/target-profile-resilient.yaml"
scenario="$fixture_root/scenario.yaml"
callback_scenario="$fixture_root/scenario-callback-duplicate.yaml"

for artifact in \
  "$provider_contract" \
  "$vulnerable_profile" \
  "$resilient_profile" \
  "$scenario" \
  "$callback_scenario"
do
  if [ ! -f "$artifact" ] || [ ! -r "$artifact" ]; then
    printf '%s\n' 'canonical bootstrap Artifact를 읽을 수 없습니다.' >&2
    exit 2
  fi
done

printf '%s\n' 'OutcomeProbe canonical Artifact 검증을 시작합니다.'
for artifact in \
  "$provider_contract" \
  "$vulnerable_profile" \
  "$resilient_profile" \
  "$scenario" \
  "$callback_scenario"
do
  java -jar "$cli_jar" validate "$artifact" --server "$server_url"
done

printf '%s\n' 'OutcomeProbe canonical Artifact import를 시작합니다.'
for artifact in \
  "$provider_contract" \
  "$vulnerable_profile" \
  "$resilient_profile" \
  "$scenario" \
  "$callback_scenario"
do
  java -jar "$cli_jar" import "$artifact" --server "$server_url"
done

printf '%s\n' 'OutcomeProbe Quick Start bootstrap이 완료되었습니다.'
