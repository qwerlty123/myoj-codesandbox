#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
TEST_ROOT=$(mktemp -d)
FAKE_BIN="$TEST_ROOT/bin"
mkdir -p "$FAKE_BIN"
trap 'rm -rf "$TEST_ROOT"' EXIT

export DEPLOY_TEST_ROOT="$TEST_ROOT"

cat >"$FAKE_BIN/mvn" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF

cat >"$FAKE_BIN/shasum" <<'EOF'
#!/usr/bin/env bash
printf '%064d  artifact.jar\n' 0
EOF

cat >"$FAKE_BIN/scp" <<'EOF'
#!/usr/bin/env bash
for argument in "$@"; do
  case "$argument" in
    */remote-install-codesandbox.sh)
      basename "$argument" >"$DEPLOY_TEST_ROOT/uploaded-helper-name"
      ;;
  esac
done
test -s "$DEPLOY_TEST_ROOT/uploaded-helper-name"
EOF

cat >"$FAKE_BIN/ssh" <<'EOF'
#!/usr/bin/env bash
for argument in "$@"; do
  remote_command="$argument"
done
uploaded_name=$(cat "$DEPLOY_TEST_ROOT/uploaded-helper-name")
expected_path="/tmp/$uploaded_name"
if [[ "$remote_command" != *"bash '$expected_path'"* ]]; then
  printf 'uploaded helper is %s but remote command was: %s\n' \
    "$expected_path" "$remote_command" >&2
  exit 1
fi
EOF

chmod +x "$FAKE_BIN/mvn" "$FAKE_BIN/shasum" "$FAKE_BIN/scp" "$FAKE_BIN/ssh"

printf '\ny\n' | PATH="$FAKE_BIN:$PATH" bash "$SCRIPT_DIR/deploy-server.sh" >/dev/null
printf 'deploy helper upload and execution paths match\n'
