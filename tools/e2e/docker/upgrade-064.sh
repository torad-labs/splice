#!/usr/bin/env bash
# Rehearse both ways a used 0.6.x home takes a release. Run only inside run.sh's isolated Docker image.
set -uo pipefail
RECEIPT_KIND=splice-upgrade-064-e2e
RECEIPT_LABEL="MODERN UPGRADE E2E"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
DONE=0
close_receipt() {
  local rc=$?
  if [ "$DONE" -ne 1 ] && [ "$FAILED" -eq 0 ]; then
    record "scenario reached the end" FAIL 0 "aborted before the next step (exit $rc)"
  fi
  finish
}
trap close_receipt EXIT
umask 077

FROM=/from
FROM_TAG=${SPLICE_UPGRADE_FROM:-}
FROM_JAR="$FROM/${SPLICE_E2E_FROM_JAR_ASSET:-splice.jar}"
HELPER="$REPO/tools/e2e/docker/upgrade_064.ts"
CONFIG="$HOME/.config/splice"
TOPOLOGY="$CONFIG/splice.toml"
RELEASES="$HOME/.local/share/splice/releases"
STATE=$(resolve_state_dir)
CONTROL="http://127.0.0.1:$CONTROL_PORT"
HEADS=(claudex claude-grok claude-kimi claude-realinfer claude-realinfer-fast)
MODELS=(claude-codex--gpt-5-codex claude-grok--grok-4.6 claude-kimi--kimi-for-coding realinfer--mock-chat claude-realinfer--mock-chat-2)
REPLIES=("ok after auth" "ok after auth" "KIMI OK" END END)
HEADS_CSV=$(IFS=,; printf '%s' "${HEADS[*]}")
PROMPT="Preserve the rehearsal standing instruction."
WINDOW=180123
SID=5e55a0d1-0000-4000-8000-000000000064
WORK="$HOME/work"
TRANSCRIPT="$HOME/.claude/projects/$(printf '%s' "$WORK" | sed 's/[^A-Za-z0-9]/-/g')/$SID.jsonl"
SUBS_LOG="$PRIVATE/subs.jsonl"
CHAT_LOG="$PRIVATE/chat.jsonl"
HTTP_LOG="$PRIVATE/releases.log"

# A failed prerequisite stops this phase. A recorded independent assertion can fail without hiding
# the outcomes of the remaining checks; finish still fails the receipt.
need() {
  local earlier="$FAILED"
  FAILED=0
  step "$@"
  stop_if_failed
  FAILED="$earlier"
}
jar_release() { java -jar "$1" version | sed -n 's/^splice //p'; }
shim_marker() { sed -nE 's/^SPLICE_SHIM_VERSION="([^"]+)"$/\1/p' "$1"; }
health() { curl -fsS -m 5 "$CONTROL/health"; }
health_field() { health | bun "$LIB_TS" field - "$1"; }

versions() {
  local old new
  old=$(jar_release "$FROM_JAR") && new=$(jar_release "$CANDIDATE_JAR") || return 1
  [ "$old" = "$FROM_VERSION" ] && [ "$new" = "$CANDIDATE_VERSION" ] && [ "$old" != "$new" ] || return 1
  [ -n "$FROM_SHIM" ] && [ -n "$CANDIDATE_SHIM" ] || return 1
  printf '%s (%s) -> %s (%s)\n' "$old" "$FROM_SHIM" "$new" "$CANDIDATE_SHIM"
}

