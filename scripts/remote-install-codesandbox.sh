#!/usr/bin/env bash

set -euo pipefail

: "${DEPLOY_SHA:?DEPLOY_SHA is required}"
: "${REMOTE_JAR:?REMOTE_JAR is required}"

SERVICE_NAME="myoj-codesandbox"
DEPLOY_DIR="/opt/myoj-codesandbox"
DEPLOY_JAR="$DEPLOY_DIR/myoj-codesandbox.jar"
ENV_FILE="/etc/myoj-codesandbox.env"
WORKSPACE_ROOT="/var/lib/myoj-sandbox/work"
HEALTH_URL="http://127.0.0.1:8090/actuator/health"
BACKUP_JAR=""
DEPLOYMENT_STARTED=false

ensure_env_value() {
  local key="$1"
  local value="$2"
  if ! sudo grep -q "^${key}=" "$ENV_FILE"; then
    printf '%s=%s\n' "$key" "$value" | sudo tee -a "$ENV_FILE" >/dev/null
  fi
}

set_env_value() {
  local key="$1"
  local value="$2"
  if sudo grep -q "^${key}=" "$ENV_FILE"; then
    sudo sed -i "s/^${key}=.*/${key}=${value}/" "$ENV_FILE"
  else
    printf '%s=%s\n' "$key" "$value" | sudo tee -a "$ENV_FILE" >/dev/null
  fi
}

rollback_on_error() {
  local status=$?
  trap - ERR
  set +e
  printf '\nDeployment failed.\n' >&2
  if [[ "$DEPLOYMENT_STARTED" == true && -n "$BACKUP_JAR" ]]; then
    printf 'Restoring previous JAR: %s\n' "$BACKUP_JAR" >&2
    sudo systemctl stop "$SERVICE_NAME"
    sudo install -o myoj-sandbox -g myoj-sandbox -m 0640 \
      "$BACKUP_JAR" "$DEPLOY_JAR.rollback"
    sudo mv "$DEPLOY_JAR.rollback" "$DEPLOY_JAR"
    sudo systemctl start "$SERVICE_NAME"
  fi
  sudo journalctl -u "$SERVICE_NAME" -n 80 --no-pager >&2
  exit "$status"
}

trap rollback_on_error ERR

printf 'Checking uploaded artifact...\n'
test -s "$REMOTE_JAR"
actual_sha=$(sha256sum "$REMOTE_JAR" | awk '{print $1}')
if [[ "$actual_sha" != "$DEPLOY_SHA" ]]; then
  printf 'SHA-256 mismatch: expected %s, got %s\n' "$DEPLOY_SHA" "$actual_sha" >&2
  exit 1
fi

sudo test -f "$DEPLOY_JAR"
sudo test -f "$ENV_FILE"
if ! sudo grep -Eq '^CODESANDBOX_SECRET_KEY=.{32,}$' "$ENV_FILE"; then
  printf 'CODESANDBOX_SECRET_KEY is missing or shorter than 32 characters.\n' >&2
  exit 1
fi

ensure_env_value "CODESANDBOX_TYPE" "container"
ensure_env_value "CODESANDBOX_JAVA_IMAGE" "eclipse-temurin:17-jdk"
ensure_env_value "CODESANDBOX_CPP_IMAGE" "gcc:13"
ensure_env_value "CODESANDBOX_GO_IMAGE" "golang:1.22"
ensure_env_value "CODESANDBOX_WORKSPACE_ROOT" "$WORKSPACE_ROOT"
set_env_value "CODESANDBOX_MAX_CONCURRENT_EXECUTIONS" "2"
set_env_value "CODESANDBOX_QUEUE_WAIT_TIMEOUT_MS" "2000"

sudo install -d -o myoj-sandbox -g myoj-sandbox -m 0750 "$WORKSPACE_ROOT"

for image in eclipse-temurin:17-jdk gcc:13 golang:1.22; do
  if ! sudo docker image inspect "$image" >/dev/null 2>&1; then
    printf 'Pulling missing image %s...\n' "$image"
    sudo docker pull "$image"
  fi
done

BACKUP_JAR="$DEPLOY_DIR/myoj-codesandbox.jar.bak.$(date +%Y%m%d%H%M%S)"
printf 'Backing up current JAR to %s\n' "$BACKUP_JAR"
sudo cp -a "$DEPLOY_JAR" "$BACKUP_JAR"
DEPLOYMENT_STARTED=true

sudo systemctl stop "$SERVICE_NAME"
sudo install -o myoj-sandbox -g myoj-sandbox -m 0640 \
  "$REMOTE_JAR" "$DEPLOY_JAR.new"
sudo mv "$DEPLOY_JAR.new" "$DEPLOY_JAR"
sudo systemctl start "$SERVICE_NAME"

healthy=false
for attempt in $(seq 1 30); do
  if curl -fsS --max-time 2 "$HEALTH_URL" >/tmp/myoj-codesandbox-health.json; then
    healthy=true
    break
  fi
  sleep 1
done

if [[ "$healthy" != true ]]; then
  printf 'Health check did not pass within 30 seconds.\n' >&2
  false
fi

sudo systemctl is-active --quiet "$SERVICE_NAME"
sudo ss -lntp | grep ':8090' >/dev/null

printf 'Health response: '
cat /tmp/myoj-codesandbox-health.json
printf '\nDeployment succeeded. Backup: %s\n' "$BACKUP_JAR"

rm -f /tmp/myoj-codesandbox-health.json "$REMOTE_JAR"
DEPLOYMENT_STARTED=false
trap - ERR
