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
LOOPBACK="$ROOT/checks/e2e/loopback_control.ts"

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

bun "$LOOPBACK" --record "$tmp/rec.jsonl" --ready-file "$tmp/ready" --head-key claude-splice \
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

# ── V4-33: tier 2 answers the folder-trust dialog with YES, and the tool-surface gate is LIVE. ──
# Red on the unfixed harness: the trust dialog's default selection is "No, exit" (Claude Code
# 2.1.257), tier 2 sent a bare Enter, and the wrapper exited before a single turn — the drive then
# failed 90 seconds later as "TUI never became ready", which reads like a head defect. Every tier-2
# scratch is a fresh mktemp dir, so this dialog is drawn on EVERY head of EVERY run.
#
# The stub is a real terminal program, not a printf: it draws the dialog with the cursor on "No",
# moves it only on a Down key, and exits nonzero on an Enter pressed while "No" is selected —
# so a harness that answers the dialog wrongly cannot reach readiness here either. It then answers
# the two drive prompts so the arm costs seconds rather than two 150s timeouts.
if command -v tmux >/dev/null 2>&1; then
  cat > "$tmp/trust-stub.py" <<'PY'
import os, sys, termios, tty

MARKER = os.environ["TRUST_MARKER"]

# The ALTERNATE SCREEN is not decoration: without it tmux pushes every cleared frame into
# scrollback, capture-pane -S -160 keeps showing the dialog after it is answered, and the
# harness's dialog-first branch would match forever. Real Claude Code uses it, so the stub must.
sys.stdout.write("\x1b[?1049h")


def draw(sel):
    sys.stdout.write("\x1b[2J\x1b[H")
    sys.stdout.write("Quick safety check: do you trust this folder\r\n")
    sys.stdout.write(("❯ " if sel == 0 else "  ") + "No, exit\r\n")
    sys.stdout.write(("❯ " if sel == 1 else "  ") + "Yes, I trust this folder\r\n")
    sys.stdout.flush()

fd = sys.stdin.fileno()
saved = termios.tcgetattr(fd)
tty.setcbreak(fd)
sel, trusted, line, pending = 0, False, "", ""
draw(sel)
try:
    while True:
        ch = sys.stdin.read(1)
        if not ch:
            break
        if pending or ch == "\x1b":
            pending += ch
            if len(pending) >= 3:
                if pending.endswith("B"):
                    sel = 1
                elif pending.endswith("A"):
                    sel = 0
                pending = ""
                if not trusted:
                    draw(sel)
            continue
        if ch in ("\r", "\n"):
            if not trusted:
                if sel != 1:
                    sys.stdout.write("\r\nTRUST_DENIED — the harness answered No\r\n")
                    sys.stdout.flush()
                    sys.exit(1)
                trusted = True
                open(MARKER, "w").write("yes\n")
                sys.stdout.write("\x1b[2J\x1b[H ready\r\n")
                sys.stdout.write(" ⏵⏵ bypass permissions on (shift+tab to cycle)\r\n")
                sys.stdout.flush()
            else:
                if "six times seven" in line:
                    sys.stdout.write("ANSWER=42\r\n")
                elif "SECOND" in line:
                    sys.stdout.write("SECOND=DONE\r\n")
                sys.stdout.flush()
            line = ""
            continue
        line += ch
finally:
    termios.tcsetattr(fd, termios.TCSADRAIN, saved)
