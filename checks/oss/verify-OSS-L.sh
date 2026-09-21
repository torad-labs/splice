#!/usr/bin/env bash
set -euo pipefail
test -f .docs/PROVENANCE.md
grep -Eqi "UNRESOLVED|upstream" .docs/PROVENANCE.md
echo "VERIFY OSS-L: OK"
