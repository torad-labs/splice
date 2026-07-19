#!/usr/bin/env bash
set -euo pipefail
test -f PROVENANCE.md
grep -Eqi "UNRESOLVED|upstream" PROVENANCE.md
echo "VERIFY OSS-L: OK"
