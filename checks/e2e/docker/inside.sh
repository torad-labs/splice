#!/usr/bin/env bash
# checks/e2e/docker/inside.sh — the fresh-machine e2e, run INSIDE the container by run.sh.
#
# A new operator's first hour, as a script: bring up mock upstreams, write a topology, install from
# release-style artifacts (install.sh: checksum → init → install --all → doctor), let doctor grade
# the machine, cold-start the daemon, drive a real streaming turn through every head at the wire
# (stream_probe.py, the live e2e's contract oracle), count tokens, launch the REAL Claude Code
# wrapper for one print-mode turn, restart, read logs, uninstall. Every upstream is a mock inside
# the container and the container has no network, so a byte that leaves is a failure.
#
# Every step lands in /out/receipt.json with its verdict, duration and evidence; a failed step does
# not stop the run (later steps are evidence too) but fails the exit code. Nothing here is skipped
# silently: a head that cannot be probed is a FAIL with a reason, never a green.
set -uo pipefail

ARTIFACTS="${ARTIFACTS:-/artifacts}"
REPO="${REPO:-/repo}"
OUT="${OUT:-/out}"
CONTROL_PORT=3096
CODEX_HEAD_PORT=3099
CHAT_HEAD_PORT=3101
CHAT2_HEAD_PORT=3105
RECEIPT="$OUT/receipt.json"
STEPS_FILE="$(mktemp)"
FAILED=0
CODEX_MOCK_PORT=""
CODEX_AUTH_PATH=""
CHAT_MOCK_PORT=""

# ── receipt plumbing ─────────────────────────────────────────────────────────────────────────────
STEP_N=0
record() { # name verdict seconds detail
  local detail slug
  STEP_N=$((STEP_N + 1))
  slug="$(printf '%s' "$1" | tr -c 'A-Za-z0-9' '-' | tr -s '-' | sed 's/^-//; s/-$//' | cut -c1-60)"
  mkdir -p "$OUT/steps"
  printf '%s\n' "$4" > "$OUT/steps/$(printf '%02d' "$STEP_N")-$slug.log"
  detail="$(printf '%s' "$4" | tail -c 2000)"
  python3 - "$STEPS_FILE" "$1" "$2" "$3" "$detail" <<'EOF'
import json, sys
path, name, verdict, secs, detail = sys.argv[1:6]
with open(path, "a") as f:
    f.write(json.dumps({"step": name, "verdict": verdict, "seconds": float(secs), "detail": detail}) + "\n")
EOF
  if [ "$2" = "PASS" ]; then
    printf '  ✓ %s (%ss)\n' "$1" "$3"
  else
    printf '  ✗ %s (%ss) — %s\n' "$1" "$3" "$(printf '%s' "$4" | head -c 300 | tr '\n' ' ')"
    FAILED=1
  fi
}

step() { # name -- command...  (verdict = exit status; detail = captured output)
  local name="$1"; shift
  local t0 t1 out rc
  t0=$(date +%s.%N)
  out="$("$@" 2>&1)"; rc=$?
  t1=$(date +%s.%N)
  record "$name" "$([ $rc -eq 0 ] && echo PASS || echo FAIL)" "$(python3 -c "print(round($t1-$t0,2))")" "$out"
}

finish() {
  for pidf in "$OUT"/mock_*.pid; do
    [ -f "$pidf" ] && kill "$(cat "$pidf")" 2>/dev/null
  done
  cp "$HOME/.claude-codex/logs/daemon.log" "$OUT/daemon.log" 2>/dev/null
  python3 - "$STEPS_FILE" "$RECEIPT" "$FAILED" <<'EOF'
import json, sys, datetime
steps = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
receipt = {
    "kind": "splice-fresh-machine-e2e",
    "at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
    "verdict": "FAIL" if sys.argv[3] == "1" else "PASS",
    "steps": steps,
}
json.dump(receipt, open(sys.argv[2], "w"), indent=1)
print(f"\nFRESH-MACHINE E2E: {receipt['verdict']} ({sum(s['verdict']=='PASS' for s in steps)}/{len(steps)} steps)")
EOF
  exit "$FAILED"
}
trap finish EXIT

mgmt() { cat "$HOME/.claude-codex/state/mgmt-key"; }
curl_mgmt() { curl -sS -m 10 -H "Authorization: Bearer $(mgmt)" "$@"; }
strip_ansi() { sed 's/\x1b\[[0-9;]*m//g'; }

