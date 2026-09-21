#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# The mock daemon's "current" version is DERIVED from the shim under test, never hardcoded — a
# snapshot literal here goes stale on the first version bump and flips every "up" daemon to
# "stale", sending the launcher down the replace path against the mock (found by the v0.2.0 bump:
# the hardcoded 0.1.1 made this test fail on exactly the commit that mattered). Same awk as
# checks/release/accept.sh — one idiom for reading the marker.
GATEWAY_VERSION="$(awk -F'"' '/^const SPLICE_GATEWAY_VERSION = "/ { print $2; exit }' "$ROOT/app/src/main/dist/bin/splice-launch")"
SHIM_VERSION="$(awk -F'"' '/^const SPLICE_SHIM_VERSION = "/ { print $2; exit }' "$ROOT/app/src/main/dist/bin/splice-launch")"
[ -n "$GATEWAY_VERSION" ] && [ -n "$SHIM_VERSION" ] || {
  echo "launcher test: could not read version markers from app/src/main/dist/bin/splice-launch" >&2
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
printf 'spawned\n' >> "${LAUNCHER_JAVA_CAPTURE:-/dev/null}"
SH
# V4-189: the supervisor unit, mocked. `cat <unit>` answers "the unit exists" only when
# LAUNCHER_UNIT_PRESENT=1; `start <unit>` records the unit name and, when LAUNCHER_UNIT_BOOTS=1,
# brings the mock daemon up the way the real unit would.
cat > "$SANDBOX/bin/systemctl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
[ "${1:-}" = "--user" ] || { printf 'unexpected systemctl args: %s\n' "$*" >&2; exit 2; }
case "${2:-}" in
  cat) [ "${LAUNCHER_UNIT_PRESENT:-0}" = "1" ] ;;
  start)
    printf '%s\n' "${3:-}" >> "$LAUNCHER_START_CAPTURE"
    [ "${LAUNCHER_UNIT_BOOTS:-0}" = "1" ] && printf 'new\n' > "$LAUNCHER_DAEMON_STATE"
    ;;
  *) printf 'unexpected systemctl verb: %s\n' "$*" >&2; exit 2 ;;
esac
SH
chmod +x "$SANDBOX/bin/curl" "$SANDBOX/bin/java" "$SANDBOX/bin/systemctl"

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
  LAUNCHER_JAVA_CAPTURE="$SANDBOX/java-spawns" \
  LAUNCHER_START_CAPTURE="$SANDBOX/unit-starts" \
  LAUNCHER_UNIT_PRESENT="${LAUNCHER_UNIT_PRESENT:-1}" \
  LAUNCHER_UNIT_BOOTS="${LAUNCHER_UNIT_BOOTS:-1}" \
    "$ROOT/app/src/main/dist/bin/splice-launch" "$@"
}

# V4-189: the operator's shape — NO selector overrides, so the shim resolves everything from $HOME
# and a cold start belongs to the supervisor unit. The sandbox home carries the same config, jar
# and mgmt-key at their default paths.
mkdir -p "$SANDBOX/home/.config/splice" "$SANDBOX/home/.local/share/splice" "$SANDBOX/home/.splice/state"
cp "$SANDBOX/splice.toml" "$SANDBOX/home/.config/splice/splice.toml"
touch "$SANDBOX/home/.local/share/splice/splice.jar"
printf 'test-key\n' > "$SANDBOX/home/.splice/state/mgmt-key"
run_launcher_default() {
  env -u SPLICE_CONFIG -u XDG_CONFIG_HOME -u SPLICE_JAR -u SPLICE_SHARE_DIR -u SPLICE_STATE_DIR \
      -u CLAUDEX_STATE_DIR -u SPLICE_CONTROL_PORT -u CONTROL_PROXY_PORT -u CONTROL_PORT \
  HOME="$SANDBOX/home" \
  PATH="$SANDBOX/bin:$PATH" \
  SPLICE_HEAD=test \
  LAUNCHER_DAEMON_STATE="$SANDBOX/daemon-state" \
  LAUNCHER_GATEWAY_VERSION="$GATEWAY_VERSION" \
  LAUNCHER_SHIM_VERSION="$SHIM_VERSION" \
  LAUNCHER_URL_CAPTURE="$SANDBOX/url" \
  LAUNCHER_BODY_CAPTURE="$SANDBOX/body" \
  LAUNCHER_SHUTDOWN_CAPTURE="$SANDBOX/shutdown" \
  LAUNCHER_PWNED_FILE="$SANDBOX/pwned" \
  LAUNCHER_JAVA_CAPTURE="$SANDBOX/java-spawns" \
  LAUNCHER_START_CAPTURE="$SANDBOX/unit-starts" \
  LAUNCHER_UNIT_PRESENT="${LAUNCHER_UNIT_PRESENT:-1}" \
  LAUNCHER_UNIT_BOOTS="${LAUNCHER_UNIT_BOOTS:-1}" \
  ${LAUNCHER_SELECTOR:+"$LAUNCHER_SELECTOR=$LAUNCHER_SELECTOR_VALUE"} \
    "$ROOT/app/src/main/dist/bin/splice-launch" "$@"
}
cold() { printf 'down\n' > "$SANDBOX/daemon-state"; rm -f "$SANDBOX/java-spawns" "$SANDBOX/unit-starts"; }

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

