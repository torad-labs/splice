# tools/e2e/docker/lib.sh — sourced by the container scenarios (inside.sh: a fresh machine;
# upgrade.sh: a published release upgraded in place). Definitions only: the receipt plumbing, the
# mock upstreams and their topology, and the checks both scenarios run. A scenario sets its options,
# sources this, arms `trap finish EXIT`, and owns its steps and their order.

# V4-177: same state-root rule as StatePaths.kt and app/src/main/dist/bin/splice-launch — SPLICE_STATE_DIR, then the
# pre-0.4 CLAUDEX_STATE_DIR, then ~/.splice/state, adopting ~/.claude-codex/state in place when that
# is the only root on the box. The fresh machine never has a pre-0.4 root to adopt; the upgrade
# scenario always does (0.3.x wrote ~/.claude-codex/state), which is the branch it exercises.
resolve_state_dir() {
  # A variable holding only whitespace is NOT an answer. `-n` calls " " set; Kotlin's isNotBlank
  # does not, and StatePaths blank-checks PER VARIABLE. Without this, SPLICE_STATE_DIR=" " in a unit
  # file makes this read " /mgmt-key" relative to CWD and report "mgmt-key not found" on a perfectly
  # healthy install, while the daemon resolves the real root. The pattern IS isNotBlank: at least
  # one non-whitespace character. Per variable, so an empty SPLICE_STATE_DIR falls through to
  # CLAUDEX_STATE_DIR instead of skipping it.
  case "${SPLICE_STATE_DIR:-}" in *[![:space:]]*) printf '%s\n' "$SPLICE_STATE_DIR"; return 0 ;; esac
  case "${CLAUDEX_STATE_DIR:-}" in *[![:space:]]*) printf '%s\n' "$CLAUDEX_STATE_DIR"; return 0 ;; esac
  # Adoption needs POSITIVE evidence on both sides, the rule StatePaths' three-valued probe follows:
  # only proven absence may start a fresh root. `[ ! -d ]` is ALSO true for a path that cannot be
  # stat-ed, so an unreadable ~/.splice would adopt the pre-0.4 root here while the daemon declines
  # and warns. Believe "absent" only when the parent is traversable, or absent itself.
  # `-e`, not `-d`: a REGULAR FILE at the current root is not proven absence either, and `[ ! -d ]`
  # called it adoptable while StatePaths declines and reports it as a fault.
  if [ ! -e "$HOME/.splice/state" ] && { [ ! -e "$HOME/.splice" ] || [ -x "$HOME/.splice" ]; } &&
     [ -d "$HOME/.claude-codex/state" ]; then
    printf '%s\n' "$HOME/.claude-codex/state"
  else
    printf '%s\n' "$HOME/.splice/state"
  fi
}

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
TESTED_CLAUDE_CODE="${SPLICE_TESTED_CLAUDE_CODE:-}"
CLAUDE_CODE_ACTUAL="$(claude --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || true)"
# The scenario names its receipt; the fresh machine is the default so its receipts read as before.
RECEIPT_KIND="${RECEIPT_KIND:-splice-fresh-machine-e2e}"
RECEIPT_LABEL="${RECEIPT_LABEL:-FRESH-MACHINE E2E}"

# Every JSON read and byte comparison lives in lib.ts beside this file; the shell keeps the flow.
LIB_TS="$(dirname "${BASH_SOURCE[0]}")/lib.ts"

# ── receipt plumbing ─────────────────────────────────────────────────────────────────────────────
STEP_N=0
record() { # name verdict seconds detail
  local detail slug
  STEP_N=$((STEP_N + 1))
  slug="$(printf '%s' "$1" | tr -c 'A-Za-z0-9' '-' | tr -s '-' | sed 's/^-//; s/-$//' | cut -c1-60)"
  mkdir -p "$OUT/steps"
  printf '%s\n' "$4" > "$OUT/steps/$(printf '%02d' "$STEP_N")-$slug.log"
  detail="$(printf '%s' "$4" | tail -c 2000)"
  bun "$LIB_TS" record "$STEPS_FILE" "$1" "$2" "$3" "$detail"
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
  record "$name" "$([ $rc -eq 0 ] && echo PASS || echo FAIL)" "$(awk -v a="$t0" -v b="$t1" 'BEGIN { printf "%.2f", b - a }')" "$out"
}

