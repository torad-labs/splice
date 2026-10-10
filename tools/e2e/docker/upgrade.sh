#!/usr/bin/env bash
# tools/e2e/docker/upgrade.sh — the upgrade rehearsal, run INSIDE the container by
# `run.sh --upgrade-from vX.Y.Z`.
#
# A user on a published release upgrades by re-running the install one-liner (releases before 0.4.0
# have no `splice upgrade`), with their daemon running, sessions open and transcripts on disk. This
# rehearses that on a machine that has USED the old release: /from holds the old release's own
# assets, /artifacts the candidate.
#
# THE HOME THE OLD RELEASE LEAVES BEHIND IS THE POINT, and it is built BY THE OLD RELEASE wherever
# the old release writes that file itself (splice-builder, 2026-10-10: the rehearsal used to seed a
# three-head TOML, one API key, one ChatGPT credential, one transcript and the management key, so
# every other thing a real 0.3.x home holds was upgraded by nobody and read by nothing). What 0.3.2
# writes here now: a Grok credential through its own browser OAuth flow and a Kimi credential
# through its own device flow (both against the loopback vendor mock, tools/e2e/docker/mock_subs.ts,
# which checks the credential on every upstream request); two keys in keys.toml; a knob in
# <state>/config.json through its own control API; quota readings and per-turn perf rows from real
# turns on three heads; and a head's sessions/ and projects/ trees. Two files no release mints on
# any path are written here as the vendor's own tooling leaves them — the Kimi device identity and a
# code-mode file from the 0.3.2 beta — and the claim on those is that the upgrade carries them and
# never rotates them. Each one has its own receipt step after the upgrade, because a file that
# survives an install and is then read by nothing is not an upgrade.
#
# The old release installs with its own install.sh, boots, signs in, stores
# keys, serves real print-mode turns through the Claude Code wrappers (a transcript under a
# fixed session id) and hands out a launch recipe. The candidate's install.sh then runs over it in
# RELEASE mode (piped to bash, reading the release from a file:// base, since the container has no
# network), and each upgrade claim the release notes make is its own receipt step:
#   - install.sh leaves the running daemon alone and keeps the old release as the rollback target;
#   - the first launch replaces the stale daemon: a new process holding the candidate jar, /health
#     reporting the candidate's version;
#   - config and credentials are byte-identical, and a pre-0.4 state root stays where it was, with
#     the same management key;
#   - a session launched under the old release keeps working: its launch env, replayed, runs a turn;
#   - a relaunched session holds a turn key, never the management key, and no key sits in
#     settings.json or the status line; <state>/turn-auth-header is 0600;
#   - the pre-upgrade transcript is byte-identical where the head reads it, and it resumes;
#   - rotation: with the key file deleted `splice restart` refuses by name; deleted, stopped and
#     restarted, a fresh key is minted, the old one refused, and the header follows on the next launch;
#   - every 0.3.2 credential still signs in: the Grok turn's upstream request carries the bearer the
#     0.3.2 login stored, the Kimi turn's carries that credential's x-api-key with the five X-Msh-*
#     device headers, and both files (and the device identity) are byte-identical afterwards;
#   - the 0.3.2 readings are served, not just kept: /api/usage reports the Kimi head's quota window
#     observed before the install, `splice perf` reports the rows the 0.3.2 turns wrote, and the knob
#     stored in <state>/config.json is still in effect;
#   - the 0.3.2 code-mode file is carried into one file per conversation and removed;
#   - projects: the 0.3.2 transcript is merged into ~/.claude/projects by hard link (same inode) and
#     the head's own path is the link to it;
#   - a bare `-c` starts a NEW session, since a session 0.3.x began is not in the head's registry,
#     while `-r <id>` resumes it (CHANGELOG, Upgrading from 0.3.x);
#   - status and uninstall.
#
# Keys are compared with cmp against copies in a 0700 directory under $HOME — never printed, never
# written under /out (run.sh copies /out into the checkout's receipts).
set -uo pipefail

RECEIPT_KIND="splice-upgrade-e2e"
RECEIPT_LABEL="UPGRADE E2E"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
trap finish EXIT

FROM="${FROM:-/from}"
FROM_TAG="${SPLICE_UPGRADE_FROM:-}"
CONFIG="$HOME/.config/splice"
AUTH="$CONFIG/auth"
# The two subscription heads a 0.3.2 user had, on the ports the 0.3.2 example config gives them.
GROK_HEAD_PORT=3100
KIMI_HEAD_PORT=3102
ALL_HEADS="claudex,mockchat,mockchat2,claude-grok,claude-kimi"
SUBS_PORT=""
# The vendor mock's request log holds no token VALUE (mock_subs.ts): every credential is a label.
SUBS_LOG="$PRIVATE/mock_subs.jsonl"
# The authorization code and the loopback redirect port xAI's own CLI flow uses
# (GrokOAuthEndpoints.REDIRECT_PORT), which is what `splice login` binds and waits on.
GROK_CODE="grok-e2e-code"
GROK_REDIRECT_PORT=56121
# The code-mode conversation the 0.3.2 file holds, named so the carried per-conversation file can be
# found by content rather than by the hash its name is.
CODE_MODE_KEY="5e55a0d1-0000-4000-8000-00000000c0de"
# The pre-upgrade session: a fixed id, so the resume after the upgrade names it rather than finds it.
SID="5e55a0d1-0000-4000-8000-000000000001"
WORK="$HOME/work"
# Both releases give the claudex head this config dir (observed on 0.3.2; the candidate's launch
# recipe names it in the head-contract step). Claude Code keeps a transcript at
# <config>/projects/<cwd with every non-alphanumeric as '-'>/<session>.jsonl.
TRANSCRIPT="$HOME/.claude-claudex/projects/$(printf '%s' "$WORK" | sed 's/[^A-Za-z0-9]/-/g')/$SID.jsonl"
PRE04_STATE="$HOME/.claude-codex/state"

