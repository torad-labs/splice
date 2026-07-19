#!/usr/bin/env bash
set -euo pipefail
python3 -c "import yaml,sys; yaml.safe_load(open(sys.argv[1]))" .github/workflows/ci.yml
grep -q "permissions:" .github/workflows/ci.yml
! grep -Eq "uses: .*@v[0-9]+[[:space:]]*$" .github/workflows/ci.yml
echo "VERIFY OSS-C: OK"
