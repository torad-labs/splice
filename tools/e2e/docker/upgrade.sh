#!/usr/bin/env bash
# tools/e2e/docker/upgrade.sh — the upgrade rehearsal, run INSIDE the container by
# `run.sh --upgrade-from vX.Y.Z`.
#
# A user on a published release upgrades by re-running the install one-liner (releases before 0.4.0
# have no `splice upgrade`), with their daemon running, sessions open and transcripts on disk. This
# rehearses that on a machine that has USED the old release: /from holds the old release's own
# assets, /artifacts the candidate. The old release installs with its own install.sh, boots, stores
# an API key, serves a real print-mode turn through the Claude Code wrapper (a transcript under a
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
PRIVATE="$HOME/.e2e-private"
CONFIG="$HOME/.config/splice"
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
  [ -n "$FROM_VERSION" ] && [ -n "$CANDIDATE_VERSION" ] || { echo "a jar did not report its version"; return 1; }
  [ "${FROM_TAG#v}" = "$FROM_VERSION" ] || { echo "/from is not $FROM_TAG: its jar reports $FROM_VERSION"; return 1; }
  [ "$FROM_VERSION" != "$CANDIDATE_VERSION" ] ||
    { echo "the candidate reports $FROM_VERSION too, so the running daemon would never be replaced"; return 1; }
}
step "the release and the candidate report different versions" versions_differ

step "mock upstreams up" start_mocks
read_mock_ports
step "topology written" write_topology
export_mock_env

# ── 1. the old release in use ─────────────────────────────────────────────────────────────────
step "install $FROM_TAG with its own install.sh: checksums verified, wrappers linked" install_step "$FROM"
step "$FROM_TAG cold start: /health ok, every head ready" cold_start
step "$FROM_TAG /api/heads lists all three heads running" api_heads

# Why the release notes send a 0.3.x user to the install one-liner: the verb does not exist yet.
no_upgrade_verb() {
  local out rc
  out="$(splice upgrade </dev/null 2>&1)"; rc=$?
  printf '%s\n' "$out" | head -5
  [ $rc -ne 0 ] || { echo "$FROM_TAG accepted \`splice upgrade\`"; return 1; }
  ! printf '%s\n' "$out" | grep -q 'upgrade' || { echo "$FROM_TAG names an upgrade verb"; return 1; }
  echo "$FROM_TAG has no upgrade verb (exit $rc)"
}
step "$FROM_TAG has no \`splice upgrade\`: the install one-liner is the upgrade path" no_upgrade_verb

store_api_key() {
  printf '%s' "mock-chat-key" | splice key set MOCK_CHAT_API_KEY --stdin || return 1
  [ "$(stat -c %a "$CONFIG/keys.toml")" = "600" ] || { echo "keys.toml is $(stat -c %a "$CONFIG/keys.toml")"; return 1; }
  echo "MOCK_CHAT_API_KEY stored in $CONFIG/keys.toml (0600)"
}
step "$FROM_TAG stores an API key (a credential the upgrade must keep)" store_api_key

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
  printf '%s' "$out" | grep -qF "ok after auth" || { echo "the turn did not complete"; return 1; }
  printf '%s' "$out" | grep -qF "replacing stale daemon $FROM_VERSION with $CANDIDATE_VERSION" ||
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
  [ -n "$jar_sha" ] && [ "$jar_sha" = "$want" ] || { echo "the serving daemon does not hold the candidate jar open"; return 1; }
}
step "first launch after the upgrade: the shim replaces the $FROM_TAG daemon with the candidate" in_dir "$HOME" handover

heads_back() { wait_health 60 && api_heads; }
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
  printf '%s\n' "$out" | grep -qE "^\s*✓\s+topology\s" || { echo "doctor does not load the topology"; return 1; }
  printf '%s\n' "$out" | grep -qE "^\s*✓\s+shim\s+current \($shim\)" || { echo "doctor does not report the shim current ($shim)"; return 1; }
  ! printf '%s\n' "$out" | grep -v '^splice doctor' | grep -q '✗' || { echo "doctor reports a ✗"; return 1; }
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
  bun "$LIB_TS" turn-key-placement "$OUT/recipe-claudex.json" "$HOME/.claude-claudex/settings.json" "$(resolve_state_dir)"
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

resume_pre_session() {
  wrapper_turn claudex "ok after auth" --resume "$SID" || return 1
  bun "$LIB_TS" appended "$PRIVATE/transcript.pre" "$TRANSCRIPT"
}
step "the pre-upgrade session resumes on the candidate and appends after its bytes" in_dir "$WORK" resume_pre_session

step "wrapper turn after the upgrade: claude-mockchat -p through its head" in_dir "$HOME" wrapper_turn claude-mockchat "END"

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
  printf '%s' "$out" | grep -qF "mgmt-key not found at $key" || { echo "restart did not name the missing key"; return 1; }
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
