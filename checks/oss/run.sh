#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

for check in checks/oss/verify-OSS-{A..M}.sh; do
  echo "── $(basename "$check" .sh) ──"
  bash "$check"
done

echo "VERIFY OSS: ALL OK"