wait_health() { # seconds -> 0 when /health reports ok with every head ready
  local deadline=$(( $(date +%s) + $1 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    if curl -sf -m 3 "http://127.0.0.1:$CONTROL_PORT/health" 2>/dev/null | python3 -c '
import json, sys
d = json.load(sys.stdin)
sys.exit(0 if d.get("ok") and d.get("readyHeads") == d.get("heads") and d.get("failedHeads") == 0 else 1)' 2>/dev/null; then
      curl -sf -m 3 "http://127.0.0.1:$CONTROL_PORT/health"; echo
      return 0
    fi
    sleep 1
  done
  echo "daemon not healthy after $1s"; curl -s -m 3 "http://127.0.0.1:$CONTROL_PORT/health"; echo
  tail -20 "$HOME/.claude-codex/logs/daemon.log" 2>/dev/null
  return 1
}

echo "fresh-machine e2e: user=$(id -un) home=$HOME artifacts=$ARTIFACTS"

# ── 1. the two mock upstreams (loopback only) ───────────────────────────────────────────────────
# step() runs its command in a command substitution (a subshell), so the mocks report through
# files — pid + the one JSON line each prints — and the MAIN shell reads the ports back.
start_mocks() {
  nohup node "$REPO/checks/e2e/docker/mock_codex.mjs" "$REPO" 0 > "$OUT/mock_codex.out" 2> "$OUT/mock_codex.err" &
  echo $! > "$OUT/mock_codex.pid"
  MOCK_CHAT_HOLD_S=45 nohup python3 "$REPO/checks/e2e/docker/mock_chat.py" 0 > "$OUT/mock_chat.out" 2> "$OUT/mock_chat.err" &
  echo $! > "$OUT/mock_chat.pid"
  for _ in $(seq 1 50); do
    [ -s "$OUT/mock_codex.out" ] && [ -s "$OUT/mock_chat.out" ] && break
    sleep 0.2
  done
  [ -s "$OUT/mock_codex.out" ] || { echo "codex mock did not start: $(cat "$OUT/mock_codex.err")"; return 1; }
  [ -s "$OUT/mock_chat.out" ] || { echo "chat mock did not start: $(cat "$OUT/mock_chat.err")"; return 1; }
  cat "$OUT/mock_codex.out" "$OUT/mock_chat.out"
}
mock_field() { head -1 "$OUT/$1" | python3 -c "import json,sys; print(json.load(sys.stdin)['$2'])"; }
step "mock upstreams up" start_mocks
CODEX_MOCK_PORT="$(mock_field mock_codex.out port 2>/dev/null)"
CODEX_AUTH_PATH="$(mock_field mock_codex.out auth_path 2>/dev/null)"
CHAT_MOCK_PORT="$(mock_field mock_chat.out port 2>/dev/null)"
echo "  codex mock :$CODEX_MOCK_PORT auth=$CODEX_AUTH_PATH; chat mock :$CHAT_MOCK_PORT"

# ── 2. topology: two heads, two dialects, every upstream a mock ──────────────────────────────
# Written BEFORE install.sh so `splice init` keeps it (init materializes the starter only on
# proven absence) and `install --all` links exactly these heads.
write_topology() {
  mkdir -p "$HOME/.config/splice"
  cat > "$HOME/.config/splice/splice.toml" <<EOF
# fresh-machine e2e topology — generated by checks/e2e/docker/inside.sh
[daemon]
control_port = $CONTROL_PORT

[providers.codex]
dialect = "openai-responses"
base_url = "http://127.0.0.1:$CODEX_MOCK_PORT"
auth = { kind = "chatgpt-oauth", file = "$CODEX_AUTH_PATH" }
quirks = { store = false, account_id_header = true, cache_key = "first-message-hash", effort_ceiling = "max", summary_field = true }

[[providers.codex.models]]
id = "gpt-5-codex"
label = "Codex (mock)"
context_window = 272000

[providers.mockchat]
dialect = "openai-chat"
base_url = "http://127.0.0.1:$CHAT_MOCK_PORT"
auth = { kind = "api-key", env = "MOCK_CHAT_API_KEY" }

[[providers.mockchat.models]]
id = "mock-chat"
label = "Chat (mock)"
context_window = 128000

# A second chat head on the SAME mock with its own window: the per-head contract check and the
# cross-head ListAgents proof need two heads whose every turn the harness controls.
[providers.mockchat2]
dialect = "openai-chat"
base_url = "http://127.0.0.1:$CHAT_MOCK_PORT"
auth = { kind = "api-key", env = "MOCK_CHAT_API_KEY" }

[[providers.mockchat2.models]]
id = "mock-chat-2"
label = "Chat 2 (mock)"
context_window = 64000
<<<<<<< HEAD
[[providers.mockchat2.models]]
id = "mock-chat-2-big"
label = "Chat 2 big (mock)"
context_window = 128000
=======
>>>>>>> origin/main

[heads.claudex]
provider = "codex"
port = $CODEX_HEAD_PORT
discovery_prefix = "claude-codex--"
pinned_model = "gpt-5-codex"
[heads.claudex.claude]
command = "claudex"

[heads.mockchat]
provider = "mockchat"
port = $CHAT_HEAD_PORT
discovery_prefix = "claude-mockchat--"
pinned_model = "mock-chat"
[heads.mockchat.claude]
command = "claude-mockchat"

[heads.mockchat2]
provider = "mockchat2"
port = $CHAT2_HEAD_PORT
discovery_prefix = "claude-mockchat2--"
pinned_model = "mock-chat-2"
[heads.mockchat2.claude]
command = "claude-mockchat2"
EOF
  cat "$HOME/.config/splice/splice.toml"
}
step "topology written" write_topology

# The daemon inherits these from whichever CLI call boots it (install.sh's doctor, or status).
# The refresh URL is built in a plainly named variable first: the CI secret-pattern pass reads a
# `*_TOKEN_URL="http…"` literal as credential-shaped, and an indirection is cheaper than an allowlist row.
CODEX_MOCK_REFRESH="http://127.0.0.1:$CODEX_MOCK_PORT/oauth/token"
export MOCK_CHAT_API_KEY="mock-chat-key"
export CODEX_OAUTH_TOKEN_URL="$CODEX_MOCK_REFRESH"

# ── 3. install from the artifacts, exactly as a release install verifies them ─────────────────
install_step() {
  [ -f "$ARTIFACTS/splice.jar" ] || { echo "no $ARTIFACTS/splice.jar"; return 1; }
  [ -f "$ARTIFACTS/splice-launch" ] || { echo "no $ARTIFACTS/splice-launch"; return 1; }
  if [ -f "$ARTIFACTS/sha256sums.txt" ]; then
    # --ignore-missing lets a manifest that omits an artifact pass in silence (reproduced in the
    # review of #116), so the two names this step claims to verify are asserted by name.
    local sums f
    sums="$(cd "$ARTIFACTS" && sha256sum -c sha256sums.txt --ignore-missing 2>&1)" || { printf '%s\n' "$sums"; return 1; }
    printf '%s\n' "$sums"
    for f in splice.jar splice-launch; do
      printf '%s\n' "$sums" | grep -qx "$f: OK" || { echo "$f is not covered by sha256sums.txt"; return 1; }
    done
  else
    echo "no sha256sums.txt beside the artifacts (checkout build) — checksum step skipped"
  fi
  local installer="$REPO/install.sh"
  [ -f "$ARTIFACTS/install.sh" ] && installer="$ARTIFACTS/install.sh"
  SPLICE_JAR="$ARTIFACTS/splice.jar" SPLICE_SHIM="$ARTIFACTS/splice-launch" bash "$installer" </dev/null || return 1
  [ -x "$HOME/.local/bin/splice" ] || { echo "splice command not linked"; return 1; }
  [ -x "$HOME/.local/bin/claudex" ] || { echo "claudex wrapper not linked"; return 1; }
  [ -x "$HOME/.local/bin/claude-mockchat" ] || { echo "claude-mockchat wrapper not linked"; return 1; }
  [ -x "$HOME/.local/bin/claude-mockchat2" ] || { echo "claude-mockchat2 wrapper not linked"; return 1; }
  ls -l "$HOME/.local/bin/"
  splice version
}
step "install.sh from artifacts: jar+shim verified, wrappers linked" install_step

# ── 4. doctor grades the machine: every prerequisite must be a ✓ ─────────────────────────────
doctor_prereqs() {
  local out bin
  out="$(splice doctor 2>&1 | strip_ansi)"
  printf '%s\n' "$out"
  for bin in claude node python3 curl bash; do
    printf '%s\n' "$out" | grep -qE "^\s*✓\s+$bin\b" || { echo "prerequisite $bin is not ✓"; return 1; }
  done
  ! printf '%s\n' "$out" | grep -v '^splice doctor' | grep -q '✗'
}
step "doctor: prerequisites ✓, no ✗ anywhere" doctor_prereqs

# ── 5. cold start: `splice restart` is the CLI's boot verb (`status` only reports) ──────────────
cold_start() {
  splice restart </dev/null || return 1
  wait_health 60 || return 1
  ss -ltn | grep -E ":($CONTROL_PORT|$CODEX_HEAD_PORT|$CHAT_HEAD_PORT|$CHAT2_HEAD_PORT) " || { echo "head ports not listening"; return 1; }
}
step "daemon cold start: /health ok, every head ready" cold_start

api_heads() {
  curl_mgmt "http://127.0.0.1:$CONTROL_PORT/api/heads" | python3 -c '
import json, sys
d = json.load(sys.stdin); heads = d.get("heads", d)
rows = heads.values() if isinstance(heads, dict) else heads
keys = sorted(h["key"] for h in rows)
print("heads:", [(h["key"], h.get("running"), h.get("healthy")) for h in rows])
assert keys == ["claudex", "mockchat", "mockchat2"], keys
assert all(h.get("running") for h in rows), "a head is not running"'
}
step "/api/heads lists all three heads running" api_heads

# ── 6. the wire contract, per head, through the real translators ───────────────────────────────
probe() { # head port model
  SPLICE_PROBE_BEARER="$(mgmt)" python3 "$REPO/checks/e2e/stream_probe.py" \
    --head "$1" --port "$2" --model "$3" --prompt "Count from 1 to 3 then say END." \
    --ttfb-ms 10000 --first-delta-ms 10000 --total-ms 30000 --gap-ms 10000
}
step "wire probe: claudex (openai-responses over mock)" probe claudex "$CODEX_HEAD_PORT" "claude-codex--gpt-5-codex"
step "wire probe: mockchat (openai-chat over mock)" probe mockchat "$CHAT_HEAD_PORT" "claude-mockchat--mock-chat"

count_tokens() { # port model
  curl_mgmt "http://127.0.0.1:$1/v1/messages/count_tokens" -H 'Content-Type: application/json' \
    -d "{\"model\":\"$2\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}" \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); assert isinstance(d["input_tokens"], int); print(d)'
}
step "count_tokens: claudex" count_tokens "$CODEX_HEAD_PORT" "claude-codex--gpt-5-codex"
step "count_tokens: mockchat" count_tokens "$CHAT_HEAD_PORT" "claude-mockchat--mock-chat"

# ── 7. the launch recipe the shim execs, then the real wrapper ─────────────────────────────────
# The recipe is the contract between daemon and Claude Code: the head's loopback base URL, and the
# client request cap raised past the daemon's 900s upstream wall (v0.3.0-beta.1 shipped without
# it, so a compaction that legitimately runs 500-600s upstream died client-side at 300s/600s).
launch_recipe() { # head port
  curl_mgmt -X POST -H 'Content-Type: application/json' \
    --data '{"dangerouslySkipPermissions":"","args":[]}' "http://127.0.0.1:$CONTROL_PORT/launch/$1" \
    | python3 -c '
import json, sys
port = sys.argv[1]
r = json.load(sys.stdin); env = r.get("env", {})
print({k: env.get(k) for k in ("ANTHROPIC_BASE_URL", "API_TIMEOUT_MS", "CLAUDE_CODE_MAX_RETRIES")}, "argv:", r.get("argv"))
assert env.get("ANTHROPIC_BASE_URL") == "http://127.0.0.1:%s" % port, env.get("ANTHROPIC_BASE_URL")
assert int(env.get("API_TIMEOUT_MS") or 0) > 900_000, "API_TIMEOUT_MS must exceed the daemon 900s wall: %r" % env.get("API_TIMEOUT_MS")
assert r.get("argv"), "empty argv"' "$2"
}
step "launch recipe: claudex base URL + API_TIMEOUT_MS > 900s" launch_recipe claudex "$CODEX_HEAD_PORT"
step "launch recipe: mockchat base URL + API_TIMEOUT_MS > 900s" launch_recipe mockchat "$CHAT_HEAD_PORT"

# ── 7b. per-head model roster + window: what /model offers, what the client compacts on ─────
# Each head materializes its OWN picker (settings.json availableModels + model, enforced, and a
# .claude.json additionalModelOptionsCache row per model carrying its context_window) and hands
# Claude Code its own CLAUDE_CODE_MAX_CONTEXT_TOKENS, from the topology alone. Three heads, three
# windows: a head reading another head's roster or window is exactly the fresh-install drift
# this step exists to catch.
<<<<<<< HEAD
# The same /launch also PACKAGES the head: the daemon's own status line (settings.json statusLine
# posting Claude Code's blob to /statusline/<head>), the in-session /login command and the hook
# that runs the head's sign-in when it is submitted. Splice is the whole package, so a head that
# launches without any of these is a failed install, not a cosmetic gap.
head_contract() { # head pinned-model pinned-window rows("id:window,...")
  curl_mgmt -X POST -H 'Content-Type: application/json' \
    --data '{"dangerouslySkipPermissions":"","args":[]}' "http://127.0.0.1:$CONTROL_PORT/launch/$1" \
    > "$OUT/recipe-$1.json" || return 1
  python3 - "$1" "$2" "$3" "$4" "$HOME" "$OUT/recipe-$1.json" "$CONTROL_PORT" <<'EOF'
import json, os, sys
head, model, window, rows_arg, home, recipe, control = sys.argv[1:8]
window = int(window)
expected_rows = {kv.split(":")[0]: int(kv.split(":")[1]) for kv in rows_arg.split(",")}
=======
head_contract() { # head model window
  curl_mgmt -X POST -H 'Content-Type: application/json' \
    --data '{"dangerouslySkipPermissions":"","args":[]}' "http://127.0.0.1:$CONTROL_PORT/launch/$1" \
    > "$OUT/recipe-$1.json" || return 1
  python3 - "$1" "$2" "$3" "$HOME" "$OUT/recipe-$1.json" <<'EOF'
import json, os, sys
head, model, window, home, recipe = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4], sys.argv[5]
>>>>>>> origin/main
r = json.load(open(recipe)); env = r.get("env", {})
cfg = env.get("CLAUDE_CONFIG_DIR") or os.path.join(home, ".claude-" + head)
settings = json.load(open(os.path.join(cfg, "settings.json")))
cache = json.load(open(os.path.join(cfg, ".claude.json"))).get("additionalModelOptionsCache", [])
rows = {row["value"]: row.get("context_window") for row in cache}
<<<<<<< HEAD
statusline = (settings.get("statusLine") or {}).get("command", "")
hooks = settings.get("hooks", {}).get("UserPromptSubmit", [])
hook_cmds = [h.get("command", "") for entry in hooks for h in entry.get("hooks", [])]
login_md = os.path.join(cfg, "commands", "login.md")
print("recipe:", {k: env.get(k) for k in ("ANTHROPIC_MODEL", "CLAUDE_CODE_MAX_CONTEXT_TOKENS", "CLAUDE_CODE_AUTO_COMPACT_WINDOW", "CLAUDE_CONFIG_DIR")})
print("picker:", {"model": settings.get("model"), "availableModels": settings.get("availableModels"),
                  "enforceAvailableModels": settings.get("enforceAvailableModels"), "rows": rows})
print("packaging:", {"statusLine": statusline, "login.md": os.path.isfile(login_md), "UserPromptSubmit": hook_cmds})
assert env.get("ANTHROPIC_MODEL") == model, env.get("ANTHROPIC_MODEL")
assert env.get("CLAUDE_CODE_MAX_CONTEXT_TOKENS") == str(window), env.get("CLAUDE_CODE_MAX_CONTEXT_TOKENS")
assert env.get("CLAUDE_CODE_AUTO_COMPACT_WINDOW") == str(window), env.get("CLAUDE_CODE_AUTO_COMPACT_WINDOW")
assert settings.get("model") == model, settings.get("model")
assert settings.get("availableModels") == list(expected_rows), "picker off: %r" % settings.get("availableModels")
assert settings.get("enforceAvailableModels") is True, "picker is not enforced"
assert rows == expected_rows, rows
assert statusline == f"curl -sS --data-binary @- http://127.0.0.1:{control}/statusline/{head}", "status line not splice's: %r" % statusline
assert os.path.isfile(login_md), "no in-session /login command materialized"
assert any("splice-login-hook" in c for c in hook_cmds), "no /login hook on UserPromptSubmit: %r" % hook_cmds
EOF
}
step "head contract + packaging: claudex (gpt-5-codex @272k, status line, /login)" head_contract claudex gpt-5-codex 272000 "gpt-5-codex:272000"
step "head contract + packaging: mockchat (mock-chat @128k, status line, /login)" head_contract mockchat mock-chat 128000 "mock-chat:128000"
step "head contract + packaging: mockchat2 (mock-chat-2 @64k + a 128k row, status line, /login)" head_contract mockchat2 mock-chat-2 64000 "mock-chat-2:64000,mock-chat-2-big:128000"

# ── 7c. the daemon's status line on a SCALED row ─────────────────────────────────────────────
# Claude Code fixes its context window per process (the pinned row's) and splice scales the counts
# it reports so any other row compacts at its own window, so the blob Claude Code pipes back is in
# client units. On the 128k row of the 64k mockchat2 session, 32000 reported tokens are 64000
# real ones: the bar must read the row's label and "64k/128k", not "32k/64k". The pinned row is the
# control: nothing changes. Posted exactly as Claude Code does (no management key on /statusline).
statusline_row() { # head model expected-fragment...
  local head="$1" model="$2" line; shift 2
  line="$(curl -sS -m 10 --data-binary @- "http://127.0.0.1:$CONTROL_PORT/statusline/$head" <<EOF | strip_ansi
{"model":{"id":"$model","display_name":"$model"},
 "context_window":{"context_window_size":64000,"used_percentage":50,
   "current_usage":{"input_tokens":2000,"cache_read_input_tokens":30000,"cache_creation_input_tokens":0}}}
EOF
)"
  printf '%s\n' "$line"
  local frag
  for frag in "$@"; do
    printf '%s' "$line" | grep -qF -- "$frag" || { echo "status line lacks '$frag'"; return 1; }
  done
}
step "status line: the scaled 128k row shows its label and real window" statusline_row mockchat2 mock-chat-2-big "Chat 2 big (mock)" "64k/128k" "50%"
step "status line: the pinned 64k row is untouched" statusline_row mockchat2 mock-chat-2 "Chat 2 (mock)" "32k/64k" "50%"
=======
print("recipe:", {k: env.get(k) for k in ("ANTHROPIC_MODEL", "CLAUDE_CODE_MAX_CONTEXT_TOKENS", "CLAUDE_CODE_AUTO_COMPACT_WINDOW", "CLAUDE_CONFIG_DIR")})
print("picker:", {"model": settings.get("model"), "availableModels": settings.get("availableModels"),
                  "enforceAvailableModels": settings.get("enforceAvailableModels"), "rows": rows})