step "Claude Code version matches the splice tested pin" client_version_receipt
stop_if_failed
need "private work and configuration directories" mkdir -p "$PRIVATE" "$WORK" "$CONFIG"
FROM_VERSION=${FROM_TAG#v}
FROM_SHIM=$(shim_marker "$FROM/splice-launch")
CANDIDATE_VERSION=$(jar_release "$CANDIDATE_JAR")
CANDIDATE_SHIM=$(shim_marker "$ARTIFACTS/splice-launch")
need "baseline tag, candidate gateway and shim versions match their artifacts" versions

export MOCK_CHAT_LOG="$CHAT_LOG"
need "loopback Codex and openai-chat mocks up" start_mocks
read_mock_ports
start_subs() {
  MOCK_SUBS_LOG="$SUBS_LOG" MOCK_SUBS_CODEX_ORIGIN="http://127.0.0.1:$CODEX_MOCK_PORT" \
    nohup bun "$REPO/tools/e2e/docker/mock_subs.ts" 0 > "$OUT/mock_subs.out" 2> "$OUT/mock_subs.err" &
  printf '%s\n' "$!" > "$OUT/mock_subs.pid"
  await_port mock_subs.out
}
await_port() {
  for _ in $(seq 1 50); do
    [ ! -s "$OUT/$1" ] || return 0
    sleep 0.2
  done
  echo "$1 did not announce a port"
  return 1
}
need "loopback Grok and Kimi login and turn mocks up" start_subs
need "subscription mock port readable" mock_values subs mock_subs.out port
SUBS=$(mock_field mock_subs.out port)
export GROK_OAUTH_ISSUER="http://127.0.0.1:$SUBS/grok"
export KIMI_OAUTH_HOST="http://127.0.0.1:$SUBS/kimi"
CODEX_REFRESH="http://127.0.0.1:$CODEX_MOCK_PORT/oauth/token"
export CODEX_OAUTH_TOKEN_URL="$CODEX_REFRESH"
unset MOCK_CHAT_API_KEY

seed_topology() {
  # Both RealInfer heads already use the SAME standing prompt. Moving it to their provider must
  # not introduce a prompt to a sibling which did not ask for it (the fix session refuses that move).
  cat > "$TOPOLOGY" <<EOF
[daemon]
control_port = $CONTROL_PORT

[providers.codex]
dialect = "openai-responses"
base_url = "http://127.0.0.1:$CODEX_MOCK_PORT"
auth = { kind = "chatgpt-oauth", file = "$CODEX_MOCK_AUTH_FILE" }
quirks = { store = false, account_id_header = true, cache_key = "first-message-hash", summary_field = true }
[[providers.codex.models]]
id = "gpt-5-codex"
context_window = 272000

[providers.xai]
dialect = "openai-responses"
base_url = "http://127.0.0.1:$SUBS/grok"
auth = { kind = "grok-oauth" }
quirks = { cache_key = "session-id", effort_ceiling = "high", summary_field = true, tool_choice = true, reasoning_cache = false }
[[providers.xai.models]]
id = "grok-4.6"
context_window = 500000

[providers.kimi]
dialect = "anthropic-passthrough"
base_url = "http://127.0.0.1:$SUBS/kimi"
auth = { kind = "kimi-oauth" }
quirks = { mfjs = true, strip_cache_control = true, synthesize_signatures = true, map_thinking_adaptive = true, block_allowlist = ["text", "image", "thinking", "tool_use", "tool_result", "server_tool_use", "web_search_tool_result"] }
[[providers.kimi.models]]
id = "kimi-for-coding"
context_window = 256000

[providers.realinfer]
dialect = "openai-chat"
base_url = "http://127.0.0.1:$CHAT_MOCK_PORT"
local = false
auth = { kind = "api-key", env = "MOCK_CHAT_API_KEY" }
[[providers.realinfer.models]]
id = "mock-chat"
context_window = 220000
[[providers.realinfer.models]]
id = "mock-chat-2"
context_window = 220000
[[providers.realinfer.models]]
id = "mock-chat-2-big"
context_window = 220000

[heads.claudex]
provider = "codex"
port = 3099
discovery_prefix = "claude-codex--"
pinned_model = "gpt-5-codex"
[heads.claudex.claude]
command = "claudex"

[heads.claude-grok]
provider = "xai"
port = 3100
discovery_prefix = "claude-grok--"
pinned_model = "grok-4.6"
[heads.claude-grok.claude]
command = "claude-grok"

[heads.claude-kimi]
provider = "kimi"
port = 3102
discovery_prefix = "claude-kimi--"
pinned_model = "kimi-for-coding"
[heads.claude-kimi.claude]
command = "claude-kimi"

# BEGIN team-managed
[heads.claude-realinfer]
provider = "realinfer"
port = 3101
discovery_prefix = "realinfer--"
pinned_model = "mock-chat"
context_window = $WINDOW
model_slots = { opus = "mock-chat", sonnet = "mock-chat-2-big", haiku = "mock-chat-2" }
system_prompt = "$PROMPT"
system_prompt_mode = "append"
[heads.claude-realinfer.overrides]
maxInflight = "3"
[heads.claude-realinfer.claude]
command = "claude-realinfer"
# END team-managed

[heads.claude-realinfer-fast]
provider = "realinfer"
port = 3105
discovery_prefix = "claude-realinfer--"
pinned_model = "mock-chat-2"
context_window = $WINDOW
system_prompt = "$PROMPT"
system_prompt_mode = "append"
[heads.claude-realinfer-fast.claude]
command = "claude-realinfer-fast"
EOF
}

release_install() {
  local dir="$1" installer="$2"
  cat "$installer" | SPLICE_RELEASE_BASE_URL="file://$dir" bash
}

grok_login() {
  local log="$PRIVATE/grok-login.log" pid found=""
  splice login claude-grok </dev/null > "$log" 2>&1 &
  pid=$!
  for _ in $(seq 1 100); do
    found=$(sed -nE 's/.*[?&]state=([^&[:space:]]+).*/\1/p' "$log" | head -1)
    [ -z "$found" ] || break
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.3
  done
  if [ -z "$found" ] || ! curl -fsS -m 10 -o /dev/null \
      "http://127.0.0.1:56121/callback?code=grok-e2e-code&state=$found"; then
    kill "$pid" 2>/dev/null || true
    echo "released Grok login did not complete its browser callback"
    return 1
  fi
  wait "$pid" && [ -s "$CONFIG/auth/grok.json" ] &&
    [ "$(stat -c %a "$CONFIG/auth/grok.json")" = 600 ]
}

kimi_login() {
  timeout 120 splice login claude-kimi </dev/null > "$PRIVATE/kimi-login.log" 2>&1 &&
    [ -s "$CONFIG/auth/kimi.json" ] && [ "$(stat -c %a "$CONFIG/auth/kimi.json")" = 600 ]
}

store_chat() {
  printf '%s' mock-chat-key | splice key set MOCK_CHAT_API_KEY --stdin > "$PRIVATE/key-set.log" 2>&1 &&
    [ -s "$CONFIG/keys.toml" ] && [ "$(stat -c %a "$CONFIG/keys.toml")" = 600 ]
}

check_serving() { # version shim artifact; also record the serving PID for the following handover
  local current pid fd
  wait_health 60 || return 1
  current=$(health_field version) && [ "$current" = "$1" ] || return 1
  [ "$(health_field wantShimVersion)" = "$2" ] && [ "$(splice version)" = "splice $1" ] || return 1
  [ "$(shim_marker "$HOME/.local/share/splice/splice-launch")" = "$2" ] || return 1
  pid=$(health_field pid) && [[ $pid =~ ^[1-9][0-9]*$ ]] || return 1
  for fd in /proc/"$pid"/fd/*; do
    case "$(readlink "$fd" 2>/dev/null)" in
      */splice.jar|*/splice.jar\ \(deleted\))
        cmp -s "$fd" "$3" || { echo "serving process $pid has a different jar"; return 1; }
        printf '%s\n' "$pid" > "$PRIVATE/serving.pid"
        api_heads "$HEADS_CSV"
        return $? ;;
    esac
  done
  echo "no open splice.jar in serving process $pid"
  return 1
}