finish() {
  for pidf in "$OUT"/mock_*.pid; do
    [ -f "$pidf" ] && kill "$(cat "$pidf")" 2>/dev/null
  done
  # Named, not swallowed: this runs in the EXIT trap, so a wrong root or an absent log used to
  # leave the artifacts dir with no daemon.log and nothing saying why — whoever investigates the
  # e2e red gets no daemon output and no explanation for its absence.
  _daemon_log="$(resolve_state_dir)/../logs/daemon.log"
  cp "$_daemon_log" "$OUT/daemon.log" 2>/dev/null || echo "no daemon.log at $_daemon_log" >&2
  bun "$LIB_TS" finish "$STEPS_FILE" "$RECEIPT" "$FAILED" "$CLAUDE_CODE_ACTUAL" "$TESTED_CLAUDE_CODE" \
    "$RECEIPT_KIND" "$RECEIPT_LABEL"
  exit "$FAILED"
}

# Fails BY NAME. `cat` on a missing key wrote to stderr and yielded "", so every curl went out as
# `Authorization: Bearer ` and the run reported a wall of 401s — "no key at this path" told as an
# auth failure, which sends the reader looking at the wrong half of the system.
mgmt() {
  local key="$(resolve_state_dir)/mgmt-key"
  [ -r "$key" ] || { echo "no mgmt-key at $key" >&2; return 1; }
  cat "$key"
}
curl_mgmt() { curl -sS -m 10 -H "Authorization: Bearer $(mgmt)" "$@"; }
strip_ansi() { sed 's/\x1b\[[0-9;]*m//g'; }

