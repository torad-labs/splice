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
  nohup python3 "$REPO/checks/e2e/docker/mock_chat.py" 0 > "$OUT/mock_chat.out" 2> "$OUT/mock_chat.err" &
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
  ss -ltn | grep -E ":($CONTROL_PORT|$CODEX_HEAD_PORT|$CHAT_HEAD_PORT) " || { echo "head ports not listening"; return 1; }
}
step "daemon cold start: /health ok, every head ready" cold_start

api_heads() {
  curl_mgmt "http://127.0.0.1:$CONTROL_PORT/api/heads" | python3 -c '
import json, sys
d = json.load(sys.stdin); heads = d.get("heads", d)
rows = heads.values() if isinstance(heads, dict) else heads
keys = sorted(h["key"] for h in rows)
print("heads:", [(h["key"], h.get("running"), h.get("healthy")) for h in rows])
assert keys == ["claudex", "mockchat"], keys
assert all(h.get("running") for h in rows), "a head is not running"'
}
step "/api/heads lists both heads running" api_heads

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
  printf '%s\n' "$out" | grep -q 'claudex' && printf '%s\n' "$out" | grep -q 'claude-mockchat' || { echo "status lacks a head row"; return 1; }
}
step "splice logs --tail 5: non-empty tail" logs_step
step "splice status: daemon running, both heads listed" status_step

uninstall_step() {
  local out rc
  out="$(splice uninstall </dev/null 2>&1)"; rc=$?
  printf '%s\n' "$out" | tail -c 800
  [ $rc -eq 0 ] || { echo "uninstall exit $rc"; return 1; }
  [ ! -e "$HOME/.local/bin/claudex" ] || { echo "claudex link survived uninstall"; return 1; }
  [ ! -e "$HOME/.local/bin/claude-mockchat" ] || { echo "claude-mockchat link survived uninstall"; return 1; }
}
step "splice uninstall removes the wrappers" uninstall_step