PY
  TRUST_MARKER="$tmp/trust-accepted"
  printf '#!/bin/sh\nexec python3 %s\n' "$tmp/trust-stub.py" > "$tmp/claude-splice"
  chmod +x "$tmp/claude-splice"
  env -u SPLICE_E2E_CLIENT_TOKEN PATH="$tmp:$PATH" TRUST_MARKER="$TRUST_MARKER" \
    CLAUDEX_STATE_DIR="$STATE" SPLICE_CONTROL_PORT="$CONTROL" E2E_RECEIPT_DIR="$tmp/receipts" \
    bash "$HARNESS" --tier 2 --head claude-splice >"$tmp/trust.out" 2>"$tmp/trust.err"
  if [ -f "$TRUST_MARKER" ]; then
    ok "tier-2 answers the folder-trust dialog with YES (a bare Enter would have exited)"
  else
    err "tier-2 never trusted the scratch folder — the wrapper was answered No or not at all"
    cat "$tmp/trust.err"
  fi
  if grep -q "✓ claude-splice/tui-turn1" "$tmp/trust.err" &&
     grep -q "✓ claude-splice/tui-turn2" "$tmp/trust.err"; then
    ok "tier-2 drives both turns once the folder is trusted"
  else
    err "tier-2 reached no turn after the trust dialog"
    cat "$tmp/trust.err"
  fi
  # The gate is LIVE inside tier 2, not just reachable from the mcp-oracle hook: a stub wrapper is
  # not Claude Code, so it spawns no MCP server and the tool surface must be reported missing.
  if grep -q "✗ claude-splice/mcp-tool-surface" "$tmp/trust.err"; then
    ok "tier-2's tool-surface gate reds when the driven wrapper registered no MCP tools"
  else
    err "tier-2 did not run the tool-surface gate at all (the arm is inert again)"
    cat "$tmp/trust.err"
  fi
  tmux -L splice-e2e kill-server 2>/dev/null || true
else
  ok "tier-2 trust-dialog arm not run (no tmux on this box)"
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

# ── V4-33: the operator-shaped tool surface is REAL, not a constant in a comment. ──
# Red on the unfixed harness in three different places, because the first version of this arm was
# none of these walls: it grepped heads-e2e.sh for a >64-char constant while the planted "MCP
# server" was `python3 -c "raise SystemExit(0)"` — it exits before the first byte of the stdio
# handshake — and nothing wrote enabledMcpjsonServers, so Claude Code registered ZERO tools, the
# 68-char name never reached the wire, and the arm could not have caught the muse 400 it exists
# for. The three walls below are: the enable plumbing is present; the server script is DRIVEN for
# real; and the harness gate that reads the handshake receipt is red on a surface that never
# formed and green on the receipt this selftest's own real server run just wrote.

# The constants come FROM heads-e2e.sh, never retyped here: a selftest that spells the names
# itself stops testing the harness and starts testing its own copy of it.
if consts="$(python3 - "$HARNESS" <<'PY' 2>&1
import pathlib, re, shlex, sys
text = pathlib.Path(sys.argv[1]).read_text()
out = []
for var in ("OVERLONG_TOOL_NAME", "OVERLONG_MCP_SERVER", "OVERLONG_MCP_TOOL", "OVERLONG_MCP_LOG_NAME"):
    m = re.search(r'^%s="([^"]+)"' % var, text, re.M)
    if not m:
        sys.exit("%s missing from heads-e2e.sh" % var)
    out.append("%s=%s" % (var, shlex.quote(m.group(1))))
print("\n".join(out))
PY
)"; then
  eval "$consts"
  ok "harness tool-surface constants readable"
else
  err "long-tool-name arm: $consts"
  OVERLONG_TOOL_NAME=""; OVERLONG_MCP_SERVER=""; OVERLONG_MCP_TOOL=""; OVERLONG_MCP_LOG_NAME=""
fi

# ── wall 1: the plumbing that makes a project MCP server load non-interactively. ──
if msg="$(python3 - "$HARNESS" "$ROOT/checks/e2e/mcp_overlong_tool_server.ts" <<'PY' 2>&1
import pathlib, re, sys
text = pathlib.Path(sys.argv[1]).read_text()
server_script = pathlib.Path(sys.argv[2])

def need(pat, label):
    m = re.search(pat, text, re.M)
    if not m:
        sys.exit(label + " missing from heads-e2e.sh")
    return m

