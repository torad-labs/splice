#!/usr/bin/env bash
# tools/e2e/docker/no-node.sh — a new user on a machine with Java and a natively installed Claude Code and NO Node,
# start to finish. Run INSIDE the container by `run.sh --scenario no-node`.
#
# Every step records the command and the EXACT text the user would see into /out/transcript.txt (stdout and stderr
# merged in the order they print), so a reader judges the words and their order, not a summary. The mock upstreams
# stand in for OpenAI's backend because the container has no network; bun runs them and is not on a user's machine.
set -uo pipefail

RECEIPT_KIND="splice-no-node-e2e"
RECEIPT_LABEL="NO-NODE E2E"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
trap finish EXIT

TRANSCRIPT="$OUT/transcript.txt"
: > "$TRANSCRIPT"
# shown CMD...  — print "$ CMD", run it, show its output exactly as printed, keep the exit status.
shown() {
  printf '$ %s\n' "$*" | tee -a "$TRANSCRIPT"
  "$@" 2>&1 | tee -a "$TRANSCRIPT"
  local rc=${PIPESTATUS[0]}
  printf '[exit %s]\n\n' "$rc" | tee -a "$TRANSCRIPT"
  return "$rc"
}

echo "no-node e2e: user=$(id -un) home=$HOME artifacts=$ARTIFACTS"

no_node_anywhere() {
  local found=0 name
  for name in node nodejs npm npx corepack; do
    if command -v "$name" >/dev/null 2>&1; then echo "$name is on PATH: $(command -v "$name")"; found=1; fi
  done
  find / -xdev \( -name node -o -name nodejs -o -name npm \) \( -type f -o -type l \) -perm -u+x 2>/dev/null | head -5 | sed 's/^/found: /'
  [ "$found" = 0 ] && ! find / -xdev \( -name node -o -name nodejs -o -name npm \) \( -type f -o -type l \) -perm -u+x 2>/dev/null | grep -q . \
    && echo "no node, nodejs, npm, npx or corepack anywhere on this machine; java: $(java -version 2>&1 | head -1); claude: $(claude --version)"
}
step "Claude Code version matches the splice tested pin" client_version_receipt
step "the machine has no Node anywhere" no_node_anywhere

step "mock upstreams up (bun runs them; it is test scaffolding, not a user prerequisite)" start_mocks
read_mock_ports

# The installer and the CLI read these; the mock stands in for OpenAI's backend and sign-in.
export CHATGPT_API_BASE="http://127.0.0.1:$CODEX_MOCK_PORT"
export CODEX_AUTH_PATH CODEX_OAUTH_TOKEN_URL="http://127.0.0.1:$CODEX_MOCK_PORT/oauth/token"

# Offset-based: the text a step printed is the transcript from where it began.
mark() { wc -c < "$TRANSCRIPT"; }
since() { tail -c +"$(($1 + 1))" "$TRANSCRIPT"; }

# 1. install.sh start to finish, as a new user runs it
install_all() {
  local m; m=$(mark)
  SPLICE_JAR="$ARTIFACTS/splice.jar" SPLICE_SHIM="$ARTIFACTS/splice-launch" shown bash "$REPO/install.sh" </dev/null || return 1
  if since "$m" | grep -qi 'node'; then echo "install.sh output mentions Node"; return 1; fi
  echo "install.sh exited 0 and its output never says node"
}
step "1. install.sh runs start to finish and never mentions Node" install_all