handover() {
  local old
  old=$(cat "$PRIVATE/serving.pid") && check_serving "$@" || return 1
  [ "$(cat "$PRIVATE/serving.pid")" != "$old" ] && ! kill -0 "$old" 2>/dev/null ||
    { echo "the former serving process $old was not replaced"; return 1; }
}

desktop_baseline() {
  # The published verb may print settings with its key; do not put that output in the receipt.
  splice desktop on </dev/null > "$PRIVATE/desktop-on.log" 2>&1 &&
    [ -s "$STATE/desktop.json" ] && [ -s "$STATE/desktop-key" ] || return 1
  if [ "$FROM_VERSION" = 0.6.4 ]; then
    splice claude door </dev/null > "$PRIVATE/claude-door.log" 2>&1 &&
      [ -s "$STATE/claude-lane.json" ] || return 1
  fi
  bun "$HELPER" turn "$CONTROL/desktop" "$STATE/desktop-key" "baseline-desktop" "${MODELS[0]}" "${REPLIES[0]}"
}

save_old_recipe() {
  curl_mgmt -f -X POST -H 'Content-Type: application/json' --data \
    '{"dangerouslySkipPermissions":"","args":["-p","Rehearse the next turn.","--output-format","text"]}' \
    "$CONTROL/launch/claudex" > "$PRIVATE/recipe-old.json"
}