name = need(r'^OVERLONG_TOOL_NAME="([^"]+)"', "OVERLONG_TOOL_NAME").group(1)
server = need(r'^OVERLONG_MCP_SERVER="([^"]+)"', "OVERLONG_MCP_SERVER").group(1)
tool = need(r'^OVERLONG_MCP_TOOL="([^"]+)"', "OVERLONG_MCP_TOOL").group(1)
if len(name) <= 64:
    sys.exit("OVERLONG_TOOL_NAME is %d chars, want >64 (operator 400 was 68)" % len(name))
composed = "mcp__%s__%s" % (server, tool)
if composed != name:
    sys.exit("OVERLONG_TOOL_NAME is not mcp__SERVER__TOOL composition")
if not server_script.is_file():
    sys.exit("the planted MCP server script is missing at %s" % server_script)
if server_script.name not in text:
    sys.exit("heads-e2e.sh no longer plants %s — an MCP server that is not real registers no "
             "tools, which is the exact HEAD defect this row redoes" % server_script.name)
# The exact HEAD shape, matched on the .mcp.json argv rather than on the prose that explains it:
# an inline `python3 -c ...` server exits before the first byte of the stdio handshake.
if '["-c"' in text:
    sys.exit("heads-e2e.sh still plants an inline `python3 -c` MCP server — it exits before the "
             "stdio handshake, so Claude Code registers zero tools")
# NOT checked here: that the plant WRITES the enable settings. Grepping this file for
# "enabledMcpjsonServers" passes on the comment that explains the key — a denominator taken from
# the same text it is checking. Wall 2 runs the plant and reads what it produced instead.
if "plant_overlong_mcp" not in text:
    sys.exit("plant_overlong_mcp missing from heads-e2e.sh")
if 'plant_overlong_mcp "$scratch"' not in text:
    sys.exit("tier2 no longer calls plant_overlong_mcp on the scratch dir")
if 'mcp_surface_gate "$key" "$scratch"' not in text:
    sys.exit("tier2 no longer asserts the tool surface actually formed")
if "a skip is not a pass" not in text:
    sys.exit("skip summary no longer says a skip is not a pass")
print("%s is %d chars and composes from the .mcp.json key plus a real server's tool"
      % (name, len(name)))
PY
)"; then
  ok "long-tool-name arm: ${msg}"
else
  err "long-tool-name arm: ${msg:-python failed}"
fi

# ── wall 2: run the PLANT, then drive what it planted — command, args and env, nothing retyped. ──
# This is the wall the HEAD version had no form of. `--tier plant-oracle` runs the harness's own
# plant_overlong_mcp into a scratch dir; everything below is read back out of the files it wrote,
# so a plant that stops enabling the server, stops pointing at the real server script, or stops
# handing it a receipt path goes red here rather than passing on the prose that describes it.
MCP_SCRATCH="$tmp/mcp-scratch"
mkdir -p "$MCP_SCRATCH"
if env CLAUDEX_STATE_DIR="$STATE" SPLICE_CONTROL_PORT="$CONTROL" E2E_RECEIPT_DIR="$tmp/receipts" \
  E2E_MCP_SCRATCH="$MCP_SCRATCH" \
  bash "$HARNESS" --tier plant-oracle --head claude-splice >"$tmp/plant.out" 2>"$tmp/plant.err"; then
  ok "plant-oracle wrote the tier-2 MCP scratch"
else
  err "plant-oracle failed to plant the MCP scratch"
  cat "$tmp/plant.err"
fi

if msg="$(python3 - "$MCP_SCRATCH" "$OVERLONG_MCP_SERVER" "$OVERLONG_MCP_TOOL" \
    "$OVERLONG_TOOL_NAME" "$ROOT/checks/e2e/mcp_overlong_tool_server.ts" <<'PY' 2>&1
import json, os, pathlib, subprocess, sys

scratch = pathlib.Path(sys.argv[1])
server, tool, composed, script = sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5]

