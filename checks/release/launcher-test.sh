#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# The mock daemon's "current" version is DERIVED from the shim under test, never hardcoded — a
# snapshot literal here goes stale on the first version bump and flips every "up" daemon to
# "stale", sending the launcher down the replace path against the mock (found by the v0.2.0 bump:
# the hardcoded 0.1.1 made this test fail on exactly the commit that mattered). Same awk as
# checks/release/accept.sh — one idiom for reading the marker.
GATEWAY_VERSION="$(awk -F'"' '/^SPLICE_GATEWAY_VERSION="/ { print $2; exit }' "$ROOT/bin/splice-launch")"
SHIM_VERSION="$(awk -F'"' '/^SPLICE_SHIM_VERSION="/ { print $2; exit }' "$ROOT/bin/splice-launch")"
[ -n "$GATEWAY_VERSION" ] && [ -n "$SHIM_VERSION" ] || {
  echo "launcher test: could not read version markers from bin/splice-launch" >&2
  exit 1
}

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
      up|new) printf '{"ok":true,"version":"%s","wantShimVersion":"%s","topologyStale":%s}\n' "$LAUNCHER_GATEWAY_VERSION" "$LAUNCHER_SHIM_VERSION" "${LAUNCHER_TOPOLOGY_STALE:-false}" ;;
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
if [ "${LAUNCHER_JAVA_BOOT_FAILS:-0}" = "1" ]; then
  # JW-01: a boot-dead daemon — the stack trace goes to stderr, which the launcher must be
  # redirecting into daemon-boot.log (pre-fix it went to /dev/null).
  echo "Exception in thread main: kaboom-at-boot" >&2
  exit 1
fi
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
  LAUNCHER_GATEWAY_VERSION="$GATEWAY_VERSION" \
  LAUNCHER_SHIM_VERSION="$SHIM_VERSION" \
  LAUNCHER_URL_CAPTURE="$SANDBOX/url" \
  LAUNCHER_BODY_CAPTURE="$SANDBOX/body" \
  LAUNCHER_SHUTDOWN_CAPTURE="$SANDBOX/shutdown" \
  LAUNCHER_INJECT_ENV_KEY="${LAUNCHER_INJECT_ENV_KEY:-0}" \
  LAUNCHER_JAVA_BOOT_FAILS="${LAUNCHER_JAVA_BOOT_FAILS:-0}" \
  LAUNCHER_TOPOLOGY_STALE="${LAUNCHER_TOPOLOGY_STALE:-false}" \
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

# JW-01: a boot-dead daemon must leave a tailable trace, and the launcher must SHOW it on the
# handshake failure instead of just "got <none>". The java stub writes its stack trace to
# stderr, which the launcher's redirect must capture in daemon-boot.log.
printf 'down\n' > "$SANDBOX/daemon-state"
rm -f "$SANDBOX/logs/daemon-boot.log"
set +e
BOOT_ERR="$(LAUNCHER_JAVA_BOOT_FAILS=1 run_launcher 2>&1)"
BOOT_RC=$?
set -e
test "$BOOT_RC" -ne 0
grep -q "daemon-boot.log" <<<"$BOOT_ERR" || { echo "JW-01: launcher must name the boot log, got: $BOOT_ERR" >&2; exit 1; }
grep -q "kaboom-at-boot" <<<"$BOOT_ERR" || { echo "JW-01: launcher must print the boot-log tail, got: $BOOT_ERR" >&2; exit 1; }
grep -q "kaboom-at-boot" "$SANDBOX/logs/daemon-boot.log"

# JW-04: a daemon reporting topologyStale=true must produce the non-fatal restart warning while
# the launch still proceeds (warning shape mirrors the shim-staleness one).
printf 'up\n' > "$SANDBOX/daemon-state"
STALE_ERR="$(LAUNCHER_TOPOLOGY_STALE=true run_launcher 2>&1 >/dev/null)"
grep -q "running topology is stale" <<<"$STALE_ERR" || { echo "JW-04: expected the stale-topology warning, got: $STALE_ERR" >&2; exit 1; }
grep -q "splice restart" <<<"$STALE_ERR" || { echo "JW-04: the warning must name the fix, got: $STALE_ERR" >&2; exit 1; }

echo "launcher test: OK"
