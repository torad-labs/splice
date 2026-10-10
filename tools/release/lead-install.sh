#!/usr/bin/env bash
# splice-lead: install a judged sha on this machine and restart splice (repo/CLAUDE.md, steps 1-7).
# Usage: tools/release/lead-install.sh <sha> [urgent]
#
# CADENCE (Eli, President, deciding for Marcos — 2026-10-10, TEMPORARY):
#   Until a restart refuses no connection, at most ONE restart an hour.  A build that fixes
#   something broken in the LIVE daemon installs at once: pass "urgent" as the second argument.
#   When the zero-refusal handover is installed and seen to refuse nothing, this guard is deleted
#   and Marcos's rule applies as written again (repo/CLAUDE.md: install every verified sha, at any
#   time, without asking).
set -euo pipefail
sha=$1; urgent=${2:-}; R=/home/marcos/Documents/dev/projects/mythos/repo; T=/tmp/splice-build-$sha-lead; D=~/.local/share/splice
M=~/.local/state/splice-lead/last-restart-epoch
ct(){ TZ=America/Chicago date '+%-I:%M %p CT'; }
echo "start $(ct)"
cd $R
[ -d $T ] && git worktree remove --force $T 2>/dev/null || true
git worktree add --detach $T $sha
cd $T
test -x tools/gate/bin/gradlew || { echo "no gradlew in tree"; exit 2; }
bun tools/gate slot install-$sha -- :app:shadowJar
echo "slot exit 0 at $(ct)"
J=$T/app/build/libs/app-all.jar; L=$T/app/src/main/dist/bin/splice-launch
a=$(sha256sum $J | cut -d' ' -f1); echo "artifact $a"
rp=$(systemctl --user show -p MainPID --value splice.service); [ "$rp" = 0 ] && rp=
if [ -n "$rp" ]; then
  rfd=$(ls -l /proc/$rp/fd 2>/dev/null | awk '/splice\.jar/ && !f {print $9; f=1}')
  ro=$(sha256sum /proc/$rp/fd/$rfd | cut -d' ' -f1)
  echo "running  $ro (pid $rp)"
  if [ "$a" = "$ro" ]; then echo "ALREADY RUNNING $sha at $(ct)"; cd $R && git worktree remove --force $T && echo "tree removed"; exit 0; fi
fi
# --- cadence guard (see header) ---
if [ "$urgent" != urgent ] && [ -f $M ]; then
  last=$(cat $M); now=$(date +%s); age=$(( now - last ))
  if [ $age -lt 3600 ]; then
    opens=$(TZ=America/Chicago date -d "@$(( last + 3600 ))" '+%-I:%M %p CT')
    echo "HELD $sha — last restart was $(( age / 60 ))m ago; the window opens $opens."
    echo "HELD: pass 'urgent' only for a fix to something broken in the live daemon."
    cd $R && git worktree remove --force $T && echo "tree removed"
    exit 10
  fi
fi
[ "$urgent" = urgent ] && echo "URGENT: cadence guard bypassed — a fix to something broken live."
stamp=$(TZ=America/Chicago date +%Y%m%d-%H%M)
cp -p $D/splice.jar $D/splice.jar.bak-$stamp-pre-$sha
cp -p $D/splice-launch $D/splice-launch.bak-$stamp-pre-$sha
echo "backup splice.jar.bak-$stamp-pre-$sha"
cp $J $D/splice.jar.new-$sha && mv $D/splice.jar.new-$sha $D/splice.jar
cp $L $D/splice-launch.new-$sha && chmod 0755 $D/splice-launch.new-$sha && mv $D/splice-launch.new-$sha $D/splice-launch
splice restart --now
date +%s > $M
sleep 8
pid=$(systemctl --user show -p MainPID --value splice.service)
fd=$(ls -l /proc/$pid/fd | awk '/splice\.jar/ && !f {print $9; f=1}')
o=$(sha256sum /proc/$pid/fd/$fd | cut -d' ' -f1)
echo "open     $o (pid $pid fd $fd)"
[ "$a" = "$o" ] && echo "VERIFIED $sha at $(ct)" || { echo "MISMATCH"; exit 1; }
cd $R && git worktree remove --force $T && echo "tree removed"