replay_old() {
  bun "$HELPER" legacy-auth "$PRIVATE/recipe-old.json" &&
    bun "$LIB_TS" replay "$PRIVATE/recipe-old.json" "${REPLIES[0]}"
}

snapshot() {
  local f
  [ -s "$TRANSCRIPT" ] || { echo "old transcript missing at $TRANSCRIPT"; return 1; }
  cp "$TRANSCRIPT" "$PRIVATE/transcript.before" || return 1
  for f in "$CONFIG/auth/grok.json" "$CONFIG/auth/kimi.json" "$CONFIG/keys.toml" \
      "$STATE/mgmt-key" "$STATE/desktop-key" "$STATE/config.json" "$TOPOLOGY" "$TRANSCRIPT"; do
    [ -s "$f" ] || { echo "expected old file $f"; return 1; }
  done
  (cd "$HOME" && {
    # manager/ Markdown ships with each jar; init refreshes it by design. Settings and credentials are not managed docs.
    find .config/splice -type f ! -path '.config/splice/manager/*.md' -print0
    printf '%s\0' "${STATE#"$HOME/"}"/{mgmt-key,desktop-key,config.json} "${TRANSCRIPT#"$HOME/"}"
  } | sort -z | xargs -0 sha256sum) > "$PRIVATE/unchanged.sums" || return 1
  grep -x -e '# BEGIN team-managed' -e '# END team-managed' "$TOPOLOGY" > "$PRIVATE/markers.before"
}

unchanged() { (cd "$HOME" && sha256sum -c --strict "$PRIVATE/unchanged.sums"); }
patch_knob() {
  curl_mgmt -f -X PATCH -H 'Content-Type: application/json' --data '{"maxQueued":"64"}' \
    "$CONTROL/api/config" > "$PRIVATE/patch.json" && grep -q '"maxQueued"' "$STATE/config.json"
}

wrapper_at() { local i="$1"; shift; (cd "$WORK" && wrapper_turn "${HEADS[i]}" "${REPLIES[i]}" "$@"); }
wrapper_exists() { local n; for n in "${HEADS[@]}"; do [ -x "$HOME/.local/bin/$n" ] || { echo "$n is not linked"; return 1; }; done; }

