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

# DR-111: the loopback head key deliberately matches the real head in splice.example.toml, so an
# unredirected tier-1 pass would FABRICATE the real head's receipt in-repo. Snapshot now, assert
# untouched at the end; every harness invocation below redirects via E2E_RECEIPT_DIR.
REPO_RECEIPT="$ROOT/checks/e2e/receipts/claude-splice.json"
receipt_sha_before="$(sha256sum "$REPO_RECEIPT" 2>/dev/null || echo absent)"

python3 "$LOOPBACK" --record "$tmp/rec.jsonl" --ready-file "$tmp/ready" --head-key claude-splice \
  --duplicate-stop-file "$tmp/duplicate-stop" --unknown-kind-head unknown-kind \
  --count-tokens-drop-file "$tmp/ct-drop" \
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
    E2E_RECEIPT_DIR="$tmp/receipts" \
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

# ── duplicate terminal: a second message_stop is a protocol failure, never a clean stream. ──
touch "$tmp/duplicate-stop"
if run_arm duplicate SPLICE_E2E_CLIENT_TOKEN=caller-e2e-token; then
  err "duplicate message_stop arm must fail the wire probe"
  cat "$tmp/duplicate.err"
elif grep -q "message_stop count = 2" "$tmp/duplicate.err"; then
  ok "duplicate message_stop is rejected by the wire probe"
else
  err "duplicate message_stop arm failed without naming the duplicate terminal"
  cat "$tmp/duplicate.err"
fi
rm -f "$tmp/duplicate-stop"

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

# ── selector: a requested key absent from discovery must fail, not report 0/0/0 success. ──
: > "$tmp/rec.jsonl"
if env -u SPLICE_E2E_CLIENT_TOKEN \
  CLAUDEX_STATE_DIR="$STATE" SPLICE_CONTROL_PORT="$CONTROL" E2E_RECEIPT_DIR="$tmp/receipts" \
  bash "$HARNESS" --tier 1 --head absent-head >"$tmp/absent.out" 2>"$tmp/absent.err"; then
  err "absent --head selector must exit nonzero"
elif grep -q "requested head 'absent-head' was not returned" "$tmp/absent.err"; then
  ok "absent --head selector fails by name"
else
  err "absent --head selector failed without naming the missing key"
  cat "$tmp/absent.err"
fi
if [ "$(head_hits)" != 0 ]; then
  err "absent --head selector touched the discovered head ($(head_hits) hits)"
else
  ok "absent --head selector never touched a head"
fi

# ── unknown authKind: harness-FATAL by name, never a silent SKIP (DR-49a). ──
# Red on the unfixed harness: probe_bearer's `exit 1` died inside tier1's command-substitution
# SUBSHELL, the parent read rc=1 — the same code as the legit "no caller token" skip — and the
# run exited 0 with the FATAL text scrolling past as decoration.
: > "$tmp/rec.jsonl"
if env -u SPLICE_E2E_CLIENT_TOKEN \
  CLAUDEX_STATE_DIR="$STATE" SPLICE_CONTROL_PORT="$CONTROL" E2E_RECEIPT_DIR="$tmp/receipts" \
  bash "$HARNESS" --tier 1 --head unknown-kind >"$tmp/unknown.out" 2>"$tmp/unknown.err"; then
  err "unknown authKind must be FATAL (exit nonzero), not a SKIP"
  cat "$tmp/unknown.err"
elif grep -q "unrecognized authKind 'mystery-kind'" "$tmp/unknown.err"; then
  ok "unknown authKind is FATAL by name"
else
  err "unknown authKind exited nonzero without naming the kind"
  cat "$tmp/unknown.err"
fi
if [ "$(head_hits)" != 0 ]; then
  err "unknown-authKind arm probed the head ($(head_hits) hits) — the exact leak the FATAL exists to stop"
else
  ok "unknown-authKind arm never touched the head"
fi

# ── perf oracle: recovery pairing is model-scoped, not whole-file adjacency (DR-49b). ──
# Red on the unfixed harness: a healthy model's interleaved ok rows sat adjacent to every
# failure of a broken model, so a lane that NEVER recovered read "retried-then-ok" and the
# oracle exited 0 (proven: exit 0 + "retried-then-ok: http_500x2" on this exact fixture).
PERF="$STATE/claude-splice-perf.jsonl"
printf '%s\n' \
  '{"ts":1000,"model":"broken-model","outcome":"http_500"}' \
  '{"ts":2000,"model":"healthy-model","outcome":"ok"}' \
  '{"ts":3000,"model":"broken-model","outcome":"http_500"}' \
  '{"ts":4000,"model":"healthy-model","outcome":"ok"}' > "$PERF"
if env CLAUDEX_STATE_DIR="$STATE" SPLICE_CONTROL_PORT="$CONTROL" E2E_RECEIPT_DIR="$tmp/receipts" \
  E2E_PERF_SINCE=0 E2E_PERF_WANT=1 \
  bash "$HARNESS" --tier perf-oracle --head claude-splice >"$tmp/perf-lie.out" 2>"$tmp/perf-lie.err"; then
  err "interleaved cross-model oks must not pardon a persistently failing model"
  cat "$tmp/perf-lie.err"
elif grep -q "unrecovered non-ok rows" "$tmp/perf-lie.err"; then
  ok "cross-model interleave stays unrecovered (adjacency pardon closed)"