cfg_path = scratch / ".mcp.json"
if not cfg_path.is_file():
    sys.exit("the plant wrote no .mcp.json")
servers = json.loads(cfg_path.read_text()).get("mcpServers") or {}
if list(servers) != [server]:
    sys.exit("planted .mcp.json declares %s, wanted exactly [%r] — the composed wire name comes "
             "from this key" % (list(servers), server))
entry = servers[server]

# The enable settings are the other half of the HEAD defect: a project .mcp.json that Claude Code
# has not been told to trust contributes no tools at all, and tier 2 has nobody to click approve.
enabled = False
for name in ("settings.local.json", "settings.json"):
    path = scratch / ".claude" / name
    if not path.is_file():
        continue
    data = json.loads(path.read_text())
    if server in (data.get("enabledMcpjsonServers") or []) or data.get("enableAllProjectMcpServers"):
        enabled = True
if not enabled:
    sys.exit("the plant never enabled %r in the scratch project settings — Claude Code leaves an "
             "unapproved project MCP server unloaded, so the session carries no over-long tool"
             % server)

if pathlib.Path(entry.get("args", [""])[0]).resolve() != pathlib.Path(script).resolve():
    sys.exit("planted server argv is %r, not the real MCP server script" % (entry.get("args"),))
log = (entry.get("env") or {}).get("SPLICE_E2E_MCP_LOG")
if not log:
    sys.exit("planted server gets no SPLICE_E2E_MCP_LOG — nothing would record the handshake")

# Spawn it exactly as Claude Code would: the planted command, the planted args, the planted env.
proc = subprocess.Popen([entry["command"]] + entry["args"], stdin=subprocess.PIPE,
                        stdout=subprocess.PIPE, env=dict(os.environ, **entry["env"]),
                        text=True, bufsize=1)

def send(obj):
    proc.stdin.write(json.dumps(obj) + "\n")
    proc.stdin.flush()

def expect(msg_id):
    line = proc.stdout.readline()
    if not line:
        sys.exit("server closed stdout before answering id=%s" % msg_id)
    row = json.loads(line)
    if row.get("id") != msg_id:
        sys.exit("answer id %r, wanted %r" % (row.get("id"), msg_id))
    if "error" in row:
        sys.exit("server errored on id=%s: %s" % (msg_id, row["error"]))
    return row["result"]