assert env.get("ANTHROPIC_MODEL") == model, env.get("ANTHROPIC_MODEL")
assert env.get("CLAUDE_CODE_MAX_CONTEXT_TOKENS") == str(window), env.get("CLAUDE_CODE_MAX_CONTEXT_TOKENS")
assert env.get("CLAUDE_CODE_AUTO_COMPACT_WINDOW") == str(window), env.get("CLAUDE_CODE_AUTO_COMPACT_WINDOW")
assert settings.get("model") == model and settings.get("availableModels") == [model], "picker off: %r" % settings.get("availableModels")
assert settings.get("enforceAvailableModels") is True, "picker is not enforced"
assert rows == {model: window}, rows
EOF
}
step "head contract: claudex offers gpt-5-codex at 272k" head_contract claudex gpt-5-codex 272000
step "head contract: mockchat offers mock-chat at 128k" head_contract mockchat mock-chat 128000
step "head contract: mockchat2 offers mock-chat-2 at 64k" head_contract mockchat2 mock-chat-2 64000
>>>>>>> origin/main

# ── 8. the real wrapper: Claude Code itself, print mode, through the head, to the mock ───────
wrapper_turn() { # wrapper expected-substring
  local out rc
  out="$(DISABLE_AUTOUPDATER=1 DISABLE_TELEMETRY=1 DISABLE_ERROR_REPORTING=1 \
    CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1 \
    timeout 120 "$1" -p "Count from 1 to 3 then say END." --output-format text </dev/null 2>&1)"
  rc=$?
  printf '%s\n' "$out" | tail -c 1500
  [ $rc -eq 0 ] || { echo "wrapper exit $rc"; return 1; }
  printf '%s' "$out" | grep -qF "$2" || { echo "wrapper output lacks the mock's reply '$2'"; return 1; }
}
daemon_down() {
  pkill -u "$(id -un)" -f 'splice.jar daemon' || true
  for _ in $(seq 1 100); do
    if ! pgrep -u "$(id -un)" -f 'splice.jar daemon' >/dev/null &&
       ! curl -sf -m 1 "http://127.0.0.1:$CONTROL_PORT/health" >/dev/null 2>&1; then
      echo "daemon process gone, :$CONTROL_PORT closed"; return 0
    fi
    sleep 0.2
  done
  echo "daemon still alive 20s after pkill"; pgrep -a -f 'splice.jar daemon'; return 1
}
step "daemon stopped (the shim must boot it on first launch)" daemon_down
# The vendored codex mock answers every basic turn with "ok after auth"; the chat mock ends in END.
step "wrapper turn: claudex -p boots the daemon and completes" wrapper_turn claudex "ok after auth"
step "wrapper turn: claude-mockchat -p through the head" wrapper_turn claude-mockchat "END"