jar_version() { java -jar "$1" version 2>/dev/null | sed -n 's/^splice //p' | head -1; }
FROM_VERSION="$(jar_version "$FROM/splice.jar")"
CANDIDATE_VERSION="$(jar_version "$ARTIFACTS/splice.jar")"
in_dir() { cd "$1" && shift && "$@"; }
daemon_pid() { pgrep -u "$(id -un)" -f 'splice.jar daemon' | head -1; }
health_version() { curl -sf -m 3 "http://127.0.0.1:$CONTROL_PORT/health" | bun "$LIB_TS" field - version; }
# Every file splice keeps under ~/.config/splice (splice.toml, keys.toml, auth/…), hashed by path.
config_hashes() { (cd "$CONFIG" && find . -type f | sort | xargs sha256sum); }
launch_recipe_json() { # head args-json  -> the recipe the shim would exec
  curl_mgmt -f -X POST -H 'Content-Type: application/json' \
    --data "{\"dangerouslySkipPermissions\":\"\",\"args\":$2}" "http://127.0.0.1:$CONTROL_PORT/launch/$1"
}

echo "upgrade e2e: ${FROM_TAG:-<unnamed>} ($FROM_VERSION) -> candidate ($CANDIDATE_VERSION), user=$(id -un) home=$HOME"
mkdir -p "$PRIVATE" "$WORK" && chmod 0700 "$PRIVATE"

step "Claude Code version matches the splice tested pin" client_version_receipt

# A candidate that reports the old version can never replace the running daemon: the shim's handshake
# returns when /health's version equals its marker (splice-launch, replaceStaleDaemon), so every
# post-upgrade step below would be testing the OLD daemon. run.sh refuses this up front from the shim
# markers; this is the same check against what the jars themselves report.
versions_differ() {
  echo "from ${FROM_TAG:-<unnamed>}: jar reports ${FROM_VERSION:-<none>}; candidate jar reports ${CANDIDATE_VERSION:-<none>}"
  { [ -n "$FROM_VERSION" ] && [ -n "$CANDIDATE_VERSION" ]; } || { echo "a jar did not report its version"; return 1; }
  [ "${FROM_TAG#v}" = "$FROM_VERSION" ] || { echo "/from is not $FROM_TAG: its jar reports $FROM_VERSION"; return 1; }
  [ "$FROM_VERSION" != "$CANDIDATE_VERSION" ] ||
    { echo "the candidate reports $FROM_VERSION too, so the running daemon would never be replaced"; return 1; }
}
step "the release and the candidate report different versions" versions_differ

# ── the subscription vendors, and the topology a 0.3.2 user had ───────────────────────────────
# One loopback process serves both vendors' sign-in surfaces and the Kimi head's upstream, and
# proxies the Grok head's upstream to the vendored responses mock once it has checked the bearer, so
# nothing here re-implements a dialect (mock_subs.ts). It starts AFTER read_mock_ports: the proxy
# target is the responses mock's own port.
start_subs_mock() {
  MOCK_SUBS_LOG="$SUBS_LOG" MOCK_SUBS_CODEX_ORIGIN="http://127.0.0.1:$CODEX_MOCK_PORT" \
    nohup bun "$REPO/tools/e2e/docker/mock_subs.ts" 0 > "$OUT/mock_subs.out" 2> "$OUT/mock_subs.err" &
  echo $! > "$OUT/mock_subs.pid"
  for _ in $(seq 1 50); do
    [ -s "$OUT/mock_subs.out" ] && break
    sleep 0.2
  done
  [ -s "$OUT/mock_subs.out" ] || { echo "subs mock did not start: $(cat "$OUT/mock_subs.err")"; return 1; }
  cat "$OUT/mock_subs.out"
}
read_subs_port() { # in the MAIN shell, after start_subs_mock
  SUBS_PORT="$(mock_field mock_subs.out port 2>/dev/null)"
  echo "  subs mock :$SUBS_PORT (log $SUBS_LOG)"
}

# The three e2e heads (lib.sh, shared with the fresh machine) PLUS the two subscription heads, copied
# from the 0.3.2 release's own config/splice.example.toml ([providers.xai], [providers.kimi] and
# their heads) with every upstream and sign-in endpoint moved to the loopback mock. Written before
# install.sh, so `splice init` keeps it and `install --all` links all five commands.
seed_topology() {
  write_topology > /dev/null || return 1
  cat >> "$CONFIG/splice.toml" <<EOF

# ── what a 0.3.2 user had signed in to: a Grok subscription and a Kimi subscription ──
[providers.xai]
dialect = "openai-responses"
base_url = "http://127.0.0.1:$SUBS_PORT/grok"
auth = { kind = "grok-oauth" }
quirks = { cache_key = "session-id", effort_ceiling = "high", summary_field = true, tool_choice = true, reasoning_cache = false }
[[providers.xai.models]]
id = "grok-4.6"
label = "Grok (mock)"
context_window = 500000

[providers.kimi]
dialect = "anthropic-passthrough"
base_url = "http://127.0.0.1:$SUBS_PORT/kimi"
auth = { kind = "kimi-oauth" }
quirks = { mfjs = true, strip_cache_control = true, synthesize_signatures = true, map_thinking_adaptive = true, block_allowlist = ["text", "image", "thinking", "tool_use", "tool_result", "server_tool_use", "web_search_tool_result"] }
[[providers.kimi.models]]
id = "kimi-for-coding"
label = "Kimi (mock)"
context_window = 256000

[heads.claude-grok]
provider = "xai"
port = $GROK_HEAD_PORT
discovery_prefix = "claude-grok--"
pinned_model = "grok-4.6"
[heads.claude-grok.claude]
command = "claude-grok"

[heads.claude-kimi]
provider = "kimi"
port = $KIMI_HEAD_PORT
discovery_prefix = "claude-kimi--"
pinned_model = "kimi-for-coding"
[heads.claude-kimi.claude]
command = "claude-kimi"
EOF
  cat "$CONFIG/splice.toml"
}

# Both vendors' endpoints are env-overridable in BOTH releases (GrokOAuthEndpoints.issuer /
# KimiOAuthEndpoints.host), and 0.4.0 honours an override only on loopback (LoopbackOverride), which
# these are. The token URLs derive from these two, so no URL here is spelled as a literal — the CI
# secret-pattern pass reads a `*_TOKEN_URL="http…"` assignment as credential-shaped.
export_subs_env() { # in the MAIN shell, after read_subs_port
  GROK_ISSUER="http://127.0.0.1:$SUBS_PORT/grok"
  export GROK_OAUTH_ISSUER="$GROK_ISSUER"
  export KIMI_OAUTH_HOST="http://127.0.0.1:$SUBS_PORT/kimi"
}