try:
    send({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
        "protocolVersion": "2025-11-25", "capabilities": {},
        "clientInfo": {"name": "heads-e2e-selftest", "version": "1"}}})
    init = expect(1)
    if init.get("protocolVersion") != "2025-11-25":
        sys.exit("initialize did not negotiate the requested protocolVersion: %r"
                 % init.get("protocolVersion"))
    if "tools" not in (init.get("capabilities") or {}):
        sys.exit("initialize did not advertise the tools capability: %r" % init.get("capabilities"))
    # the third leg of the handshake; answering a notification is a protocol violation, so nothing
    # is read back for it
    send({"jsonrpc": "2.0", "method": "notifications/initialized"})

    send({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
    names = [t.get("name") for t in (expect(2).get("tools") or [])]
    if names != [tool]:
        sys.exit("tools/list advertised %r, wanted exactly [%r] — the composed wire name would no "
                 "longer be %r" % (names, tool, composed))
    want = "mcp__%s__%s" % (server, names[0])
    if want != composed or len(want) <= 64:
        sys.exit("composed name is %r (%d chars) — the arm no longer exercises an over-64 name"
                 % (want, len(want)))

    send({"jsonrpc": "2.0", "id": 3, "method": "tools/call",
          "params": {"name": tool, "arguments": {}}})
    content = expect(3).get("content") or []
    if not content or content[0].get("type") != "text" or not content[0].get("text"):
        sys.exit("tools/call returned no text content: %r" % content)
finally:
    proc.stdin.close()
    proc.wait(timeout=10)

rows = [json.loads(l) for l in pathlib.Path(log).read_text().splitlines() if l.strip()]
methods = [r.get("method") for r in rows]
for needed in ("initialize", "notifications/initialized", "tools/list", "tools/call"):
    if needed not in methods:
        sys.exit("handshake receipt is missing %r (has %s)" % (needed, methods))
print("planted server enabled and driven: initialize+initialized+tools/list+tools/call served, "
      "%s composes to %d chars" % (tool, len(composed)))
PY
)"; then
  ok "the planted MCP config spawns a server that completes a real stdio handshake: ${msg}"
else
  err "the planted MCP config did not produce a working tool surface: ${msg:-python failed}"
fi

# ── wall 3: the harness gate is red when the surface never formed. ──
# Mutation-proof, both boring cases. (a) no receipt at all — the HEAD shape exactly: config
# planted, server never spawned. (b) a receipt with initialize and nothing else — the server
# started but its tools never entered the session, which passes every "is the file there" check.
mcp_oracle() { # scratch_dir out_name
  env CLAUDEX_STATE_DIR="$STATE" SPLICE_CONTROL_PORT="$CONTROL" E2E_RECEIPT_DIR="$tmp/receipts" \
    E2E_MCP_SCRATCH="$1" \
    bash "$HARNESS" --tier mcp-oracle --head claude-splice >"$tmp/$2.out" 2>"$tmp/$2.err"
}

mkdir -p "$tmp/mcp-never-spawned"
if mcp_oracle "$tmp/mcp-never-spawned" mcp-none; then
  err "tool-surface gate passed with NO handshake receipt — that is the unfixed harness"
  cat "$tmp/mcp-none.err"
elif grep -q "no MCP handshake receipt" "$tmp/mcp-none.err"; then
  ok "tool-surface gate reds when the planted server was never spawned"
else
  err "tool-surface gate went red without naming the missing receipt"
  cat "$tmp/mcp-none.err"
fi

mkdir -p "$tmp/mcp-no-tools"
printf '%s\n' '{"ts":1,"method":"initialize","protocol":"2026-09-01"}' \
  > "$tmp/mcp-no-tools/${OVERLONG_MCP_LOG_NAME:-mcp-handshake.jsonl}"
if mcp_oracle "$tmp/mcp-no-tools" mcp-partial; then
  err "tool-surface gate passed on a handshake that never served tools/list"
  cat "$tmp/mcp-partial.err"
elif grep -q "no tools/list row" "$tmp/mcp-partial.err"; then
  ok "tool-surface gate reds when the server initialized but listed no tools"
else
  err "tool-surface gate went red without naming the missing tools/list"
  cat "$tmp/mcp-partial.err"
fi

# ── wall 3, green half: the SAME gate passes on the receipt wall 2's real server just wrote. ──
# Green comes from a real server run, never a hand-written fixture, so the gate and the server
# cannot drift apart while agreeing with each other.
if mcp_oracle "$MCP_SCRATCH" mcp-green; then
  if grep -q "entered the session tool surface" "$tmp/mcp-green.err"; then
    ok "tool-surface gate passes on the real server's own handshake receipt"
  else
    err "tool-surface gate passed without naming the tool surface it verified"
    cat "$tmp/mcp-green.err"
  fi
else
  err "tool-surface gate reds on a REAL handshake receipt — the gate and the server disagree"
  cat "$tmp/mcp-green.err"
fi

if [ "$fail" -eq 0 ]; then
  echo "heads-e2e-selftest OK — skip stays off the head, fake token probes with the caller bearer, mgmt-key token is FATAL, unknown authKind is FATAL, perf recovery is model-scoped, transport failures and not-logged-in are per-head verdicts, receipts stay real, the over-long MCP tool surface is planted by a REAL stdio server that is enabled in the scratch settings, and the gate over its handshake receipt is red-green proven"
  exit 0
fi
echo "heads-e2e-selftest FAIL"
exit 1
