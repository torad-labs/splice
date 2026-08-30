#!/usr/bin/env bash
set -euo pipefail
bash -n install.sh
shellcheck -S error install.sh
bash checks/release/launcher-test.sh
! grep -q "marcospaulo/splice" install.sh
grep -q "releases/download\|releases/latest/download" install.sh
! grep -qE "\|\| true" install.sh
grep -q "GitHub CLI (gh) is required" install.sh
grep -Fq 'verify_attestation "$JAR_TMP" splice.jar' install.sh
grep -Fq 'verify_attestation "$SHIM_TMP" splice-launch' install.sh
python3 - .github/workflows/release.yml <<'PY'
import sys
import yaml

workflow = yaml.safe_load(open(sys.argv[1], encoding="utf-8"))
steps = workflow["jobs"]["publish"]["steps"]
release_steps = [step for step in steps if str(step.get("uses", "")).startswith("softprops/action-gh-release@")]
if len(release_steps) != 1:
    raise SystemExit(f"VERIFY OSS-D: expected one action-gh-release step, found {len(release_steps)}")
expected = "${{ contains(needs.build.outputs.version, '-') }}"
actual = release_steps[0].get("with", {}).get("prerelease")
if actual != expected:
    raise SystemExit(f"VERIFY OSS-D: action-gh-release prerelease input is {actual!r}, expected {expected!r}")
PY
! grep -Eq "uses: .*@v[0-9]+[[:space:]]*$" .github/workflows/release.yml
grep -q "draft: true" .github/workflows/release.yml
grep -q "THIRD_PARTY_LICENSES.txt" .github/workflows/release.yml
grep -q distributionSha256Sum gateway/gradle/wrapper/gradle-wrapper.properties
VERSION="$(node -p "require('./package.json').version")"
invalid_tag_log="$(mktemp)"
trap 'rm -f "$invalid_tag_log"' EXIT
for invalid_tag in v1.2.3-01 v1.2.3-alpha.007; do
  if SPLICE_RELEASE_TAG="$invalid_tag" bash checks/release/stage.sh >"$invalid_tag_log" 2>&1; then
    echo "VERIFY OSS-D: invalid SemVer tag unexpectedly passed: $invalid_tag" >&2
    exit 1
  fi
  grep -q "tag must be valid SemVer" "$invalid_tag_log" || {
    echo "VERIFY OSS-D: $invalid_tag failed for the wrong reason" >&2
    exit 1
  }
done
( cd gateway && ./gradlew -q :app:shadowJar --no-daemon --no-parallel )
SPLICE_RELEASE_TAG="v$VERSION" bash checks/release/stage.sh
SPLICE_EXPECTED_VERSION="$VERSION" bash checks/release/accept.sh
echo "VERIFY OSS-D: OK"
