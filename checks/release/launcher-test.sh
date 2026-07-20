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
    printf '{"env":{},"unset":[],"argv":["true"]}\n'
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

printf 'old\n' > "$SANDBOX/daemon-state"
rm -f "$SANDBOX/shutdown"
run_launcher
test "$(cat "$SANDBOX/shutdown")" = "http://127.0.0.1:4567/api/daemon/shutdown"
test "$(cat "$SANDBOX/daemon-state")" = "new"

echo "launcher test: OK"