step "mock upstreams up" start_mocks
read_mock_ports
step "the subscription vendors' mock up (Grok sign-in, Kimi sign-in, Kimi upstream, Grok proxy)" start_subs_mock
read_subs_port
step "topology written: the three e2e heads plus the Grok and Kimi heads a 0.3.2 user had" seed_topology
export_mock_env
export_subs_env

# ── 1. the old release in use ─────────────────────────────────────────────────────────────────
step "install $FROM_TAG with its own install.sh: checksums verified, wrappers linked" install_step "$FROM"

subs_wrappers() {
  for command in claude-grok claude-kimi; do
    [ -x "$HOME/.local/bin/$command" ] || { echo "$command wrapper not linked by $FROM_TAG"; return 1; }
  done
  ls -l "$HOME/.local/bin/claude-grok" "$HOME/.local/bin/claude-kimi"
}
step "$FROM_TAG linked the two subscription heads' commands too" subs_wrappers

# THE OLD RELEASE'S OWN BROWSER FLOW, driven the way a browser drives it. The image has no browser
# (no xdg-utils), so LoginIo.openBrowser fails and `splice login` PRINTS the authorize URL and waits
# on the loopback callback it bound; there is no console either, so the paste fallback is a no-op.
# This reads the `state` out of that printed URL and makes the callback request xAI's redirect would
# have made. The code exchange then goes to the vendor mock over the env-overridden issuer, and the
# credential file is written by 0.3.2, not by this harness.
grok_login() {
  local out="$PRIVATE/grok-login.out" pid state=""
  : > "$out"
  splice login claude-grok </dev/null > "$out" 2>&1 &
  pid=$!
  for _ in $(seq 1 100); do
    state="$(sed -nE 's/.*[?&]state=([^&[:space:]]+).*/\1/p' "$out" | head -1)"
    [ -n "$state" ] && break
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.3
  done
  if [ -z "$state" ]; then
    echo "no authorize URL with a state parameter from \`splice login claude-grok\`:"
    sed -E 's/(state|code_challenge)=[^&[:space:]]*/\1=<withheld>/g' "$out" | tail -20
    kill "$pid" 2>/dev/null
    return 1
  fi
  curl -sS -m 10 -o /dev/null "http://127.0.0.1:$GROK_REDIRECT_PORT/callback?code=$GROK_CODE&state=$state" ||
    { echo "the login's loopback listener refused the redirect"; kill "$pid" 2>/dev/null; return 1; }
  wait "$pid" || { echo "splice login claude-grok exited nonzero:"; tail -20 "$out"; return 1; }
  grep -vE 'state=|code=|code_challenge=' "$out" | tail -8
  [ -s "$AUTH/grok.json" ] || { echo "no credential at $AUTH/grok.json"; return 1; }
  [ "$(stat -c %a "$AUTH/grok.json")" = "600" ] || { echo "grok.json is $(stat -c %a "$AUTH/grok.json")"; return 1; }
  echo "$FROM_TAG wrote $AUTH/grok.json (0600) itself, from its own browser flow"
}
step "$FROM_TAG signs in to Grok through its own browser flow (loopback callback, code exchange)" grok_login

# The RFC 8628 device flow needs no browser and no console: the mock answers `authorization_pending`
# once, so 0.3.2's poller runs its normal path, and then issues the tokens.
kimi_login() {
  local out="$PRIVATE/kimi-login.out"
  if ! timeout 120 splice login claude-kimi </dev/null > "$out" 2>&1; then
    echo "splice login claude-kimi failed:"
    tail -20 "$out"
    return 1
  fi
  tail -8 "$out"
  [ -s "$AUTH/kimi.json" ] || { echo "no credential at $AUTH/kimi.json"; return 1; }
  [ "$(stat -c %a "$AUTH/kimi.json")" = "600" ] || { echo "kimi.json is $(stat -c %a "$AUTH/kimi.json")"; return 1; }
  echo "$FROM_TAG wrote $AUTH/kimi.json (0600) itself, from its own device flow"
}
step "$FROM_TAG signs in to Kimi through its own device flow (pending poll, then tokens)" kimi_login

# THE TWO FILES NO RELEASE MINTS ON ANY PATH IT RUNS HERE. KimiDeviceIdentity.deviceId() persists the
# uuid Kimi binds a session to, and nothing in 0.3.2 or 0.4.0 calls it during a login or a turn — the
# file comes from the vendor's own tooling (a home that shares ~/.kimi via auth.file) or from a later
# splice that asks for it. Both paths a release WOULD read are seeded: beside the credential
# (KimiOAuth, the refresh path) and in the state dir (the passthrough arm's per-head copy). The claim
# on them is DR-59's: an upgrade never rotates a device identity, so they must come out byte-identical.
seed_device_identity() {
  local state uuid="a1b2c3d4-0000-4000-8000-00000000d1d1"
  state="$(resolve_state_dir)"
  ( umask 077 && printf '%s' "$uuid" > "$AUTH/device_id" ) || return 1
  ( umask 077 && printf '%s' "$uuid" > "$state/claude-kimi-device_id" ) || return 1
  ls -l "$AUTH/device_id" "$state/claude-kimi-device_id"
  echo "the Kimi device identity is in both places a release reads it (0600)"
}
step "the Kimi device identity exists beside the credential and in the state dir" seed_device_identity

step "$FROM_TAG cold start: /health ok, every head ready" cold_start
step "$FROM_TAG /api/heads lists all five heads running" api_heads "$ALL_HEADS"

subs_head_ports() {
  ss -ltn | grep -E ":($GROK_HEAD_PORT|$KIMI_HEAD_PORT) " ||
    { echo "the Grok and Kimi heads are not both listening"; return 1; }
}
step "$FROM_TAG serves the two subscription heads on their own ports" subs_head_ports

# Why the release notes send a 0.3.x user to the install one-liner: the verb does not exist yet.
no_upgrade_verb() {
  local out rc
  out="$(splice upgrade </dev/null 2>&1)"; rc=$?
  printf '%s\n' "$out" | head -5
  [ $rc -ne 0 ] || { echo "$FROM_TAG accepted \`splice upgrade\`"; return 1; }
  ! grep -q 'upgrade' <<<"$out" || { echo "$FROM_TAG names an upgrade verb"; return 1; }
  echo "$FROM_TAG has no upgrade verb (exit $rc)"
}
step "$FROM_TAG has no \`splice upgrade\`: the install one-liner is the upgrade path" no_upgrade_verb