# V4-189 / UF-01: with no selector override and splice.service on the box, a cold start STARTS THE
# UNIT and waits for it; java is never spawned beside it (the raw nohup spawn is how three stray
# daemons squatted :3096 on 2026-09-21).
cold
run_launcher_default
test "$(cat "$SANDBOX/unit-starts")" = "splice.service"
test ! -e "$SANDBOX/java-spawns"
test "$(cat "$SANDBOX/url")" = "http://127.0.0.1:4567/launch/test"

# UF-02: the unit name is the operator's SPLICE_SUPERVISOR_UNIT, never a hardcoded splice.service.
cold
SPLICE_SUPERVISOR_UNIT=splice-canary.service run_launcher_default
test "$(cat "$SANDBOX/unit-starts")" = "splice-canary.service"
test ! -e "$SANDBOX/java-spawns"

# UF-03: any selector override means a harness's own daemon — the unit is never touched, the raw
# spawn runs. One arm per selector the guard names; the first (SPLICE_CONFIG) is what run_launcher
# itself does, the rest are the ones astra found missing from the guard.
for selector in SPLICE_CONFIG XDG_CONFIG_HOME SPLICE_JAR SPLICE_SHARE_DIR SPLICE_STATE_DIR \
    CLAUDEX_STATE_DIR SPLICE_CONTROL_PORT CONTROL_PROXY_PORT CONTROL_PORT; do
  cold
  case "$selector" in
    SPLICE_CONFIG) value="$SANDBOX/home/.config/splice/splice.toml" ;;
    XDG_CONFIG_HOME) value="$SANDBOX/home/.config" ;;
    SPLICE_JAR) value="$SANDBOX/home/.local/share/splice/splice.jar" ;;
    SPLICE_SHARE_DIR) value="$SANDBOX/home/.local/share/splice" ;;
    SPLICE_STATE_DIR|CLAUDEX_STATE_DIR) value="$SANDBOX/home/.splice/state" ;;
    *) value=4567 ;;
  esac
  LAUNCHER_SELECTOR="$selector" LAUNCHER_SELECTOR_VALUE="$value" run_launcher_default
  test ! -e "$SANDBOX/unit-starts" || { echo "UF-03: $selector set must never start the unit" >&2; exit 1; }
  test "$(cat "$SANDBOX/java-spawns")" = "spawned" || { echo "UF-03: $selector set must raw-spawn" >&2; exit 1; }
done

# UF-04: a unit that never answers is reported on a wall-clock deadline and the shim exits 1 —
# it never falls through to a raw spawn beside the unit it just started.
cold
set +e
DEAD_ERR="$(LAUNCHER_UNIT_BOOTS=0 SPLICE_UNIT_WAIT_SECONDS=1 run_launcher_default 2>&1)"
DEAD_RC=$?
set -e
test "$DEAD_RC" -eq 1
grep -q "splice.service did not answer /health within 1s" <<<"$DEAD_ERR" || { echo "UF-04: expected the deadline message, got: $DEAD_ERR" >&2; exit 1; }
test "$(cat "$SANDBOX/unit-starts")" = "splice.service"
test ! -e "$SANDBOX/java-spawns"

# UF-05: no unit on the box (systemctl cat fails) — the raw spawn is still the cold start.
cold
LAUNCHER_UNIT_PRESENT=0 run_launcher_default
test ! -e "$SANDBOX/unit-starts"
test "$(cat "$SANDBOX/java-spawns")" = "spawned"

echo "launcher test: OK"