<<<<<<< HEAD
# ── 8a. plan usage: the head's 5h/7d windows ride every response and draw the bars ───────────
# The daemon polls the provider's usage endpoint (the mock's wham/usage: 14% of 5h, 42% of 7d),
# stamps every response with the anthropic-ratelimit-unified-* headers Claude Code reads into its
# rate_limits, and draws the same windows on the status line beside effort and session spend.
quota_bars() {
  local log="$HOME/.claude-codex/logs/daemon.log" hdrs line frag
  for _ in $(seq 1 30); do grep -q '\[claudex\]\[quota\]' "$log" 2>/dev/null && break; sleep 0.5; done
  grep '\[claudex\]\[quota\]' "$log" | tail -1 || { echo "the codex usage probe never reported"; return 1; }
  hdrs="$(curl_mgmt -D - -o /dev/null -X POST "http://127.0.0.1:$CODEX_HEAD_PORT/v1/messages" \
    -H 'Content-Type: application/json' \
    -d '{"model":"claude-codex--gpt-5-codex","max_tokens":16,"stream":false,"messages":[{"role":"user","content":"hi"}]}')"
  printf '%s\n' "$hdrs" | grep -i 'anthropic-ratelimit-unified' | tr -d '\r'
  printf '%s' "$hdrs" | grep -qi '^anthropic-ratelimit-unified-5h-utilization: 0.1400' ||
    { echo "the head's response carries no 5h utilization header"; return 1; }
  printf '%s' "$hdrs" | grep -qi '^anthropic-ratelimit-unified-7d-utilization: 0.4200' ||
    { echo "the head's response carries no 7d utilization header"; return 1; }
  line="$(curl -sS -m 10 --data-binary '{"model":{"id":"gpt-5-codex"},"effort":{"level":"high"},"cost":{"total_cost_usd":1.5}}' \
    "http://127.0.0.1:$CONTROL_PORT/statusline/claudex" | strip_ansi)"
  printf '%s\n' "$line"
  for frag in "Codex (mock)·high" '$1.50' "5h █░░░░░░░ 14%" "7d ███░░░░░ 42%"; do
    printf '%s' "$line" | grep -qF -- "$frag" || { echo "status line lacks '$frag'"; return 1; }
  done
}
step "plan usage: 5h/7d windows on every claudex response and on its status line" quota_bars