# Two keys, as a home that has tried more than one api-key head holds: the chat heads' key, and the
# key of a head the user has since removed from splice.toml — which the upgrade must keep too, since
# the store is the operator's, not the topology's.
store_api_keys() {
  printf '%s' "mock-chat-key" | splice key set MOCK_CHAT_API_KEY --stdin || return 1
  printf '%s' "openrouter-key-from-0-3-2" | splice key set OPENROUTER_API_KEY --stdin || return 1
  [ "$(stat -c %a "$CONFIG/keys.toml")" = "600" ] || { echo "keys.toml is $(stat -c %a "$CONFIG/keys.toml")"; return 1; }
  local names
  names="$(splice key list 2>&1 | strip_ansi)"
  printf '%s\n' "$names"
  for name in MOCK_CHAT_API_KEY OPENROUTER_API_KEY; do
    grep -q "$name" <<<"$names" || { echo "$name is not in the key store"; return 1; }
  done
  echo "two keys stored in $CONFIG/keys.toml (0600); neither value printed"
}
step "$FROM_TAG stores two API keys in keys.toml (credentials the upgrade must keep)" store_api_keys

# The recipe a session launched now would run under: kept, so the same env can be replayed after the
# upgrade, which is exactly what an already-running Claude Code process still holds.
old_session_recipe() {
  launch_recipe_json claudex '["-p","Count from 1 to 3 then say END.","--output-format","text"]' \
    > "$PRIVATE/recipe-old.json" || { echo "no launch recipe from $FROM_TAG"; return 1; }
  chmod 0600 "$PRIVATE/recipe-old.json"
  bun "$LIB_TS" recipe-plants-mgmt "$PRIVATE/recipe-old.json" "$(resolve_state_dir)/mgmt-key"
}
step "$FROM_TAG launch recipe kept: its session env carries the management key" old_session_recipe

# The vendored codex mock answers every basic turn with "ok after auth"; the chat mock ends in END.
step "$FROM_TAG wrapper turn: claudex -p writes session $SID" \
  in_dir "$WORK" wrapper_turn claudex "ok after auth" --session-id "$SID"

# The two subscription heads, each on the credential 0.3.2 just wrote. The vendor mock refuses any
# other bearer with a 401, so a turn that completes IS the credential working: the Grok head's
# request is checked and proxied to the responses mock, and the Kimi head's carries x-api-key (what
# api.kimi.com/coding takes, never a bearer) and comes back with the unified rate-limit headers that
# leave a real quota reading on disk.
step "$FROM_TAG turn on the Grok head, on the credential its own login stored" \
  in_dir "$WORK" wrapper_turn claude-grok "ok after auth"
step "$FROM_TAG turn on the Kimi head, on the credential its own login stored" \
  in_dir "$WORK" wrapper_turn claude-kimi "KIMI OK"

# A knob the operator changed, stored where 0.3.2 stores it: <state>/config.json, written by its own
# control API (ConfigRoutes.patchConfig). maxQueued is a knob BOTH releases carry.
seed_config_json() {
  local state out
  state="$(resolve_state_dir)"
  out="$(curl_mgmt -f -X PATCH -H 'Content-Type: application/json' --data '{"maxQueued":"64"}' \
    "http://127.0.0.1:$CONTROL_PORT/api/config")" || { echo "the config patch was refused: $out"; return 1; }
  printf '%s\n' "$out" | tail -c 400
  echo
  [ -f "$state/config.json" ] || { echo "no config.json at $state/config.json"; return 1; }
  grep -q '"maxQueued"' "$state/config.json" || { echo "config.json does not hold maxQueued:"; cat "$state/config.json"; return 1; }
  cat "$state/config.json"
}
step "$FROM_TAG stores a knob in its own <state>/config.json" seed_config_json

# A code-mode file from the 0.3.2 beta (`code_mode = true` in the provider's quirks), in the shape
# CodexCodeModeStore.save wrote: ONE file holding the whole head, `version`/`records`/`expired`, with
# metadataVersion 3 — 0.3.2's CODE_MODE_METADATA_VERSION, not the candidate's. Written here rather
# than earned from a turn because the vendored mock never emits a code-mode batch, so no turn against
# it can make the old release write this file. Code mode is ON by default for a ChatGPT head on the
# Responses dialect since 0.4.0, so the candidate's first start loads this file and must carry it.
seed_code_mode() {
  local state file
  state="$(resolve_state_dir)"
  file="$state/claudex-code-mode.json"
  ( umask 077 && cat > "$file" <<EOF
{"version":1,
 "records":[{"id":"cm-0001","key":"$CODE_MODE_KEY",
   "outer":{"type":"function_call","name":"code_mode","call_id":"call_cm_1"},
   "outerCallId":"call_cm_1","source":"const files = await listFiles('.');\nreturn files.length;\n",
   "phase":"COMPLETED","pending":[],
   "results":{"toolu_cm_1":{"output":"3 files","isError":false}},
   "output":"3","error":null,"totalCalls":1,"rounds":1,"updatedAt":$(date +%s)000,
   "lastDigest":"0000000000000000000000000000000000000000000000000000000000000000",
   "baselineInputCount":1,"baselineInputDigest":"seed","metadataVersion":3,
   "baselineLogicalCount":1,"baselineLogicalDigest":"seed"}],
 "expired":[]}
EOF
  ) || return 1
  [ "$(stat -c %a "$file")" = "600" ] || { echo "$file is $(stat -c %a "$file")"; return 1; }
  echo "$file: $(wc -c < "$file") bytes, one COMPLETED conversation ($CODE_MODE_KEY) at metadataVersion 3"
}
step "a 0.3.2 code-mode file for the claudex head, as the old store wrote one" seed_code_mode