# 2. splice add codex. The sign-in is the browser page a user would use: the test plays the browser by opening the
# callback URL the CLI prints. --base-url and --model aim it at the mock because the container has no network.
add_codex() {
  local m out="$OUT/add-codex.out" url state i
  m=$(mark)
  printf '$ splice add codex --yes --base-url %s --model gpt-5-codex\n' "http://127.0.0.1:$CODEX_MOCK_PORT" >> "$TRANSCRIPT"
  splice add codex --yes --base-url "http://127.0.0.1:$CODEX_MOCK_PORT" --model gpt-5-codex </dev/null > "$out" 2>&1 &
  local pid=$!
  for i in $(seq 1 100); do grep -q 'state=' "$out" 2>/dev/null && break; sleep 0.2; done
  state="$(grep -o 'state=[^& ]*' "$out" | head -1)"
  [ -n "$state" ] || { cat "$out" >> "$TRANSCRIPT"; echo "no sign-in URL was printed"; return 1; }
  curl -s -m 10 "http://localhost:1455/auth/callback?code=testcode&$state" >/dev/null
  wait "$pid"; local rc=$?
  cat "$out" >> "$TRANSCRIPT"; printf '[exit %s]\n\n' "$rc" >> "$TRANSCRIPT"
  since "$m"
  [ $rc -eq 0 ] || return 1
  [ -x "$HOME/.local/bin/claudex" ] || { echo "claudex was not linked"; return 1; }
}
step "2. splice add codex signs in, writes the head and links claudex" add_codex

# 3. claudex lands in Claude Code and answers a turn (print mode, the same Claude Code process an interactive start execs)
one_turn() {
  local m; m=$(mark)
  shown claudex -p "say hi" </dev/null || return 1
  since "$m" | grep -q 'ok after auth' || { echo "the turn's answer never arrived"; return 1; }
}
step "3. claudex starts the daemon, lands in Claude Code and answers a turn" one_turn

# 4. four sessions at once
four_at_once() {
  local m i; m=$(mark)
  printf '$ claudex -p "session N: say hi"   # x4, started in the same second\n' >> "$TRANSCRIPT"
  for i in 1 2 3 4; do
    (claudex -p "session $i: say hi" </dev/null > "$OUT/session-$i.out" 2>&1; echo $? > "$OUT/session-$i.rc") &
  done
  wait
  local bad=0
  for i in 1 2 3 4; do
    { printf -- '--- session %s ---\n' "$i"; cat "$OUT/session-$i.out"; printf '[exit %s]\n' "$(cat "$OUT/session-$i.rc")"; } | tee -a "$TRANSCRIPT"
    [ "$(cat "$OUT/session-$i.rc")" = 0 ] && grep -q 'ok after auth' "$OUT/session-$i.out" || bad=1
  done
  printf '\n' >> "$TRANSCRIPT"
  [ $bad = 0 ] || { echo "a session failed"; return 1; }
  echo "all four sessions answered"
}
step "4. four sessions launched at once all answer" four_at_once

# 5. splice doctor, every line green
doctor_green() {
  local m; m=$(mark)
  shown splice doctor || return 1
  if since "$m" | strip_ansi | grep -v '^splice doctor' | grep -q '✗'; then echo "doctor shows a ✗"; return 1; fi
  since "$m" | strip_ansi | grep -qE '✓\s+node' && { echo "doctor lists node"; return 1; }
  echo "doctor: no ✗, no node line"
}
step "5. splice doctor is all green" doctor_green

# 6. curl taken off PATH: every tool of /usr/bin and /bin except curl
no_curl() {
  local bare="$HOME/no-curl-bin" f
  mkdir -p "$bare"
  for f in /usr/bin/* /bin/* "$HOME/.local/bin"/*; do
    [ -x "$f" ] && [ "$(basename "$f")" != curl ] && ln -sf "$f" "$bare/$(basename "$f")" 2>/dev/null
  done
  local m; m=$(mark)
  printf '$ PATH=<every tool but curl> claudex\n' >> "$TRANSCRIPT"
  local out rc
  out="$(PATH="$bare:/usr/local/bin" claudex </dev/null 2>&1)"; rc=$?
  printf '%s\n[exit %s]\n\n' "$out" "$rc" | tee -a "$TRANSCRIPT"
  [ $rc -ne 0 ] || { echo "claudex ran without curl"; return 1; }
  grep -qi 'curl' <<<"$out" || { echo "the message never names curl"; return 1; }
}
step "6. with curl off PATH, claudex says what to install" no_curl

cp "$TRANSCRIPT" "$OUT/transcript.final.txt"
