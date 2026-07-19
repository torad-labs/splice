#!/usr/bin/env bash
set -euo pipefail
! grep -qi "encrypted CoT" README.md
! grep -q "bin/claudex login" README.md
grep -qi "not affiliated" README.md
grep -q "splice-launch" README.md
grep -qi "experimental" README.md
echo "VERIFY OSS-E: OK"