# ── 8a'. `<wrapper> login`: the sign-in verb every head installs with its wrapper ────────────
# claudex is an OAuth head: `claudex login` must start the ChatGPT browser flow — bind the loopback
# callback, print the authorize URL (no browser here) and wait for the callback. The paste
# fallback only shows on a terminal, and there is none here. With no network and stdin closed it
# waits out its callback timeout, so the run is bounded by `timeout`.
login_oauth() { # wrapper expected-host
  local out
  out="$(timeout 10 "$1" login </dev/null 2>&1)" || true
  printf '%s\n' "$out" | head -8
  printf '%s' "$out" | grep -q 'open this URL to sign in' || { echo "$1 login did not print the sign-in URL"; return 1; }
  printf '%s' "$out" | grep -q "https://$2" || { echo "$1 login URL is not on $2"; return 1; }
}
# claude-mockchat is an api-key head: with no terminal, `login` names the pipe alternative
# verbatim, and that command stores the key in ~/.config/splice/keys.toml (0600).
login_apikey() {
  local out
  out="$(claude-mockchat login </dev/null 2>&1)" || true
  printf '%s\n' "$out"
  printf '%s' "$out" | grep -qF 'splice key set MOCK_CHAT_API_KEY --stdin' || { echo "login did not name the pipe path"; return 1; }
  printf '%s' "mock-chat-key" | splice key set MOCK_CHAT_API_KEY --stdin || { echo "splice key set failed"; return 1; }
  local store="$HOME/.config/splice/keys.toml"
  [ -f "$store" ] || { echo "no $store after key set"; return 1; }
  grep -q '^MOCK_CHAT_API_KEY' "$store" || { echo "key not stored"; cat "$store"; return 1; }
  [ "$(stat -c %a "$store")" = "600" ] || { echo "keys.toml mode is $(stat -c %a "$store"), not 600"; return 1; }
  echo "MOCK_CHAT_API_KEY stored in $store (0600)"
}
step "claudex login: the ChatGPT OAuth flow starts from the installed wrapper" login_oauth claudex "auth.openai.com"
step "claude-mockchat login: the api-key path names its pipe, and the pipe stores the key" login_apikey

