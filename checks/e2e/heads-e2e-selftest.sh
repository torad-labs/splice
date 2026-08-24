#!/usr/bin/env bash
# checks/e2e/heads-e2e-selftest.sh — red-green proof for heads-e2e.sh, run by the gate.
#
# The live harness is billed and skipped in CI. This canary drives it against a loopback
# control+head so the skip / fake-token / FATAL-mgmt-key arms cannot rot: a skip or FATAL
# that still probes the head (and would have shipped the mgmt key to a vendor) fails HERE.
# Loopback answers /health first so heads-e2e.sh will not cold-start the installed splice.jar.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HARNESS="$ROOT/checks/e2e/heads-e2e.sh"
LOOPBACK="$ROOT/checks/e2e/loopback_control.py"

tmp="$(mktemp -d)"
trap 'kill "$LOOP_PID" 2>/dev/null || true; rm -rf "$tmp"' EXIT

fail=0
err() { echo "  ✗ heads-e2e-selftest: $1"; fail=1; }
ok()  { echo "  ✓ heads-e2e-selftest: $1"; }

STATE="$tmp/state"
mkdir -p "$STATE"
MGMT="mgmt-key-for-selftest-32bytes!!"
printf '%s' "$MGMT" > "$STATE/mgmt-key"

python3 "$LOOPBACK" --record "$tmp/rec.jsonl" --ready-file "$tmp/ready" --head-key claude-splice \
  >"$tmp/loop.out" 2>"$tmp/loop.err" &
LOOP_PID=$!
for _ in $(seq 1 50); do
  [ -f "$tmp/ready" ] && break
  sleep 0.05
done
if [ ! -f "$tmp/ready" ]; then
  echo "  ✗ heads-e2e-selftest: loopback never wrote READY"
  echo "    stdout: $(cat "$tmp/loop.out" 2>/dev/null)"
  echo "    stderr: $(cat "$tmp/loop.err" 2>/dev/null)"
  exit 1
fi
# shellcheck disable=SC1091
. "$tmp/ready"

if ! curl -sS -m 2 "http://127.0.0.1:${CONTROL}/health" >/dev/null; then
  echo "  ✗ heads-e2e-selftest: loopback /health not answering on :$CONTROL"
  exit 1
fi

run_arm() { # name extra-env...
  local name="$1"; shift
  : > "$tmp/rec.jsonl"
  env -u SPLICE_E2E_CLIENT_TOKEN \
    CLAUDEX_STATE_DIR="$STATE" \
    SPLICE_CONTROL_PORT="$CONTROL" \
    "$@" \
    bash "$HARNESS" --tier 1 --head claude-splice \
    >"$tmp/$name.out" 2>"$tmp/$name.err"
}

head_hits() {
  python3 - "$tmp/rec.jsonl" <<'PY'
import json, sys
n = 0
for line in open(sys.argv[1]):
    row = json.loads(line)
    if row.get("plane") == "head":
        n += 1
print(n)
PY
}

# ── skip: no caller token. Discover is allowed; the head must not be touched. ──
if run_arm skip; then
  if grep -q "client-auth head, no caller credential" "$tmp/skip.err"; then
    ok "skip arm reports SKIP"
  else
    err "skip arm exited 0 but did not print the client-auth SKIP reason"
    cat "$tmp/skip.err"
  fi
else
  err "skip arm must exit 0 (SKIP is not FAIL); got $?"
  cat "$tmp/skip.err"
fi
if [ "$(head_hits)" != 0 ]; then
  err "skip arm probed the head ($(head_hits) hits) — that is the mgmt-key leak"
  cat "$tmp/rec.jsonl"
else
  ok "skip arm never touched the head"
fi

# ── token: fake caller credential, not the mgmt key. Wire + count_tokens must pass. ──
if run_arm token SPLICE_E2E_CLIENT_TOKEN=caller-e2e-token; then
  if grep -q "✓ claude-splice/wire" "$tmp/token.err" &&
     grep -q "✓ claude-splice/count_tokens" "$tmp/token.err"; then
    ok "token arm passes wire + count_tokens"
  else
    err "token arm exited 0 but did not pass both checks"
    cat "$tmp/token.err"
  fi
else
  err "token arm must pass against the loopback"
  cat "$tmp/token.err"
fi
if python3 - "$tmp/rec.jsonl" "$MGMT" <<'PY'
import json, sys
path, mgmt = sys.argv[1], sys.argv[2]
rows = [json.loads(l) for l in open(path)]
head = [r for r in rows if r.get("plane") == "head"]
if not head:
    sys.exit("token arm never hit the head")
bad = [r for r in head if r.get("authorization") != "Bearer caller-e2e-token"]
if bad:
    sys.exit("head saw unexpected Authorization: %s" % bad)
if any(mgmt in (r.get("authorization") or "") for r in head):
    sys.exit("mgmt key rode the head Authorization")
PY
then
  ok "token arm forwarded the caller bearer, never the mgmt key"
else
  err "token arm Authorization recorder: $?"
  cat "$tmp/rec.jsonl"
fi

# ── FATAL: token == mgmt key. Must refuse before discover, never touch the head. ──
if run_arm fatal SPLICE_E2E_CLIENT_TOKEN="$MGMT"; then
  err "FATAL arm must exit 1 when the token is the mgmt key"
  cat "$tmp/fatal.err"
else
  if grep -q "VERBATIM" "$tmp/fatal.err"; then
    ok "FATAL arm refuses a mgmt-key token"
  else
    err "FATAL arm exited nonzero but did not name the leak"
    cat "$tmp/fatal.err"
  fi
fi
if [ "$(head_hits)" != 0 ]; then
  err "FATAL arm probed the head ($(head_hits) hits) after refusing the token"
  cat "$tmp/rec.jsonl"
else
  ok "FATAL arm never touched the head"
fi

if [ "$fail" -eq 0 ]; then
  echo "heads-e2e-selftest OK — skip stays off the head, fake token probes with the caller bearer, mgmt-key token is FATAL"
  exit 0
fi
echo "heads-e2e-selftest FAIL"
exit 1
