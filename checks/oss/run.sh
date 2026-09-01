#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

# Derive the leg list from the DIRECTORY, never a literal {A..M} range: the brace form failed
# loudly on a deleted leg but silently SKIPPED an added verify-OSS-N.sh — the
# denominator-from-allowlist shape (DR-35c). The count floor pins today's inventory so a glob
# that stops matching (rename, move) cannot quietly shrink the ladder either.
mapfile -t checks < <(compgen -G 'checks/oss/verify-OSS-*.sh' | sort)
if (( ${#checks[@]} < 13 )); then
  echo "OSS ladder shrank: expected >= 13 verify-OSS-*.sh legs, found ${#checks[@]}" >&2
  exit 1
fi

for check in "${checks[@]}"; do
  echo "── $(basename "$check" .sh) ──"
  bash "$check"
done

echo "VERIFY OSS: ALL OK (${#checks[@]} legs)"
