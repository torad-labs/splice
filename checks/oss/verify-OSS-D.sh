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
# DR-19: the tag gate must run on the PROMOTION path too — the stage step threads the resolved
# version in as SPLICE_RELEASE_TAG (the tag path still wins inside stage.sh via GITHUB_REF_TYPE).
# Without this wiring, stage.sh's whole SemVer/equality block is skipped exactly where releases
# are actually cut, and a malformed launcher version lands as the tag name.
grep -Fq 'SPLICE_RELEASE_TAG="v$RESOLVED_VERSION" bash checks/release/stage.sh' .github/workflows/release.yml || {
  echo "VERIFY OSS-D: release.yml stage step does not thread SPLICE_RELEASE_TAG on the promotion path" >&2
  exit 1
}
# DR-25: three hand-authored asset lists, ONE authority. stage.sh's ASSETS is it; accept.sh must
# match it exactly, and the workflow's upload list must be the dist/-prefixed set plus the sums
# file — an asset dropped from any copy ships absent with a green build otherwise.
python3 - checks/release/stage.sh checks/release/accept.sh .github/workflows/release.yml <<'PY'
import re
import sys
import yaml

def assets(path):
    text = open(path, encoding="utf-8").read()
    match = re.search(r"^ASSETS=\((.*?)\)$", text, re.S | re.M)
    if match is None:
        raise SystemExit(f"VERIFY OSS-D: no ASSETS=() block in {path}")
    return match.group(1).split()

stage, accept = assets(sys.argv[1]), assets(sys.argv[2])
if stage != accept:
    raise SystemExit(f"VERIFY OSS-D: stage.sh assets {stage} != accept.sh assets {accept}")
workflow = yaml.safe_load(open(sys.argv[3], encoding="utf-8"))
steps = workflow["jobs"]["publish"]["steps"]
release = next(s for s in steps if str(s.get("uses", "")).startswith("softprops/action-gh-release@"))
files = release["with"]["files"].split()
expected = [f"dist/{a}" for a in stage] + ["dist/sha256sums.txt"]
if files != expected:
    raise SystemExit(f"VERIFY OSS-D: release.yml files {files} != staged assets {expected}")
PY
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
# DR-19 companion: the old equality leg fed the tag FROM package.json, so tag/version agreement
# could never fail from this caller (a denominator tautology). A mismatched-but-valid tag must
# fail for the equality reason specifically.
if SPLICE_RELEASE_TAG="v0.0.0-mismatch" bash checks/release/stage.sh >"$invalid_tag_log" 2>&1; then
  echo "VERIFY OSS-D: mismatched release tag unexpectedly passed" >&2
  exit 1
fi
grep -q "does not match package version" "$invalid_tag_log" || {
  echo "VERIFY OSS-D: mismatched tag failed for the wrong reason" >&2
  exit 1
}
( cd gateway && ./gradlew -q :app:shadowJar --no-daemon --no-parallel )
SPLICE_RELEASE_TAG="v$VERSION" bash checks/release/stage.sh
SPLICE_EXPECTED_VERSION="$VERSION" bash checks/release/accept.sh
echo "VERIFY OSS-D: OK"