need "write the released five-head topology with the team-managed block" seed_topology
need "install $FROM_TAG through its own piped release installer" release_install "$FROM" "$FROM/install.sh"
need "old installer links all five wrappers" wrapper_exists
need "old CLI stores the chat key, with no key set in the daemon environment" store_chat
need "old CLI writes grok.json through browser OAuth" grok_login
need "old CLI writes kimi.json through device OAuth" kimi_login
need "start the published daemon" splice restart
need "published daemon serves all five heads on its own jar and shim" check_serving "$FROM_VERSION" "$FROM_SHIM" "$FROM_JAR"
need "old Desktop sets its own key and serves a turn, preserving the Claude lane where available" desktop_baseline
need "old control API persists maxQueued in config.json" patch_knob
need "save the launch recipe of a session open before the upgrade" save_old_recipe
need "the saved recipe completes a turn on the old daemon before upgrade" replay_old
need "old claudex answers and writes fixed session $SID" wrapper_at 0 --session-id "$SID"
for i in 1 2 3 4; do need "old ${HEADS[i]} completes a turn" wrapper_at "$i"; done
# Capture the pre-upgrade usage and perf the real wrapper turns generated.
old_readings() {
  curl_mgmt -f "$CONTROL/api/usage" | bun "$LIB_TS" usage-head claude-kimi 1 42 "record:$PRIVATE/kimi-before" &&
    bun "$LIB_TS" perf-rows "$STATE/claudex-perf.jsonl" 1
}
need "published daemon wrote and serves quota and perf readings" old_readings
need "snapshot every existing config file, credentials and the fixed transcript" snapshot

links() {
  [ "$(readlink "$RELEASES/current")" = "$1" ] && [ "$(readlink "$RELEASES/previous")" = "$2" ] &&
    cmp -s "$RELEASES/$FROM_VERSION/splice.jar" "$FROM_JAR" &&
    cmp -s "$RELEASES/$CANDIDATE_VERSION/splice.jar" "$CANDIDATE_JAR" ||
    { echo "release pointers or archived jar bytes do not match ($1 / $2)"; return 1; }
  printf 'current=%s previous=%s\n' "$1" "$2"
}

installed_only() {
  [ "$(health_field pid)" = "$(cat "$PRIVATE/serving.pid")" ] &&
    [ "$(health_field version)" = "$FROM_VERSION" ] && [ "$(splice version)" = "splice $CANDIDATE_VERSION" ] &&
    links "$CANDIDATE_VERSION" "$FROM_VERSION"
}

rollback() { splice upgrade --rollback </dev/null; }
start_release_http() {
  nohup bun "$HELPER" serve "$ARTIFACTS" "$HTTP_LOG" > "$OUT/mock_release.out" 2> "$OUT/mock_release.err" &
  printf '%s\n' "$!" > "$OUT/mock_release.pid"
  await_port mock_release.out
}
upgrade_http() {
  local before=0
  [ ! -f "$HTTP_LOG" ] || before=$(wc -l < "$HTTP_LOG")
  SPLICE_RELEASE_BASE_URL="http://127.0.0.1:$RELEASE_PORT/candidate" \
    splice upgrade --to "v$CANDIDATE_VERSION" </dev/null || return 1
  tail -n "+$((before + 1))" "$HTTP_LOG" > "$PRIVATE/last-download.log" || return 1
  local f
  for f in sha256sums.txt "${CANDIDATE_JAR##*/}" splice-launch; do
    grep -Fxq "$f" "$PRIVATE/last-download.log" || { echo "HTTP did not serve $f"; return 1; }
  done
}

INSTALLER="$ARTIFACTS/install.sh"
[ -f "$INSTALLER" ] || INSTALLER="$REPO/install.sh"
need "A: candidate installer reads the verified file release through a real stdin pipe" release_install "$ARTIFACTS" "$INSTALLER"
need "installer activated files without replacing the live old daemon" installed_only
need "A: installed files preserve every old config, credential and transcript byte" unchanged
need "A: first wrapper turn requests the installer handover" wrapper_at 3
need "A: first wrapper replaced old daemon with ready candidate on expected shim and bytes" handover \
  "$CANDIDATE_VERSION" "$CANDIDATE_SHIM" "$CANDIDATE_JAR"