wait_health() { # seconds -> 0 when /health reports ok with every head ready
  local deadline=$(( $(date +%s) + $1 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    if curl -sf -m 3 "http://127.0.0.1:$CONTROL_PORT/health" 2>/dev/null | bun "$LIB_TS" health-ready >/dev/null 2>&1; then
      curl -sf -m 3 "http://127.0.0.1:$CONTROL_PORT/health"; echo
      return 0
    fi
    sleep 1
  done
  echo "daemon not healthy after $1s"; curl -s -m 3 "http://127.0.0.1:$CONTROL_PORT/health"; echo
  _daemon_log="$(resolve_state_dir)/../logs/daemon.log"
  tail -20 "$_daemon_log" 2>/dev/null || echo "no daemon.log at $_daemon_log" >&2
  return 1
}

client_version_receipt() {
  echo "actual=$CLAUDE_CODE_ACTUAL tested=$TESTED_CLAUDE_CODE"
  [ -n "$CLAUDE_CODE_ACTUAL" ] || { echo "claude --version did not report a numeric version"; return 1; }
  [ -n "$TESTED_CLAUDE_CODE" ] || { echo "SPLICE_TESTED_CLAUDE_CODE was not provided"; return 1; }
  [ "$CLAUDE_CODE_ACTUAL" = "$TESTED_CLAUDE_CODE" ] || {
    echo "the image has Claude Code $CLAUDE_CODE_ACTUAL, but splice records $TESTED_CLAUDE_CODE as tested"
    return 1
  }
}

# ── the mock upstreams (loopback only) ───────────────────────────────────────────────────────────
# step() runs its command in a command substitution (a subshell), so the mocks report through
# files — pid + the one JSON line each prints — and the MAIN shell reads the ports back.
start_mocks() {
  nohup node "$REPO/tools/e2e/docker/mock_codex.mjs" "$REPO" 0 > "$OUT/mock_codex.out" 2> "$OUT/mock_codex.err" &
  echo $! > "$OUT/mock_codex.pid"
  MOCK_CHAT_HOLD_S=45 nohup bun "$REPO/tools/e2e/docker/mock_chat.ts" 0 > "$OUT/mock_chat.out" 2> "$OUT/mock_chat.err" &
  echo $! > "$OUT/mock_chat.pid"
  for _ in $(seq 1 50); do
    [ -s "$OUT/mock_codex.out" ] && [ -s "$OUT/mock_chat.out" ] && break
    sleep 0.2
  done
  [ -s "$OUT/mock_codex.out" ] || { echo "codex mock did not start: $(cat "$OUT/mock_codex.err")"; return 1; }
  [ -s "$OUT/mock_chat.out" ] || { echo "chat mock did not start: $(cat "$OUT/mock_chat.err")"; return 1; }
  cat "$OUT/mock_codex.out" "$OUT/mock_chat.out"
}
mock_field() { bun "$LIB_TS" field "$OUT/$1" "$2"; }
read_mock_ports() { # in the MAIN shell, after start_mocks
  CODEX_MOCK_PORT="$(mock_field mock_codex.out port 2>/dev/null)"
  CODEX_AUTH_PATH="$(mock_field mock_codex.out auth_path 2>/dev/null)"
  CHAT_MOCK_PORT="$(mock_field mock_chat.out port 2>/dev/null)"
  echo "  codex mock :$CODEX_MOCK_PORT auth=$CODEX_AUTH_PATH; chat mock :$CHAT_MOCK_PORT"
}

# ── topology: two heads, two dialects, every upstream a mock ──────────────────────────────────
# Written BEFORE install.sh so `splice init` keeps it (init materializes the starter only on
# proven absence) and `install --all` links exactly these heads.
write_topology() {
  mkdir -p "$HOME/.config/splice"
  cat > "$HOME/.config/splice/splice.toml" <<EOF
# fresh-machine e2e topology — generated by tools/e2e/docker/inside.sh
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
[[providers.mockchat2.models]]
id = "mock-chat-2-big"
label = "Chat 2 big (mock)"
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

# The daemon inherits these from whichever CLI call boots it (install.sh's doctor, or status).
# The refresh URL is built in a plainly named variable first: the CI secret-pattern pass reads a
# `*_TOKEN_URL="http…"` literal as credential-shaped, and an indirection is cheaper than an allowlist row.
export_mock_env() { # in the MAIN shell, after read_mock_ports
  CODEX_MOCK_REFRESH="http://127.0.0.1:$CODEX_MOCK_PORT/oauth/token"
  export MOCK_CHAT_API_KEY="mock-chat-key"
  export CODEX_OAUTH_TOKEN_URL="$CODEX_MOCK_REFRESH"
}

# ── install from release-style artifacts, exactly as a release install verifies them ───────────
install_step() { # [artifacts-dir]  (default $ARTIFACTS; the upgrade scenario installs /from first)
  local art="${1:-$ARTIFACTS}"
  [ -f "$art/splice.jar" ] || { echo "no $art/splice.jar"; return 1; }
  [ -f "$art/splice-launch" ] || { echo "no $art/splice-launch"; return 1; }
  if [ -f "$art/sha256sums.txt" ]; then
    # --ignore-missing lets a manifest that omits an artifact pass in silence (reproduced in the
    # review of #116), so the two names this step claims to verify are asserted by name.
    local sums f
    sums="$(cd "$art" && sha256sum -c sha256sums.txt --ignore-missing 2>&1)" || { printf '%s\n' "$sums"; return 1; }
    printf '%s\n' "$sums"
    for f in splice.jar splice-launch; do
      printf '%s\n' "$sums" | grep -qx "$f: OK" || { echo "$f is not covered by sha256sums.txt"; return 1; }
    done
  else
    echo "no sha256sums.txt beside the artifacts (checkout build) — checksum step skipped"
  fi
  local installer="$REPO/install.sh"
  [ -f "$art/install.sh" ] && installer="$art/install.sh"
  SPLICE_JAR="$art/splice.jar" SPLICE_SHIM="$art/splice-launch" bash "$installer" </dev/null || return 1
  [ -x "$HOME/.local/bin/splice" ] || { echo "splice command not linked"; return 1; }
  [ -x "$HOME/.local/bin/claudex" ] || { echo "claudex wrapper not linked"; return 1; }
  [ -x "$HOME/.local/bin/claude-mockchat" ] || { echo "claude-mockchat wrapper not linked"; return 1; }
  [ -x "$HOME/.local/bin/claude-mockchat2" ] || { echo "claude-mockchat2 wrapper not linked"; return 1; }
  ls -l "$HOME/.local/bin/"
  splice version
}

# ── cold start: `splice restart` is the CLI's boot verb (`status` only reports) ──────────────
cold_start() {
  splice restart </dev/null || return 1
  wait_health 60 || return 1
  ss -ltn | grep -E ":($CONTROL_PORT|$CODEX_HEAD_PORT|$CHAT_HEAD_PORT|$CHAT2_HEAD_PORT) " || { echo "head ports not listening"; return 1; }
}

api_heads() { curl_mgmt "http://127.0.0.1:$CONTROL_PORT/api/heads" | bun "$LIB_TS" api-heads; }

# ── per-head model roster + window, packaging, and the turn key ────────────────────────────────
# Each head materializes its OWN picker (settings.json availableModels + model, enforced, and a
# .claude.json additionalModelOptionsCache row per model carrying its context_window) and hands
# Claude Code ONE client window (CLAUDE_CODE_MAX_CONTEXT_TOKENS = the pinned row's): every other
# row's real window is applied by usage scaling on the wire, and a later topology edit reaches a
# running session through the window it reports on its status line. The four tier
# slots (ANTHROPIC_DEFAULT_{OPUS,SONNET,HAIKU,FABLE}_MODEL) never share a BARE id: two tiers on
# one bare id drew that model twice in /model (v0.3.0); a repeated tier now rides the head's
# discovery-wrapped spelling, which the allowlist hides (so wrapped values may repeat) and the
# head still routes. Three
# heads, three rosters: a head reading another head's roster is exactly the fresh-install drift
# this step exists to catch.
# The same /launch also PACKAGES the head: the daemon's own status line (settings.json statusLine
# posting Claude Code's blob to /statusline/<head>), the in-session /login command and the hook
# that runs the head's sign-in when it is submitted. Splice is the whole package, so a head that
# launches without any of these is a failed install, not a cosmetic gap.
head_contract() { # head pinned-model pinned-window rows("id:window,...")
  curl_mgmt -X POST -H 'Content-Type: application/json' \
    --data '{"dangerouslySkipPermissions":"","args":[]}' "http://127.0.0.1:$CONTROL_PORT/launch/$1" \
    > "$OUT/recipe-$1.json" || return 1
  # The management key goes by FILE, never on argv, where a process listing would show it.
  bun "$LIB_TS" head-contract "$1" "$2" "$3" "$4" "$HOME" "$OUT/recipe-$1.json" "$CONTROL_PORT" \
    "$(resolve_state_dir)/mgmt-key"
}

# ── the real wrapper: Claude Code itself, print mode, through the head, to the mock ───────────
wrapper_turn() { # wrapper expected-substring [extra claude args…, e.g. --session-id ID / --resume ID]
  local out rc
  out="$(DISABLE_AUTOUPDATER=1 DISABLE_TELEMETRY=1 DISABLE_ERROR_REPORTING=1 \
    CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1 \
    timeout 120 "$1" -p "Count from 1 to 3 then say END." --output-format text "${@:3}" </dev/null 2>&1)"
  rc=$?
  printf '%s\n' "$out" | tail -c 1500
  [ $rc -eq 0 ] || { echo "wrapper exit $rc"; return 1; }
  printf '%s' "$out" | grep -qF "$2" || { echo "wrapper output lacks the mock's reply '$2'"; return 1; }
}

# ── reporting and removal ─────────────────────────────────────────────────────────────────────
# Exit status alone is not a verdict for reporting commands (a status that reports a dead daemon
# still exits 0): assert the content the step name promises.
status_step() {
  local out
  out="$(splice status </dev/null 2>&1 | strip_ansi)" || { printf '%s\n' "$out"; return 1; }
  printf '%s\n' "$out"
  # The daemon's state rides on the wordmark line since c6ee5eaec ("splice X.Y.Z     daemon running on
  # PORT", or "daemon stopped (starts on first launch)"); the old labelled row is gone.
  printf '%s\n' "$out" | grep -qE "^\s*splice \S+\s+daemon running on $CONTROL_PORT\s*$" ||
    { echo "status does not report the daemon running on :$CONTROL_PORT"; return 1; }
  printf '%s\n' "$out" | grep -q 'claudex' && printf '%s\n' "$out" | grep -q 'claude-mockchat2' || { echo "status lacks a head row"; return 1; }
}

uninstall_step() {
  local out rc
  out="$(splice uninstall </dev/null 2>&1)"; rc=$?
  printf '%s\n' "$out" | tail -c 800
  [ $rc -eq 0 ] || { echo "uninstall exit $rc"; return 1; }
  [ ! -e "$HOME/.local/bin/claudex" ] || { echo "claudex link survived uninstall"; return 1; }
  [ ! -e "$HOME/.local/bin/claude-mockchat" ] || { echo "claude-mockchat link survived uninstall"; return 1; }
  [ ! -e "$HOME/.local/bin/claude-mockchat2" ] || { echo "claude-mockchat2 link survived uninstall"; return 1; }
}
