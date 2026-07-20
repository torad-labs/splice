#!/usr/bin/env bash
set -euo pipefail
bash -n install.sh
shellcheck -S error install.sh
bash checks/release/launcher-test.sh
! grep -q "marcospaulo/splice" install.sh
grep -q "releases/download\|releases/latest/download" install.sh
! grep -qE "\|\| true" install.sh
python3 -c "import yaml,sys; yaml.safe_load(open(sys.argv[1]))" .github/workflows/release.yml
! grep -Eq "uses: .*@v[0-9]+[[:space:]]*$" .github/workflows/release.yml
grep -q "draft: true" .github/workflows/release.yml
grep -q "THIRD_PARTY_LICENSES.txt" .github/workflows/release.yml
grep -q distributionSha256Sum gateway/gradle/wrapper/gradle-wrapper.properties
( cd gateway && ./gradlew -q :app:shadowJar --no-daemon --no-parallel )
bash checks/release/stage.sh
bash checks/release/accept.sh
echo "VERIFY OSS-D: OK"