else
  err "perf oracle went red without naming the unrecovered rows"
  cat "$tmp/perf-lie.err"
fi

# Same-model retry-through is the DESIGNED pardon and must survive the scoping.
printf '%s\n' \
  '{"ts":1000,"model":"m","outcome":"http_500"}' \
  '{"ts":2000,"model":"m","outcome":"ok"}' \
  '{"ts":3000,"model":"m","outcome":"ok"}' > "$PERF"
if env CLAUDEX_STATE_DIR="$STATE" SPLICE_CONTROL_PORT="$CONTROL" E2E_RECEIPT_DIR="$tmp/receipts" \
  E2E_PERF_SINCE=0 E2E_PERF_WANT=2 \
  bash "$HARNESS" --tier perf-oracle --head claude-splice >"$tmp/perf-ok.out" 2>"$tmp/perf-ok.err"; then
  if grep -q "retried-then-ok: http_500x1" "$tmp/perf-ok.err"; then
    ok "same-model retry-through is still pardoned and reported"
  else
    err "same-model retry-through passed but lost its informational tally"
    cat "$tmp/perf-ok.err"
  fi
else
  err "same-model retry-through must stay green (the pardon design is deliberate)"
  cat "$tmp/perf-ok.err"
fi
rm -f "$PERF"

# ── DR-113: a count_tokens transport failure is a per-head FAIL, not a harness abort. ──
# Red on the unfixed harness: the bare `ct=$(curl ...)` assignment errexited the whole run with
# curl's exit code — no ✗ row, no summary, later heads unprobed. The loopback drops the
# connection with no HTTP response while the marker file exists.
touch "$tmp/ct-drop"
if run_arm ctdrop SPLICE_E2E_CLIENT_TOKEN=caller-e2e-token; then
  err "count_tokens transport-failure arm must exit nonzero (a per-head FAIL is recorded)"
  cat "$tmp/ctdrop.err"
elif grep -q "✓ claude-splice/wire" "$tmp/ctdrop.err" &&
     grep -q "✗ claude-splice/count_tokens" "$tmp/ctdrop.err"; then
  ok "count_tokens transport failure records a per-head FAIL and the run continues"
else
  err "count_tokens transport failure did not surface as a recorded FAIL (harness died mid-run)"
  cat "$tmp/ctdrop.err"
fi
rm -f "$tmp/ct-drop"

# ── DR-110: tier-2's not-logged-in SKIP survives set -e. ──
# Red on the unfixed harness: `wait_pane ...; rc=$?` errexited at the bare call, so the
# README-promised SKIP never ran — the first not-logged-in head killed the run mid-roster with
# no summary and a leaked tmux session. The stub wrapper prints the auth-needed pane text
# (wait_pane's rc=2 pattern) instantly; PATH injection resolves the advertised label to it.
if command -v tmux >/dev/null 2>&1; then
  printf '#!/bin/sh\necho "not logged in"\nsleep 60\n' > "$tmp/claude-splice"
  chmod +x "$tmp/claude-splice"
  if env -u SPLICE_E2E_CLIENT_TOKEN PATH="$tmp:$PATH" \
      CLAUDEX_STATE_DIR="$STATE" SPLICE_CONTROL_PORT="$CONTROL" E2E_RECEIPT_DIR="$tmp/receipts" \
      bash "$HARNESS" --tier 2 --head claude-splice >"$tmp/tui-skip.out" 2>"$tmp/tui-skip.err"; then
    if grep -q "head not logged in" "$tmp/tui-skip.err"; then
      ok "tier-2 not-logged-in records a SKIP and the run completes"
    else
      err "tier-2 arm exited 0 without the not-logged-in SKIP line"
      cat "$tmp/tui-skip.err"
    fi
  else
    err "tier-2 not-logged-in must SKIP (exit 0), not abort the harness (got rc=$?)"
    cat "$tmp/tui-skip.err"
  fi
  tmux -L splice-e2e kill-server 2>/dev/null || true
else
  ok "tier-2 SKIP arm not run (no tmux on this box) — the DR-110 line is still gate-covered on boxes with tmux"
fi

# ── DR-111: the selftest must never fabricate the REAL head's e2e receipt. ──
# Red on the unfixed harness: emit_receipt wrote loopback data (model claude-splice--claude-
# haiku-4-5, a head no real run ever touched) into checks/e2e/receipts/claude-splice.json on
# every gate run — when the 924 receipt binding activates it would grade against fabrications.
receipt_sha_after="$(sha256sum "$REPO_RECEIPT" 2>/dev/null || echo absent)"
if [ "$receipt_sha_before" = "$receipt_sha_after" ]; then
  ok "repo receipts untouched by the selftest"
else
  err "selftest fabricated/overwrote $REPO_RECEIPT — receipt binding would grade loopback bytes"
fi
if [ -f "$tmp/receipts/claude-splice.json" ]; then
  ok "tier-1 receipt emission intact (redirected to scratch)"
else
  err "no receipt landed in scratch — emission broke or E2E_RECEIPT_DIR was ignored"
fi

if [ "$fail" -eq 0 ]; then
  echo "heads-e2e-selftest OK — skip stays off the head, fake token probes with the caller bearer, mgmt-key token is FATAL, unknown authKind is FATAL, perf recovery is model-scoped, transport failures and not-logged-in are per-head verdicts, receipts stay real"
  exit 0
fi
echo "heads-e2e-selftest FAIL"
exit 1