=======
>>>>>>> origin/main
# ── 8b. cross-head sessions: every head's sessions/ IS the operator's global registry ─────────
# Claude Code discovers peer sessions by listing $CLAUDE_CONFIG_DIR/sessions (the message sockets
# are machine-global already), so per-head config isolation is the only thing that could hide one
# head's sessions from another's ListAgents. On first launch the daemon links each head's dir at
# ~/.claude/sessions — CREATING it here, because plain `claude` has never run on this machine —
# which is what lets claudex, claude-mockchat and plain claude sessions see and message each other.
# The proof is the mechanism itself: a registration written under one head is read under the other.
sessions_shared() {
  local global="$HOME/.claude/sessions" cfg probe
  [ -d "$global" ] || { echo "global registry $global was not created"; ls -la "$HOME/.claude" 2>&1; return 1; }
  for cfg in "$HOME/.claude-claudex" "$HOME/.claude-mockchat"; do
    [ -L "$cfg/sessions" ] || { echo "$cfg/sessions is not a link"; ls -la "$cfg" 2>&1; return 1; }
    [ "$(readlink -f "$cfg/sessions")" = "$(readlink -f "$global")" ] ||
      { echo "$cfg/sessions -> $(readlink "$cfg/sessions"), not the global registry"; return 1; }
  done
  probe="e2e-probe-$$.json"
  echo '{"probe":true}' > "$HOME/.claude-claudex/sessions/$probe"
  if [ ! -f "$HOME/.claude-mockchat/sessions/$probe" ] || [ ! -f "$global/$probe" ]; then
    rm -f "$global/$probe"
    echo "a registration written under claudex is invisible from mockchat or the global registry"; return 1
  fi
  rm -f "$global/$probe"
  echo "claudex and mockchat both resolve sessions/ to $global; a claudex registration is visible from mockchat"
}
step "cross-head sessions: both heads share ~/.claude/sessions, created on first launch" sessions_shared