# What the seeding produced, read back from disk BEFORE the upgrade: the home now holds what a used
# 0.3.2 install holds. Each count is the floor the post-upgrade steps compare against.
seed_recorded() {
  local state; state="$(resolve_state_dir)"
  bun "$LIB_TS" perf-rows "$state/claudex-perf.jsonl" 1 || return 1
  bun "$LIB_TS" perf-rows "$state/claude-kimi-perf.jsonl" 1 || return 1
  wc -l < "$state/claudex-perf.jsonl" > "$PRIVATE/perf-claudex.pre" || return 1
  [ -f "$state/claude-kimi-quota.json" ] || {
    echo "no quota file at $state/claude-kimi-quota.json; the state dir holds:"
    ls "$state"
    return 1
  }
  cat "$state/claude-kimi-quota.json"; echo
  install -m 0600 "$AUTH/device_id" "$PRIVATE/device_id.pre" || return 1
  install -m 0600 "$AUTH/grok.json" "$PRIVATE/grok.json.pre" || return 1
  install -m 0600 "$AUTH/kimi.json" "$PRIVATE/kimi.json.pre" || return 1
  local sessions="$HOME/.claude-claudex/sessions" projects="$HOME/.claude-claudex/projects"
  [ -L "$sessions" ] || { echo "$sessions is not the shared registry link $FROM_TAG makes"; return 1; }
  echo "$sessions -> $(readlink "$sessions")"
  [ -d "$projects" ] || { echo "$projects is missing"; return 1; }
  echo "$projects holds $(find "$projects" -name '*.jsonl' | wc -l) transcript(s)"
  ls "$state" | sort > "$PRIVATE/state-files.pre"
  cat "$PRIVATE/state-files.pre"
}
step "the seeded 0.3.2 home read back: perf rows, a quota reading, credentials, sessions and projects" seed_recorded

snapshot_before_upgrade() {
  [ -f "$TRANSCRIPT" ] || { echo "no transcript at $TRANSCRIPT"; find "$HOME" -name "$SID*" 2>/dev/null; return 1; }
  cp "$TRANSCRIPT" "$PRIVATE/transcript.pre" || return 1
  echo "transcript $TRANSCRIPT: $(wc -c < "$TRANSCRIPT") bytes"
  local state; state="$(resolve_state_dir)"
  [ "$state" = "$PRE04_STATE" ] || { echo "$FROM_TAG state root is $state, not $PRE04_STATE"; return 1; }
  install -m 0600 "$state/mgmt-key" "$PRIVATE/mgmt-key.pre" || return 1
  echo "state root $state (mode $(stat -c %a "$state")); mgmt-key (mode $(stat -c %a "$state/mgmt-key")) kept for comparison"
  config_hashes > "$PRIVATE/config.sha256" || return 1
  cat "$PRIVATE/config.sha256"
  daemon_pid > "$PRIVATE/daemon.pid"
  [ -s "$PRIVATE/daemon.pid" ] || { echo "no $FROM_TAG daemon process"; return 1; }
  echo "the $FROM_TAG daemon is pid $(cat "$PRIVATE/daemon.pid")"
  # The line the vendor mock's log has reached, which is what makes each credential claim below a
  # claim about the CANDIDATE and not about the 0.3.2 phase, and the second the install runs at,
  # which the receipt carries beside every reading it reports.
  wc -l < "$SUBS_LOG" | tr -d ' ' > "$PRIVATE/subs-lines.pre"
  date +%s > "$PRIVATE/upgrade-at.s"
  echo "the vendor mock has served $(cat "$PRIVATE/subs-lines.pre") request(s); the install runs at $(cat "$PRIVATE/upgrade-at.s")"
}
step "$FROM_TAG state recorded: transcript, management key, config and credentials, daemon pid" snapshot_before_upgrade

# ── 2. the upgrade: the candidate's installer, in release mode, over the running release ────────
# `curl -fsSL …/install.sh | SPLICE_VERSION=vX bash` with the network replaced by a file:// base:
# the same piped, release-mode path (download, checksum, the version check, the atomic swap), with
# only the URL local. install.sh skips the Sigstore attestation for a file:// base and says so.
upgrade_install() {
  local installer="$REPO/install.sh" out rc
  [ -f "$ARTIFACTS/install.sh" ] && installer="$ARTIFACTS/install.sh"
  echo "installer: $installer (piped into bash like the one-liner, release base file://$ARTIFACTS)"
  # A PIPE, as curl gives it: a command inside install.sh that reads stdin would eat the rest of the
  # script here exactly as it would for a user, instead of a file bash can seek back into.
  # shellcheck disable=SC2002  # the pipe is the point, above
  out="$(cat "$installer" | SPLICE_RELEASE_BASE_URL="file://$ARTIFACTS" bash 2>&1)"; rc=$?
  printf '%s\n' "$out" | tail -25
  [ $rc -eq 0 ] || { echo "the release-mode install exited $rc"; return 1; }
  [ "$(splice version 2>/dev/null)" = "splice $CANDIDATE_VERSION" ] || { echo "splice version: $(splice version 2>&1)"; return 1; }
  local rel="$HOME/.local/share/splice/releases"
  echo "releases/current -> $(readlink "$rel/current"); releases/previous -> $(readlink "$rel/previous")"
  [ "$(readlink "$rel/current")" = "$CANDIDATE_VERSION" ] || { echo "releases/current is not $CANDIDATE_VERSION"; return 1; }
  [ "$(readlink "$rel/previous")" = "$FROM_VERSION" ] ||
    { echo "releases/previous is not $FROM_VERSION, so splice upgrade --rollback has no target"; return 1; }
  cmp -s "$rel/$FROM_VERSION/splice.jar" "$FROM/splice.jar" ||
    { echo "releases/$FROM_VERSION/splice.jar is not the $FROM_TAG jar"; return 1; }
  echo "releases/$FROM_VERSION holds the $FROM_TAG jar byte for byte: splice upgrade --rollback has a target"
}
step "the install one-liner (release mode) installs the candidate and keeps $FROM_VERSION as the rollback target" upgrade_install

daemon_left_alone() {
  local old pid version
  old="$(cat "$PRIVATE/daemon.pid")"; pid="$(daemon_pid)"; version="$(health_version)"
  echo "daemon pid $pid (before the install: $old); /health version $version"
  [ "$pid" = "$old" ] || { echo "install.sh replaced or stopped the running daemon"; return 1; }
  [ "$version" = "$FROM_VERSION" ] || { echo "/health reports $version, not the still-running $FROM_VERSION"; return 1; }
}
step "install.sh left the running $FROM_TAG daemon alone" daemon_left_alone

config_untouched() { (cd "$CONFIG" && sha256sum -c "$PRIVATE/config.sha256") && diff <(config_hashes) "$PRIVATE/config.sha256"; }
step "config and credentials are byte-identical after the install (splice.toml, keys.toml)" config_untouched

