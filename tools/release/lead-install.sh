#!/usr/bin/env bash
# splice-lead: install a judged sha on this machine and restart splice (repo/CLAUDE.md, steps 1-7).
# Usage: tools/release/lead-install.sh <sha> milestone "<what shipped>"
#        tools/release/lead-install.sh <sha> live-fix "<what is broken on the running daemon>"
#
#
# WHEN (Marcos, 2026-10-10): splice is installed and restarted only at a major milestone,
#   because every restart resets his sessions.  The one exception is a fix for something that is
#   broken on the running daemon right now.  Without one of the two reasons the script refuses
#   before it builds anything.  This replaces the hourly cadence and "install every verified sha".
set -euo pipefail
sha=$1; why=${2:-}; what=${3:-}
case "$why" in milestone|live-fix) [ -n "$what" ] || { echo "REFUSED: say what the $why is"; exit 10; } ;;
  *) echo "REFUSED $sha: splice installs only at a major milestone or for a live fix (Marcos, 2026-10-10)"; exit 10 ;; esac
R=/home/marcos/Documents/dev/projects/mythos/repo; T=/tmp/splice-build-$sha-lead; D=~/.local/share/splice
M=~/.local/state/splice-lead/last-restart-epoch
ct(){ TZ=America/Chicago date '+%-I:%M %p CT'; }
echo "start $(ct) — $why: $what"
cd $R
[ -d $T ] && git worktree remove --force $T 2>/dev/null || true
git worktree add --detach $T $sha
cd $T
test -x tools/gate/bin/gradlew || { echo "no gradlew in tree"; exit 2; }
# The MCP hub's two native binaries ship beside the jar on a sha that carries their module (docs/specs/mcp-hub.md,
# Linux x86_64 only), opted in with SPLICE_INSTALL_HUB=1. They are never rebuilt here: Kotlin/Native writes each
# source file's absolute path into the binary, so a build in this tree can never hash to the qualified pair. The pair
# hostshield-ctl qualified on its desk is kept, as built, in the project's artifacts folder, one folder per build sha
# (splice-lead, Oct 13), and the folder whose two binaries both hash to tools/release/mcp-hub.sha256 is the one installed.
hub=; [ "${SPLICE_INSTALL_HUB:-0}" = 1 ] && [ -d $T/integrations/mcp-hub ] && hub=/home/marcos/Documents/dev/projects/mythos/artifacts/mcp-hub
bun tools/gate slot install-$sha -- :app:shadowJar
echo "slot exit 0 at $(ct)"
# The hub pair installs only as the bytes hostshield-ctl qualified (Oct 13): the kept folder whose two binaries each
# hash to their line in tools/release/mcp-hub.sha256, checked here before anything on this machine is replaced. No such
# folder, or a pin missing, refuses the install.
if [ -n "$hub" ]; then
  pins=$R/tools/release/mcp-hub.sha256
  [ -f $pins ] || { echo "REFUSED $sha: no qualified hub hashes in $pins"; exit 11; }
  pair=
  for d in "$hub"/*/; do
    ok=1
    for b in holder front; do
      want=$(awk -v n=splice-mcp-$b '$2 == n {print $1}' $pins)
      got=$(sha256sum "$d"splice-mcp-$b 2>/dev/null | cut -d" " -f1)
      [ -n "$want" ] && [ "$got" = "$want" ] || ok=
    done
    [ -n "$ok" ] && { pair=${d%/}; break; }
  done
  [ -n "$pair" ] || { echo "REFUSED $sha: no kept hub pair under $hub hashes to $pins"; exit 11; }
  echo "hub pair $pair matches the qualified hashes"
fi
J=$T/app/build/libs/app-all.jar; L=$T/app/src/main/dist/bin/splice-launch
a=$(sha256sum $J | cut -d' ' -f1); echo "artifact $a"
rp=$(systemctl --user show -p MainPID --value splice.service); [ "$rp" = 0 ] && rp=
if [ -n "$rp" ]; then
  rfd=$(ls -l /proc/$rp/fd 2>/dev/null | awk '/splice\.jar/ && !f {print $9; f=1}')
  ro=$(sha256sum /proc/$rp/fd/$rfd | cut -d' ' -f1)
  echo "running  $ro (pid $rp)"
  if [ "$a" = "$ro" ]; then echo "ALREADY RUNNING $sha at $(ct)"; cd $R && git worktree remove --force $T && echo "tree removed"; exit 0; fi
fi
stamp=$(TZ=America/Chicago date +%Y%m%d-%H%M)
cp -p $D/splice.jar $D/splice.jar.bak-$stamp-pre-$sha
cp -p $D/splice-launch $D/splice-launch.bak-$stamp-pre-$sha
echo "backup splice.jar.bak-$stamp-pre-$sha"
cp $J $D/splice.jar.new-$sha && mv $D/splice.jar.new-$sha $D/splice.jar
cp $L $D/splice-launch.new-$sha && chmod 0755 $D/splice-launch.new-$sha && mv $D/splice-launch.new-$sha $D/splice-launch
# A running holder or front keeps its own inode through the mv; the new binary serves what starts next.
if [ -n "$hub" ]; then
  for b in holder front; do
    src=$pair/splice-mcp-$b; dst=$D/splice-mcp-$b
    [ -f $dst ] && cp -p $dst $dst.bak-$stamp-pre-$sha
    cp $src $dst.new-$sha && chmod 0755 $dst.new-$sha && mv $dst.new-$sha $dst
    echo "installed splice-mcp-$b $(sha256sum $dst | cut -d' ' -f1)"
  done
  # A running front takes the new binary by reload, never a restart (hostshield-lead's contract, Oct 12).
  if systemctl --user is-active --quiet splice-mcp-front.service; then
    systemctl --user reload splice-mcp-front.service && echo "reloaded splice-mcp-front.service"
  fi
fi
splice restart --now
date +%s > $M
sleep 8
pid=$(systemctl --user show -p MainPID --value splice.service)
fd=$(ls -l /proc/$pid/fd | awk '/splice\.jar$/ {print $9}' | sort -n | head -1)  # the classloader holds the lowest; a later fd on the jar can close mid-read
o=$(sha256sum /proc/$pid/fd/$fd | cut -d' ' -f1)
echo "open     $o (pid $pid fd $fd)"
[ "$a" = "$o" ] && echo "VERIFIED $sha at $(ct)" || { echo "MISMATCH"; exit 1; }
cd $R && git worktree remove --force $T && echo "tree removed"
