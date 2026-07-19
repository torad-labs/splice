#!/usr/bin/env bash
set -euo pipefail
test -f THIRD_PARTY_NOTICES.md
grep -qi "SIL Open Font License" THIRD_PARTY_NOTICES.md
grep -qi "gradle wrapper" THIRD_PARTY_NOTICES.md
ls webui/src/shared/fonts/ | grep -qi "OFL\|LICENSE"
echo "VERIFY OSS-H: OK"