# ── 8c. cross-head ListAgents: a session on one head lists a live session held on another ────
# The thing the announcement claims, run for real. claude-mockchat holds a turn (the mock sleeps
# on it) from a directory named heldpeer; claude-mockchat2, a different head with a different
# CLAUDE_CONFIG_DIR, is told to call ListAgents. The mock answers that turn with one ListAgents
# call, then echoes the tool result back as "PEERS: …", so the caller's printed output IS what
# ListAgents returned inside the second head. It must name the session held under the first.
cross_head_listagents() {
  local registry="$HOME/.claude/sessions" before held_pid held_json out rc
  mkdir -p "$HOME/heldpeer"
  before="$(ls "$registry" 2>/dev/null | sort)"
  ( cd "$HOME/heldpeer" && DISABLE_AUTOUPDATER=1 DISABLE_TELEMETRY=1 DISABLE_ERROR_REPORTING=1 \
      CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1 \
      timeout 120 claude-mockchat -p "SCENARIO:hold Say END." --output-format text </dev/null >"$OUT/held-session.txt" 2>&1 ) &
  held_pid=$!
  for _ in $(seq 1 60); do
    held_json="$(comm -13 <(printf '%s\n' "$before") <(ls "$registry" 2>/dev/null | sort) | head -1)"
    [ -n "$held_json" ] && break
    sleep 0.5
  done
  if [ -z "$held_json" ]; then
    echo "the held claude-mockchat session never registered"; cat "$OUT/held-session.txt"
    pkill -f 'SCENARIO:hold' 2>/dev/null || true; return 1
  fi
  echo "held session registered under claude-mockchat: $(cat "$registry/$held_json")"
  out="$(cd "$HOME" && DISABLE_AUTOUPDATER=1 DISABLE_TELEMETRY=1 DISABLE_ERROR_REPORTING=1 \
    CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1 \
    timeout 120 claude-mockchat2 -p "SCENARIO:listagents Call ListAgents and reply with its output." \
      --allowed-tools ListAgents --output-format text </dev/null 2>&1)"
  rc=$?
  pkill -f 'SCENARIO:hold' 2>/dev/null || true; wait "$held_pid" 2>/dev/null || true
  printf '%s\n' "$out" | tail -c 2000
  [ $rc -eq 0 ] || { echo "claude-mockchat2 exit $rc"; return 1; }
  printf '%s' "$out" | grep -qF 'PEERS:' || { echo "the mock never received a ListAgents tool result: the tool was not called"; return 1; }
  printf '%s' "$out" | grep -q 'heldpeer' || { echo "ListAgents inside claude-mockchat2 does not list the session held under claude-mockchat"; return 1; }
  echo "ListAgents inside claude-mockchat2 listed the claude-mockchat session held from ~/heldpeer"
}
step "cross-head ListAgents: claude-mockchat2 lists a session held on claude-mockchat" cross_head_listagents

# ── 8d. the shipped example topology, on this machine, without a single credential ───────────
# config/splice.example.toml is what a fresh install starts from. Boot it here: every head it
# declares must install, list, and hand out a launch recipe whose model and window are the ones
# the example promises, with its sessions registry linked and its wrapper on PATH. No provider is
# reachable and no auth exists, which is a fresh machine before the operator's first login: the
# daemon must still stand. The e2e topology is put back (and its wrappers relinked) afterwards.
example_heads() { # example.toml
  for _ in $(seq 1 60); do curl -sf -m 3 "http://127.0.0.1:$CONTROL_PORT/health" >/dev/null 2>&1 && break; sleep 1; done
  curl -s -m 3 "http://127.0.0.1:$CONTROL_PORT/health"; echo
  curl_mgmt "http://127.0.0.1:$CONTROL_PORT/api/heads" > "$OUT/example-heads.json" || return 1
  python3 - "$1" "$OUT/example-heads.json" "$CONTROL_PORT" "$(mgmt)" "$HOME" <<'EOF'
import json, os, sys, tomllib, urllib.request
example, heads_file, port, key, home = sys.argv[1:6]
t = tomllib.load(open(example, "rb"))
d = json.load(open(heads_file)); heads = d.get("heads", d)
rows = heads.values() if isinstance(heads, dict) else heads
listed = sorted(h["key"] for h in rows); declared = sorted(t["heads"])
print("declared:", declared)
print("listed:  ", [(h["key"], h.get("running"), h.get("healthy")) for h in rows])
assert listed == declared, (listed, declared)

def launch(head):
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}/launch/{head}", data=b'{"dangerouslySkipPermissions":"","args":[]}',
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.load(resp)

