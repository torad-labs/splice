#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

mkdir -p "$SANDBOX/bin" "$SANDBOX/home" "$SANDBOX/share" "$SANDBOX/state"
touch "$SANDBOX/share/splice.jar"
printf 'test-key\n' > "$SANDBOX/state/mgmt-key"
printf '[daemon]\ncontrol_port = 4567 # custom\n' > "$SANDBOX/splice.toml"
printf 'up\n' > "$SANDBOX/daemon-state"

cat > "$SANDBOX/bin/curl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
url=""
data=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --data)
      data="${2:-}"
      shift 2
      ;;
    http://*)
      url="$1"
      shift
      ;;
    *)
      shift
      ;;
  esac
done
case "$url" in
  */health)
    case "$(cat "$LAUNCHER_DAEMON_STATE")" in
      up|new) printf '{"ok":true,"version":"0.1.1","wantShimVersion":"shim-2"}\n' ;;
      old) printf '{"ok":true,"version":"0.0.9","wantShimVersion":"shim-1"}\n' ;;
      down) ;;
    esac
    ;;
  */api/daemon/shutdown)
    printf 'down\n' > "$LAUNCHER_DAEMON_STATE"
    printf '%s\n' "$url" > "$LAUNCHER_SHUTDOWN_CAPTURE"
    printf '{"ok":true}\n'
    ;;
  */launch/test)
    printf '%s\n' "$url" > "$LAUNCHER_URL_CAPTURE"
    printf '%s' "$data" > "$LAUNCHER_BODY_CAPTURE"
    if [ "${LAUNCHER_INJECT_ENV_KEY:-0}" = "1" ]; then
      printf '{"env":{"X$(touch %s)":"v"},"unset":[],"argv":["true"]}\n' "$LAUNCHER_PWNED_FILE"
    else
      printf '{"env":{},"unset":[],"argv":["true"]}\n'
    fi
    ;;
  *)
    printf 'unexpected curl URL: %s\n' "$url" >&2
    exit 2
    ;;
esac
SH

cat > "$SANDBOX/bin/java" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf 'new\n' > "$LAUNCHER_DAEMON_STATE"
SH
chmod +x "$SANDBOX/bin/curl" "$SANDBOX/bin/java"

run_launcher() {
  HOME="$SANDBOX/home" \
  PATH="$SANDBOX/bin:$PATH" \
  SPLICE_HEAD=test \
  SPLICE_CONFIG="$SANDBOX/splice.toml" \
  SPLICE_SHARE_DIR="$SANDBOX/share" \
  CLAUDEX_STATE_DIR="$SANDBOX/state" \
  LAUNCHER_DAEMON_STATE="$SANDBOX/daemon-state" \
  LAUNCHER_URL_CAPTURE="$SANDBOX/url" \
  LAUNCHER_BODY_CAPTURE="$SANDBOX/body" \
  LAUNCHER_SHUTDOWN_CAPTURE="$SANDBOX/shutdown" \
  LAUNCHER_INJECT_ENV_KEY="${LAUNCHER_INJECT_ENV_KEY:-0}" \
  LAUNCHER_PWNED_FILE="$SANDBOX/pwned" \
    "$ROOT/bin/splice-launch" "$@"
}

run_launcher "" $'line one\nline two'
python3 - "$SANDBOX/url" "$SANDBOX/body" <<'PY'
import json
import pathlib
import sys

url = pathlib.Path(sys.argv[1]).read_text().strip()
body = json.loads(pathlib.Path(sys.argv[2]).read_text())
assert url == "http://127.0.0.1:4567/launch/test", url
assert body["args"] == ["", "line one\nline two"], body
PY

# State config is above TOML in ConfigService's precedence and the shell shim must resolve the
# same port or it will probe/launch the daemon at one address and call another.
printf '{"controlPort":4568}\n' > "$SANDBOX/state/config.json"
run_launcher
test "$(cat "$SANDBOX/url")" = "http://127.0.0.1:4568/launch/test"

printf 'old\n' > "$SANDBOX/daemon-state"
rm -f "$SANDBOX/shutdown"
run_launcher
test "$(cat "$SANDBOX/shutdown")" = "http://127.0.0.1:4568/api/daemon/shutdown"
test "$(cat "$SANDBOX/daemon-state")" = "new"

# Regression: a recipe env key containing a command substitution must never reach the shell
# unquoted. The mock daemon returns env key `X$(touch $LAUNCHER_PWNED_FILE)`; the launcher must
# drop it (warning to stderr) instead of executing it via `eval "$CMD"`, and must still exit
# cleanly on the rest of the recipe.
printf 'new\n' > "$SANDBOX/daemon-state"
rm -f "$SANDBOX/pwned"
LAUNCHER_INJECT_ENV_KEY=1 run_launcher
test ! -e "$SANDBOX/pwned"

echo "launcher test: OK"
