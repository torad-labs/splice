#!/usr/bin/env bash
set -euo pipefail
for workflow in .github/workflows/*.yml; do
  python3 -c "import yaml,sys; yaml.safe_load(open(sys.argv[1]))" "$workflow"
  grep -q "permissions:" "$workflow"
  ! grep -Eq "uses: .*@v[0-9]+([.][0-9]+)*[[:space:]]*$" "$workflow"
done
echo "VERIFY OSS-C: OK"
