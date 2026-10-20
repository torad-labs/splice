#!/usr/bin/env bash
# splice-lead: install a judged sha on this machine and restart splice (repo/CLAUDE.md, steps 1-7).
# Usage: tools/release/lead-install.sh <sha> beta "<the beta, and what shipped>"
#        tools/release/lead-install.sh <sha> milestone "<what shipped>"
#        tools/release/lead-install.sh <sha> live-fix "<what is broken on the running daemon>"
#
# WHEN: this machine follows the beta channel (Marcos, 2026-10-13, repo/CLAUDE.md "This machine follows the beta
#   channel with auto-update"), which replaced the milestone-only rule of 2026-10-10.  Until the daemon installs betas
#   itself, splice-lead installs each beta by this script: <sha> beta "<version and what shipped>".  A reason is
#   still required so that no install is made without a named cause, but "beta" is one: restarts cut turns the retry
#   layers bring back, and the install is reversible because the backup is taken first.  "milestone" and "live-fix"
#   are the two reasons the 2026-10-10 rule allowed and remain accepted.  Without one of the three the script refuses
#   before it builds anything.
set -euo pipefail
sha=$1; why=${2:-}; what=${3:-}
case "$why" in beta|milestone|live-fix) [ -n "$what" ] || { echo "REFUSED: say what the $why is"; exit 10; } ;;
  *) echo "REFUSED $sha: say why with beta, milestone or live-fix (repo/CLAUDE.md: this machine follows the beta channel, Marcos 2026-10-13)"; exit 10 ;; esac
# R is the checkout this script sits in, not a path: the project moved from projects/mythos to projects/splice and a
# spelled-out path went dead with it. The kept hub pairs sit beside the checkout, as tools/release hub and beta keep them.
R=$(cd "$(dirname "$0")/../.." && pwd); T=/tmp/splice-build-$sha-lead; D=~/.local/share/splice
M=~/.local/state/splice-lead/last-restart-epoch
ct(){ TZ=America/Chicago date '+%-I:%M %p CT'; }
echo "start $(ct) — $why: $what"
cd $R
[ -d $T ] && git worktree remove --force $T 2>/dev/null || true
git worktree add --detach $T $sha
cd $T
test -x tools/gate/bin/gradlew || { echo "no gradlew in tree"; exit 2; }
# The MCP hub's two native binaries ship beside the jar on a sha that carries their module (docs/specs/mcp-hub.md,
# Linux x86_64 only). They are never rebuilt here: Kotlin/Native writes each source file's absolute path into the
# binary, so a build in this tree can never hash to the qualified pair. `bun tools/release hub <sha>` builds and keeps a
# pair in the project's artifacts folder, one folder per build sha; hostshield-lead qualifies it on its desk and pins
# its hashes in tools/release/mcp-hub.sha256, and the folder whose two binaries both hash to the pins is the one
# installed (splice-lead, Oct 13). Oct 13 (splice-runtime-lead): every install ships the qualified pair when it differs
# from the installed one, so a front change goes out with the next install once it is qualified. SPLICE_INSTALL_HUB=0
# skips the pair; =1 refuses the whole install when no qualified pair is kept; unset, a missing pair is said and the
# jar installs without it.
hub_mode=${SPLICE_INSTALL_HUB:-auto}
hub=; [ "$hub_mode" != 0 ] && [ -d $T/integrations/mcp-hub ] && hub=$R/../artifacts/mcp-hub
bun tools/gate slot install-$sha -- :app:shadowJar
echo "slot exit 0 at $(ct)"
# The hub pair installs only as the bytes hostshield-ctl qualified (Oct 13): the kept folder whose two binaries each
# hash to their line in tools/release/mcp-hub.sha256, checked here before anything on this machine is replaced. No such
# folder, or a pin missing, refuses the install.
if [ -n "$hub" ]; then
  pins=$R/tools/release/mcp-hub.sha256
  pair=
  [ -f $pins ] && for d in "$hub"/*/; do
    ok=1
    for b in holder front; do
      want=$(awk -v n=splice-mcp-$b '$2 == n {print $1}' $pins)
      got=$(sha256sum "$d"splice-mcp-$b 2>/dev/null | cut -d" " -f1)
      [ -n "$want" ] && [ "$got" = "$want" ] || ok=
    done
    [ -n "$ok" ] && { pair=${d%/}; break; }
  done
  if [ -z "$pair" ]; then
    [ "$hub_mode" = 1 ] && { echo "REFUSED $sha: no kept hub pair under $hub hashes to $pins"; exit 11; }
    echo "hub: no kept pair under $hub hashes to $pins; the installed hub binaries stay as they are"
    hub=
  else
    echo "hub pair $pair matches the qualified hashes"
    # The pair's folder is named for the sha it was built at: hub sources that moved since then are not in it yet.
    git -C $R diff --quiet "$(basename $pair)" "$sha" -- integrations/mcp-hub 2>/dev/null ||
      echo "hub: integrations/mcp-hub changed after $(basename $pair); bun tools/release hub $sha, then hostshield-lead's qualification and pin, ships it"
  fi
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
  changed=
  for b in holder front; do
    src=$pair/splice-mcp-$b; dst=$D/splice-mcp-$b
    if [ -f $dst ] && [ "$(sha256sum $dst | cut -d' ' -f1)" = "$(sha256sum $src | cut -d' ' -f1)" ]; then
      echo "splice-mcp-$b unchanged"; continue
    fi
    [ -f $dst ] && cp -p $dst $dst.bak-$stamp-pre-$sha
    cp $src $dst.new-$sha && chmod 0755 $dst.new-$sha && mv $dst.new-$sha $dst
    echo "installed splice-mcp-$b $(sha256sum $dst | cut -d' ' -f1)"
    changed="$changed $b"
  done
  # A running front takes a new front binary by reload, in place, never a restart (hostshield-lead's contract, Oct 12):
  # it re-execs the new binary on the same socket and pid. A front that is not running is not started here.
  case "$changed" in *front*)
    if systemctl --user is-active --quiet splice-mcp-front.service; then
      systemctl --user reload splice-mcp-front.service && echo "reloaded splice-mcp-front.service"
    fi ;;
  esac
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