registry = os.path.realpath(os.path.join(home, ".claude", "sessions"))
bad = []
for head, h in t["heads"].items():
    prov = t["providers"][h["provider"]]
    windows = {m["id"]: m.get("context_window") for m in prov.get("models", [])}
    pinned = h["pinned_model"]
    want = h.get("context_window") or windows.get(pinned) or prov.get("context_window")
    command = h.get("claude", {}).get("command", head)
    wrapper = os.access(os.path.join(home, ".local", "bin", command), os.X_OK)
    try:
        env = launch(head).get("env", {})
    except Exception as e:  # noqa: BLE001 — the receipt wants the reason, whatever it is
        print(f"{head:14} {command:18} launch FAILED: {e}"); bad.append(head); continue
    sessions = os.path.join(env.get("CLAUDE_CONFIG_DIR", ""), "sessions")
    linked = os.path.islink(sessions) and os.path.realpath(sessions) == registry
    ok = (env.get("ANTHROPIC_MODEL") == pinned and env.get("CLAUDE_CODE_MAX_CONTEXT_TOKENS") == str(want)
          and linked and wrapper)
    print(f"{head:14} {command:18} model={env.get('ANTHROPIC_MODEL')} window={env.get('CLAUDE_CODE_MAX_CONTEXT_TOKENS')}"
          f" example={pinned}@{want} sessions_link={linked} wrapper={wrapper} {'OK' if ok else 'MISMATCH'}")
    if not ok:
        bad.append(head)
assert not bad, f"heads off the example contract: {bad}"
<<<<<<< HEAD

# `<wrapper> login` for every head of the shipped example, offline: each auth kind must reach ITS
# flow — OAuth prints the authorize URL and waits (bounded by timeout), the Kimi device flow fails
# on the unreachable network AFTER trying, api-key names the pipe path, client auth says so.
import subprocess
expect = {
    "chatgpt-oauth": ["open this URL to sign in", "https://auth.openai.com"],
    "grok-oauth": ["open this URL to sign in", "https://"],
    "kimi-oauth": ["login error", "could not start device login", "enter this code"],
    "api-key": ["pipe it instead", "splice key set"],
    "client": ["no browser login for that kind"],
}
failed = []
for head, h in t["heads"].items():
    kind = t["providers"][h["provider"]]["auth"]["kind"]
    command = h.get("claude", {}).get("command", head)
    proc = subprocess.run(["timeout", "10", os.path.join(home, ".local", "bin", command), "login"],
                          stdin=subprocess.DEVNULL, capture_output=True, text=True)
    out = proc.stdout + proc.stderr
    wanted = expect[kind]
    hit = all(w in out for w in wanted) if kind in ("chatgpt-oauth", "grok-oauth", "api-key") else any(w in out for w in wanted)
    first = next((l for l in out.splitlines() if l.strip()), "")
    print(f"{command:18} {kind:13} {'OK' if hit else 'MISSING'}  {first[:110]}")
    if not hit:
        print(out[-600:]); failed.append(command)
assert not failed, f"login verb did not reach its provider flow for: {failed}"
=======
>>>>>>> origin/main
EOF
}
example_topology() {
  local example="$REPO/config/splice.example.toml" live="$HOME/.config/splice/splice.toml" rc=0
  cp "$live" "$OUT/e2e-topology.toml"
  cp "$example" "$live"
  # install --all links every wrapper the topology declares; the running daemon still serves the
  # old topology (/health says topologyStale) until `splice restart`, exactly as on a real machine.
  splice install --all </dev/null || { echo "install --all failed on the example topology"; rc=1; }
  [ $rc -eq 0 ] && { splice restart </dev/null || { echo "restart failed on the example topology"; rc=1; }; }
  [ $rc -eq 0 ] && { example_heads "$example" || rc=1; }
  # Back to the e2e topology the same way (never `uninstall` here: it removes the splice command).
  cp "$OUT/e2e-topology.toml" "$live"
  splice install --all </dev/null >/dev/null || { echo "could not reinstall the e2e topology"; return 1; }
  return $rc
}
step "shipped example topology: every head installs, boots and launches to the example's model and window" example_topology

# ── 9. restart, logs, status, uninstall ────────────────────────────────────────────────────────
restart_step() {
  splice restart </dev/null || return 1
  wait_health 60
}
step "splice restart: healthy again" restart_step
# Exit status alone is not a verdict for reporting commands (a status that reports a dead daemon
# still exits 0): assert the content the step name promises.
logs_step() {
  local out
  out="$(splice logs --tail 5 </dev/null 2>&1)" || { printf '%s\n' "$out"; return 1; }
  printf '%s\n' "$out"
  [ "$(printf '%s\n' "$out" | grep -c .)" -ge 1 ] || { echo "empty log tail"; return 1; }
}
status_step() {
  local out
  out="$(splice status </dev/null 2>&1 | strip_ansi)" || { printf '%s\n' "$out"; return 1; }
  printf '%s\n' "$out"
  printf '%s\n' "$out" | grep -qE '^\s*daemon\s+running' || { echo "status does not report the daemon running"; return 1; }
  printf '%s\n' "$out" | grep -q 'claudex' && printf '%s\n' "$out" | grep -q 'claude-mockchat2' || { echo "status lacks a head row"; return 1; }
}
step "splice logs --tail 5: non-empty tail" logs_step
step "splice status: daemon running, every head listed" status_step

uninstall_step() {
  local out rc
  out="$(splice uninstall </dev/null 2>&1)"; rc=$?
  printf '%s\n' "$out" | tail -c 800
  [ $rc -eq 0 ] || { echo "uninstall exit $rc"; return 1; }
  [ ! -e "$HOME/.local/bin/claudex" ] || { echo "claudex link survived uninstall"; return 1; }
  [ ! -e "$HOME/.local/bin/claude-mockchat" ] || { echo "claude-mockchat link survived uninstall"; return 1; }
  [ ! -e "$HOME/.local/bin/claude-mockchat2" ] || { echo "claude-mockchat2 link survived uninstall"; return 1; }
}
step "splice uninstall removes the wrappers" uninstall_step