# The first launch replaces the stale daemon; asserted on the process, not on a version string both
# sides could share: the daemon serving /health must be a new process holding the candidate jar open.
handover() {
  local out rc
  out="$(DISABLE_AUTOUPDATER=1 DISABLE_TELEMETRY=1 DISABLE_ERROR_REPORTING=1 CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1 \
    timeout 120 claudex -p "Count from 1 to 3 then say END." --output-format text </dev/null 2>&1)"; rc=$?
  printf '%s\n' "$out" | tail -c 1500
  [ $rc -eq 0 ] || { echo "wrapper exit $rc"; return 1; }
  grep -qF "ok after auth" <<<"$out" || { echo "the turn did not complete"; return 1; }
  grep -qF "replacing stale daemon $FROM_VERSION with $CANDIDATE_VERSION" <<<"$out" ||
    { echo "the shim did not say it replaced the stale daemon"; return 1; }
  local old pid fd target jar_sha="" want version
  old="$(cat "$PRIVATE/daemon.pid")"; pid="$(daemon_pid)"
  [ -n "$pid" ] || { echo "no daemon process after the launch"; return 1; }
  for fd in /proc/"$pid"/fd/*; do
    target="$(readlink "$fd" 2>/dev/null)"
    case "$target" in */splice.jar*) jar_sha="$(sha256sum < "$fd" | cut -d' ' -f1)"; echo "fd $fd -> $target"; break ;; esac
  done
  want="$(sha256sum < "$ARTIFACTS/splice.jar" | cut -d' ' -f1)"
  version="$(health_version)"
  echo "daemon pid $pid (was $old); /health version $version; open jar ${jar_sha:0:16}…, candidate ${want:0:16}…"
  ! kill -0 "$old" 2>/dev/null || { echo "the $FROM_TAG daemon (pid $old) is still alive"; return 1; }
  [ "$version" = "$CANDIDATE_VERSION" ] || { echo "/health reports $version, not the candidate's $CANDIDATE_VERSION"; return 1; }
  { [ -n "$jar_sha" ] && [ "$jar_sha" = "$want" ]; } ||
    { echo "the serving daemon does not hold the candidate jar open"; return 1; }
}
step "first launch after the upgrade: the shim replaces the $FROM_TAG daemon with the candidate" in_dir "$HOME" handover

heads_back() { wait_health 60 && api_heads "$ALL_HEADS"; }
step "every head is back and running on the candidate" heads_back

state_root_kept() {
  local state key; state="$(resolve_state_dir)"; key="$state/mgmt-key"
  echo "state root $state (mode $(stat -c %a "$state"))"
  [ "$state" = "$PRE04_STATE" ] || { echo "the state root moved to $state"; return 1; }
  [ ! -e "$HOME/.splice/state" ] || { echo "a second state root appeared at ~/.splice/state"; return 1; }
  cmp -s "$PRIVATE/mgmt-key.pre" "$key" || { echo "the mgmt-key at $key is not the one $FROM_TAG minted"; return 1; }
  echo "the mgmt-key at $key is the one $FROM_TAG minted (compared in place, not printed)"
  curl_mgmt -f -o /dev/null "http://127.0.0.1:$CONTROL_PORT/api/heads" || { echo "the candidate daemon rejects it"; return 1; }
  echo "the candidate daemon accepts it on /api/heads"
}
step "the pre-0.4 state root stays at ~/.claude-codex/state with the same management key" state_root_kept