retained_readings() {
  curl_mgmt -f "$CONTROL/api/usage" | bun "$LIB_TS" usage-head claude-kimi 1 42 "record:$PRIVATE/kimi-candidate" &&
    cmp -s "$PRIVATE/kimi-before" "$PRIVATE/kimi-candidate" &&
    bun "$LIB_TS" perf-rows "$STATE/claudex-perf.jsonl" 1
}
need "before any new Kimi or Codex turn, the candidate serves the old readings" retained_readings
need "return through candidate rollback so the old CLI can drive B" rollback
need "rollback replaced the daemon with the published baseline version and shim" handover "$FROM_VERSION" "$FROM_SHIM" "$FROM_JAR"
need "rollback pointers and both archived jars are correct" links "$FROM_VERSION" "$CANDIDATE_VERSION"
need "rolled-back Grok wrapper completes a real turn" wrapper_at 1
need "loopback HTTP release server starts inside the offline container" start_release_http
need "candidate HTTP release port readable" mock_values release mock_release.out port
RELEASE_PORT=$(mock_field mock_release.out port)
need "B: the old installed splice upgrade fetches and verifies the candidate over loopback HTTP" upgrade_http
need "B: the old CLI replaced its live daemon; candidate version, shim and all heads ready" handover \
  "$CANDIDATE_VERSION" "$CANDIDATE_SHIM" "$CANDIDATE_JAR"
need "CLI upgrade pointers and both archived jars are correct" links "$CANDIDATE_VERSION" "$FROM_VERSION"
need "B: candidate still preserves the old config, credential and transcript bytes" unchanged
need "CLI-upgraded candidate rolls back again" rollback
need "second rollback serves published baseline version and shim" handover "$FROM_VERSION" "$FROM_SHIM" "$FROM_JAR"
need "rolled-back Kimi wrapper completes a real turn" wrapper_at 2
need "old CLI can upgrade forward again over loopback" upgrade_http
need "candidate is serving again after the second CLI handover" handover "$CANDIDATE_VERSION" "$CANDIDATE_SHIM" "$CANDIDATE_JAR"
need "candidate runs its own aftercare explicitly" splice upgrade --aftercare --auto
need "aftercare did not rewrite existing config, credentials or the transcript" unchanged

preflight() { # before or after; never leak the checker text when it contains a value
  local wanted="$1" log="$PRIVATE/check-config.$1"
  splice check-config > "$log" 2>&1 || { echo "check-config exited nonzero"; return 1; }
  if grep -Fq -e "$PROMPT" -e "$WINDOW" -e mock-chat-key "$log"; then
    echo "check-config printed a configured value"
    return 1
  fi
  if [ "$wanted" = before ]; then
    local k
    for k in discovery_prefix context_window model_slots system_prompt system_prompt_mode overrides.maxInflight; do
      grep -Fq "heads.claude-realinfer.$k" "$log" || { echo "no warning naming $k"; return 1; }
    done
  else
    ! grep -q 'warning:' "$log" || { echo "check-config still warns"; return 1; }
  fi
  cat "$log"
}

resume_old() {
  wrapper_at 0 --resume "$SID" &&
    bun "$LIB_TS" appended "$PRIVATE/transcript.before" "$TRANSCRIPT"
}

launch_door() {
  local out
  out=$(cd "$WORK" && DISABLE_AUTOUPDATER=1 CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1 \
    timeout 120 splice launch claude -p "Rehearse a door turn." --output-format text </dev/null 2>&1) || {
      printf '%s\n' "$out"; return 1;
    }
  printf '%s\n' "$out"
  grep -Eq 'ok after auth|END|KIMI OK' <<<"$out"
}

door_turns() { # route and file-held key; assert the same session's explicit selector and tier routes
  local path="$1" key="$2" i session="upgrade-064-${1#/}"
  for i in 0 1 2 3 4; do
    bun "$HELPER" turn "$CONTROL$path" "$key" "$session" "${MODELS[i]}" "${REPLIES[i]}" || return 1
  done
  local -a lane=()
  [ "$path" != /door ] || lane=("$STATE/claude-lane.json")
  bun "$HELPER" realinfer "$CONTROL$path" "$key" "$session" "$CHAT_LOG" "$PROMPT" "$WINDOW" "${lane[@]}"
}

