#!/usr/bin/env bash
# tools/e2e/docker/auto-mode.sh — an INTERACTIVE claude-mockchat session in auto mode, driven in tmux through its first
# checked action (a Bash command that writes a file). A translated head must show no "this session isn't eligible"
# notice and hold nothing: the launch recipe sets CLAUDE_CODE_AUTO_MODE_SERVER=0. Run INSIDE the container by
# `run.sh --scenario auto-mode`. The mock upstream does not answer Claude Code's own classifier request in a form
# auto mode can read, so the command ends "could not evaluate this action"; that is the mock's silence, not a hold.
set -uo pipefail
RECEIPT_KIND="splice-auto-mode-e2e"
RECEIPT_LABEL="AUTO-MODE E2E"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
trap finish EXIT

step "Claude Code version matches the splice tested pin" client_version_receipt
step "mock upstreams up" start_mocks
read_mock_ports
step "topology written" write_topology
export_mock_env
step "install.sh from artifacts" install_step
step "daemon cold start" cold_start

PANE="$OUT/auto-mode-pane.txt"
auto_mode_first_action() {
  mkdir -p "$HOME/work"; cd "$HOME/work" || return 1
  tmux new-session -d -s auto -x 180 -y 50 "claude-mockchat --permission-mode auto"
  sleep 8
  tmux send-keys -t auto Down Enter   # "Yes, I trust this folder"
  sleep 6
  tmux send-keys -t auto "SCENARIO:runbash please write the file" Enter
  sleep 25
  tmux capture-pane -p -t auto | grep -v '^\s*$' | tee "$PANE"
  grep -q "isn't eligible" "$PANE" && { echo "the compatibility notice appeared"; return 1; }
  grep -q "Enter to continue" "$PANE" && { echo "the session is held at a prompt"; return 1; }
  grep -q "Bash(mkdir" "$PANE" || { echo "the first checked action never ran"; return 1; }
  echo "no notice, nothing held"
}
step "interactive auto mode: first checked action, no compatibility notice, nothing held" auto_mode_first_action