doctor_after_upgrade() {
  local out shim
  shim="$(sed -nE 's/^const SPLICE_SHIM_VERSION = "([^"]+)";$/\1/p' "$ARTIFACTS/splice-launch")"
  out="$(splice doctor 2>&1 | strip_ansi)"
  printf '%s\n' "$out"
  [ -n "$shim" ] || { echo "the candidate shim carries no SPLICE_SHIM_VERSION marker"; return 1; }
  grep -qE "^\s*✓\s+topology\s" <<<"$out" || { echo "doctor does not load the topology"; return 1; }
  grep -qE "^\s*✓\s+shim\s+current \($shim\)" <<<"$out" || { echo "doctor does not report the shim current ($shim)"; return 1; }
  [ "$(grep -v '^splice doctor' <<<"$out" | grep -c '✗')" = 0 ] || { echo "doctor reports a ✗"; return 1; }
}
step "doctor after the upgrade: topology loads, shim current, no ✗" doctor_after_upgrade

# A Claude Code process started before the upgrade keeps the env it was launched with. Replaying the
# kept recipe (its env, its unsets, its argv) is that process asking for its next turn.
old_session_turn() {
  bun "$LIB_TS" replay "$PRIVATE/recipe-old.json" "ok after auth" || return 1
  echo "a session launched under $FROM_TAG still runs a turn on its management key"
}
step "a session launched under $FROM_TAG keeps working: its env (the management key) still runs a turn" in_dir "$HOME" old_session_turn

step "head contract after relaunch: claudex (turn key, 0600 header file, roster, packaging)" \
  head_contract claudex gpt-5-codex 272000 "gpt-5-codex:272000"

# The same relaunch, from the release notes' side: the key lives in <state>/turn-auth-header and
# nowhere a process listing or a settings file would show it.
turn_key_placement() {
  bun "$LIB_TS" turn-key-placement "$PRIVATE/recipe-claudex.json" "$HOME/.claude-claudex/settings.json" "$(resolve_state_dir)"
}
step "after relaunch: turn key in the env, no key in settings.json or the status line, <state>/turn-auth-header 0600" turn_key_placement

transcript_intact() {
  local projects="$HOME/.claude-claudex/projects"
  if [ -L "$projects" ]; then echo "$projects -> $(readlink "$projects")"; else echo "$projects is a directory"; fi
  echo "$TRANSCRIPT resolves to $(readlink -f "$TRANSCRIPT")"
  cmp -s "$PRIVATE/transcript.pre" "$TRANSCRIPT" || { echo "the transcript the head reads is not the pre-upgrade one"; return 1; }
  echo "$(wc -c < "$TRANSCRIPT") bytes, identical to the pre-upgrade transcript"
}
step "the pre-upgrade transcript is byte-identical where the head reads it" transcript_intact

# CHANGELOG, Upgrading from 0.3.x: a session 0.3.x began is not in the head's splice-sessions.json,
# so a bare -c starts a NEW session and leaves that session's transcript alone, while `-r <id>` is
# what resumes it. BEFORE the resume below, which is what puts the session in that registry and
# makes a later -c continue it correctly.
bare_continue_starts_new() {
  local before after
  before="$(find "$HOME/.claude/projects" -name '*.jsonl' | wc -l)"
  wrapper_turn claudex "ok after auth" -c || return 1
  cmp -s "$PRIVATE/transcript.pre" "$TRANSCRIPT" ||
    { echo "a bare -c continued the 0.3.2 session instead of starting one of its own"; return 1; }
  after="$(find "$HOME/.claude/projects" -name '*.jsonl' | wc -l)"
  echo "transcripts in the shared tree: $before before the bare -c, $after after"
  [ "$after" -gt "$before" ] || { echo "a bare -c started no new session"; return 1; }
  echo "the 0.3.2 session's transcript is untouched: a bare -c began a session of its own"
}
step "a bare -c starts a new session and leaves the 0.3.2 session's transcript alone" \
  in_dir "$WORK" bare_continue_starts_new

resume_pre_session() {
  wrapper_turn claudex "ok after auth" --resume "$SID" || return 1
  bun "$LIB_TS" appended "$PRIVATE/transcript.pre" "$TRANSCRIPT"
}
step "the pre-upgrade session resumes on the candidate and appends after its bytes" in_dir "$WORK" resume_pre_session

step "wrapper turn after the upgrade: claude-mockchat -p through its head" in_dir "$HOME" wrapper_turn claude-mockchat "END"

# ── 2b. everything else the 0.3.2 home held, read by the candidate ────────────────────────────
# A file that survives an install and is then read by nothing is not an upgrade, so each of these
# drives the candidate and asserts what the CANDIDATE did with the old release's file.

# FIRST, before any post-upgrade turn on the Kimi head: the only five-hour reading in existence is
# the one the 0.3.2 turns left on disk, so the candidate serving it is retention and nothing else.
# `entries` is the usage file's own turn count, which only a turn writes.
quota_survives() {
  curl_mgmt -f "http://127.0.0.1:$CONTROL_PORT/api/usage" | bun "$LIB_TS" usage-head claude-kimi 1 42
}
step "quota: the candidate serves the reading $FROM_TAG's turns left, before a turn of its own" quota_survives

# And from here the vendor reports a different utilization, so the reading above cannot be confused
# with one the candidate's own turn observes below.
move_vendor_utilization() {
  curl -sS -f -m 10 -X POST "http://127.0.0.1:$SUBS_PORT/control/kimi-utilization?utilization=0.77" ||
    { echo "the vendor mock refused the utilization change"; return 1; }
  echo
}
step "the Kimi upstream starts reporting a different five-hour utilization" move_vendor_utilization

# The vendor mock answers any bearer but the one it issued with a 401, so a turn that completes is
# the credential being read. The log is skipped past the 0.3.2 phase's own lines, so `current` here
# can only be the candidate's request.
grok_reads() {
  wrapper_turn claude-grok "ok after auth" || return 1
  bun "$LIB_TS" subs-seen "$SUBS_LOG" "$(cat "$PRIVATE/subs-lines.pre")" /grok/responses bearer current
}
step "the 0.3.2 Grok credential still signs in: the candidate's upstream request carries its token" \
  in_dir "$WORK" grok_reads

kimi_reads() {
  wrapper_turn claude-kimi "KIMI OK" || return 1
  local skip; skip="$(cat "$PRIVATE/subs-lines.pre")"
  bun "$LIB_TS" subs-seen "$SUBS_LOG" "$skip" /kimi/v1/messages x_api_key current || return 1
  bun "$LIB_TS" subs-seen "$SUBS_LOG" "$skip" /kimi/v1/messages identity complete || return 1
  # The candidate's own turn observed the vendor's new utilization, which is the other half of the
  # retention claim: the 42% above was the kept reading, not whatever the vendor says now.
  curl_mgmt -f "http://127.0.0.1:$CONTROL_PORT/api/usage" | bun "$LIB_TS" usage-head claude-kimi 2 77
}
step "the 0.3.2 Kimi credential still signs in: x-api-key from its file, with the five X-Msh-* headers" \
  in_dir "$WORK" kimi_reads

# Nothing refreshed, so nothing rotated: the credential files and the device identity are the 0.3.2
# bytes. A device identity rewritten by an upgrade would invalidate the session Kimi bound to it.
credentials_identical() {
  local state; state="$(resolve_state_dir)"
  cmp -s "$PRIVATE/grok.json.pre" "$AUTH/grok.json" || { echo "$AUTH/grok.json changed across the upgrade"; return 1; }
  cmp -s "$PRIVATE/kimi.json.pre" "$AUTH/kimi.json" || { echo "$AUTH/kimi.json changed across the upgrade"; return 1; }
  cmp -s "$PRIVATE/device_id.pre" "$AUTH/device_id" || { echo "$AUTH/device_id was rotated"; return 1; }
  cmp -s "$PRIVATE/device_id.pre" "$state/claude-kimi-device_id" ||
    { echo "$state/claude-kimi-device_id was rotated"; return 1; }
  echo "grok.json, kimi.json and both device_id copies are byte-identical (compared in place, never printed)"
  ls -l "$AUTH/device_id" "$state/claude-kimi-device_id"
}
step "the 0.3.2 credentials and the Kimi device identity are byte-identical after the upgrade" credentials_identical

# `splice perf` is the candidate reading the JSONL the old release wrote (PerfRowsFileSource).
perf_survives() {
  local state out floor; state="$(resolve_state_dir)"; floor="$(cat "$PRIVATE/perf-claudex.pre")"
  bun "$LIB_TS" perf-rows "$state/claudex-perf.jsonl" "$floor" || return 1
  out="$(splice perf </dev/null 2>&1 | strip_ansi)" || { printf '%s\n' "$out"; return 1; }
  printf '%s\n' "$out" | head -30
  grep -q 'claudex' <<<"$out" || { echo "splice perf names no claudex rows"; return 1; }
  echo "the candidate reports the $floor or more rows $FROM_TAG's turns wrote"
}
step "perf: the candidate reports the per-turn rows $FROM_TAG's turns wrote" perf_survives

code_mode_carried() {
  local state; state="$(resolve_state_dir)"
  bun "$LIB_TS" code-mode-carried "$state/claudex-code-mode.json" \
    "$state/heads/claudex/code-mode" "$CODE_MODE_KEY"
}
step "code-mode: the 0.3.2 whole-head file is carried into one file per conversation, and removed" code_mode_carried

config_json_survives() {
  local state out; state="$(resolve_state_dir)"
  cat "$state/config.json"; echo
  grep -q '"maxQueued"' "$state/config.json" || { echo "the knob is gone from config.json"; return 1; }
  out="$(curl_mgmt -f "http://127.0.0.1:$CONTROL_PORT/api/config")" || { echo "/api/config failed"; return 1; }
  printf '%s\n' "$out" | tail -c 600; echo
  grep -q '"maxQueued"[: ]*"\?64' <<<"$out" ||
    { echo "the candidate does not report the knob $FROM_TAG stored (maxQueued = 64)"; return 1; }
  echo "the knob $FROM_TAG wrote to <state>/config.json is in effect on the candidate"
}
step "config.json: the knob $FROM_TAG stored is still in effect on the candidate" config_json_survives

# The head's own projects/ was a REAL directory under 0.3.2 (ProjectsLink did not exist yet). On the
# candidate the first launch merges it into the operator's global tree by hard link — the SAME inode,
# so a live writer keeps appending to the same bytes — and replaces the head's path with the link.
projects_merged() {
  local head="$HOME/.claude-claudex/projects" global="$HOME/.claude/projects" encoded merged pre
  encoded="$(printf '%s' "$WORK" | sed 's/[^A-Za-z0-9]/-/g')"
  [ -L "$head" ] || { echo "$head is not the link to the global tree"; return 1; }
  echo "$head -> $(readlink "$head")"
  merged="$global/$encoded/$SID.jsonl"
  [ -f "$merged" ] || {
    echo "the 0.3.2 transcript is not in $global/$encoded; that tree holds:"
    ls -la "$global/$encoded" 2>&1 | head -10
    return 1
  }
  # The merge is by link, not by copy, so the bytes 0.3.2 wrote are the first bytes of the file the
  # global name now points at — whatever every later turn appended after them.
  pre="$(wc -c < "$PRIVATE/transcript.pre")"
  cmp -s -n "$pre" "$PRIVATE/transcript.pre" "$merged" ||
    { echo "the merged transcript does not begin with the $pre bytes $FROM_TAG wrote"; return 1; }
  echo "$merged: $(wc -c < "$merged") bytes, the first $pre of them the 0.3.2 session's"
}
step "projects: the 0.3.2 transcript is merged into ~/.claude/projects, and the head's path is the link" projects_merged


# ── 3. rotating the management key ────────────────────────────────────────────────────────────
# `splice restart` stops a running daemon through its key-authenticated shutdown route
# (RestartCommand.stopIfRunning), so once the key file is gone restart cannot stop it: it refuses and
# names the missing file. The daemon is stopped by its supervisor (`systemctl --user stop
# splice.service`) or by ending its process — no unit runs here, so the process — and then
# `splice restart` starts a daemon that mints a fresh key.
restart_refuses_keyless_stop() {
  local state key out rc
  state="$(resolve_state_dir)"; key="$state/mgmt-key"
  sha256sum < "$state/turn-auth-header" > "$PRIVATE/header.pre-rotation"
  install -m 0600 "$key" "$PRIVATE/mgmt-key.pre-rotation" || return 1
  rm -f "$key"
  out="$(splice restart </dev/null 2>&1)"; rc=$?
  printf '%s\n' "$out"
  [ $rc -ne 0 ] || { echo "restart claimed success against a daemon it had no key to stop"; return 1; }
  grep -qF "mgmt-key not found at $key" <<<"$out" || { echo "restart did not name the missing key"; return 1; }
  kill -0 "$(daemon_pid)" 2>/dev/null || { echo "the refused restart still took the daemon down"; return 1; }
  echo "with the key deleted and the daemon up, restart refuses by name and leaves the daemon serving"
}
step "rotation: with the key deleted, \`splice restart\` alone refuses by name (it has no key to stop the daemon with)" restart_refuses_keyless_stop

rotate_mgmt_key() {
  local state key
  state="$(resolve_state_dir)"; key="$state/mgmt-key"
  pkill -u "$(id -un)" -f 'splice.jar daemon' || { echo "no daemon process to end"; return 1; }
  for _ in $(seq 1 100); do [ -z "$(daemon_pid)" ] && break; sleep 0.2; done
  [ -z "$(daemon_pid)" ] || { echo "the daemon did not exit on SIGTERM"; return 1; }
  echo "daemon process ended"
  splice restart </dev/null || { echo "splice restart failed to start a daemon with no key on disk"; return 1; }
  wait_health 60 || return 1
  [ -f "$key" ] || { echo "no fresh mgmt-key at $key after the restart"; return 1; }
  [ "$(stat -c %a "$key")" = "600" ] || { echo "the fresh mgmt-key is mode $(stat -c %a "$key")"; return 1; }
  ! cmp -s "$PRIVATE/mgmt-key.pre-rotation" "$key" || { echo "the restart put the same key back"; return 1; }
  echo "a fresh mgmt-key was minted at $key (0600, differs from the deleted one; neither printed)"
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' -m 10 -H "Authorization: Bearer $(cat "$PRIVATE/mgmt-key.pre-rotation")" \
    "http://127.0.0.1:$CONTROL_PORT/api/heads")"
  echo "the deleted key now gets HTTP $code on /api/heads"
  [ "$code" = "401" ] || [ "$code" = "403" ] || { echo "the deleted key is still accepted"; return 1; }
  curl_mgmt -f -o /dev/null "http://127.0.0.1:$CONTROL_PORT/api/heads" || { echo "the fresh key is rejected"; return 1; }
  echo "the fresh key is accepted"
}
step "rotation: key deleted, daemon stopped, \`splice restart\` mints a fresh key; the old one is refused" rotate_mgmt_key

header_follows() {
  head_contract claudex gpt-5-codex 272000 "gpt-5-codex:272000" >/dev/null || { echo "the relaunch after rotation failed its contract"; return 1; }
  if sha256sum < "$(resolve_state_dir)/turn-auth-header" | cmp -s - "$PRIVATE/header.pre-rotation"; then
    echo "turn-auth-header still holds the pre-rotation key after a launch"; return 1
  fi
  echo "the launch after rotation rewrote turn-auth-header with a key the head accepts"
  wrapper_turn claudex "ok after auth"
}
step "rotation: the next launch rewrites turn-auth-header and a turn runs on it" in_dir "$HOME" header_follows

step "splice status after the upgrade: daemon running, every head listed" status_step
step "splice uninstall after the upgrade removes the wrappers" uninstall_step