fix_config() {
  # A finite series of actual menu choices in a PTY, not an editor replacing the file for the CLI.
  local log="$PRIVATE/config-fix.log"
  { printf 'm\n%.0s' {1..16}; printf 'q\n'; } |
    script -q -e -c 'splice check-config --fix' /dev/null > "$log" 2>&1 || {
      echo "interactive check-config --fix failed; no fix was substituted"; return 1;
    }
  grep -Fq 'moving ' "$log" || { echo "check-config --fix did not perform a move"; return 1; }
  grep -x -e '# BEGIN team-managed' -e '# END team-managed' "$TOPOLOGY" |
    cmp - "$PRIVATE/markers.before" || { echo "the marker lines changed"; return 1; }
  bun "$HELPER" fixed "$TOPOLOGY" "$PROMPT" "$WINDOW"
}

retire_restore() {
  local link="$HOME/.local/bin/claude-realinfer-fast" cfg="$HOME/.claude-claude-realinfer-fast/settings.json" target
  target=$(readlink "$link") && cp "$cfg" "$PRIVATE/fast-settings.before" || return 1
  splice claude retire claude-realinfer-fast </dev/null &&
    [ ! -e "$link" ] && [ ! -e "$cfg" ] &&
    splice claude restore claude-realinfer-fast </dev/null &&
    [ "$(readlink "$link")" = "$target" ] && cmp -s "$cfg" "$PRIVATE/fast-settings.before" &&
    wrapper_at 4
}

doctor_green() {
  local out
  out=$(splice doctor 2>&1 | strip_ansi) || { printf '%s\n' "$out"; return 1; }
  printf '%s\n' "$out"
  ! grep -q '✗' <<<"$out"
}
status_all() {
  local out name
  out=$(splice status 2>&1 | strip_ansi) && grep -Fq "daemon running on $CONTROL_PORT" <<<"$out" || return 1
  printf '%s\n' "$out"
  for name in "${HEADS[@]}"; do grep -Eq "(^|[[:space:]])$name([[:space:]]|$)" <<<"$out" || return 1; done
}

need "check-config exits zero, warns on the old fields and exposes no configured values" preflight before
need "old persisted maxQueued is in effect on the candidate" bun "$HELPER" knob "$CONTROL" "$STATE/mgmt-key"
need "candidate still serves the old Kimi quota and Codex perf rows" old_readings
need "all five named wrappers survive the upgrade without automatic retirement" wrapper_exists
step "the original recipe still turns before relaunching its own head" replay_old
for i in 0 1 2 3 4; do step "candidate ${HEADS[i]} completes a real turn" wrapper_at "$i"; done
step "the original recipe still turns after relaunching its own head" replay_old
step "real Claude Code resumes fixed SID and appends after the old transcript bytes" resume_old
step "splice launch claude runs a real turn on the door" launch_door
step "terminal door routes every provider, preserves prefixes, maps tiers and applies prompt and window" \
  door_turns /door "$STATE/terminal-key"
step "legacy Desktop endpoint with its old key routes every provider, tiers, prompt and window" \
  door_turns /desktop "$STATE/desktop-key"
need "interactive config fix migrates provider fields and keeps both marker lines byte-identical" fix_config
need "migrated splice.toml passes a clean check-config" preflight after
need "restart on the migrated topology" splice restart
need "migrated config boots all original heads on the candidate" handover "$CANDIDATE_VERSION" "$CANDIDATE_SHIM" "$CANDIDATE_JAR"
for i in 0 1 2 3 4; do need "after config fix ${HEADS[i]} still completes a real turn" wrapper_at "$i"; done
step "after config fix the door still routes all providers, tiers and migrated values" door_turns /door "$STATE/terminal-key"
step "retiring and restoring the sibling restores its command and settings byte-for-byte" retire_restore
step "splice doctor reports zero failures" doctor_green
step "splice status reports the running daemon and every original head" status_all
DONE=1
