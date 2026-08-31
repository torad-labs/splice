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
#
# DR-19 redo (2026-08-31, codex catch): the old raw grep read YAML as text, so the required line
# satisfied the gate from a COMMENT while the parsed run was an ungated bare command. Parse the
# workflow instead: exactly one step runs stage.sh, and its exact run + env values are pinned —
# comments never survive yaml.safe_load, so the decoy class is structurally dead. The mutant
# below proves the check can fail on exactly that class, every run.
stage_step_check() {
  python3 - "$1" <<'PY'
import sys
import yaml
workflow = yaml.safe_load(open(sys.argv[1], encoding="utf-8"))
steps = [s for job in workflow["jobs"].values() for s in job.get("steps", [])
         if "checks/release/stage.sh" in str(s.get("run", ""))]
if len(steps) != 1:
    raise SystemExit(f"VERIFY OSS-D: expected exactly one parsed stage.sh step, found {len(steps)}")
step = steps[0]
gated = 'SPLICE_RELEASE_TAG="v$RESOLVED_VERSION" bash checks/release/stage.sh'
if step.get("run") != gated:
    raise SystemExit(f"VERIFY OSS-D: stage step run is not the gated promotion command: {step.get('run')!r}")
if step.get("env", {}).get("RESOLVED_VERSION") != "${{ steps.version.outputs.version }}":
    raise SystemExit(f"VERIFY OSS-D: stage step env does not thread the resolved version: {step.get('env')!r}")
PY
}
stage_step_check .github/workflows/release.yml
stage_decoy="$(mktemp)"
sed 's|run: SPLICE_RELEASE_TAG="v$RESOLVED_VERSION" bash checks/release/stage.sh|run: bash checks/release/stage.sh # SPLICE_RELEASE_TAG="v$RESOLVED_VERSION" bash checks/release/stage.sh|' \
  .github/workflows/release.yml > "$stage_decoy"
if stage_step_check "$stage_decoy" 2>/dev/null; then
  rm -f "$stage_decoy"
  echo "VERIFY OSS-D: comment-decoy mutant unexpectedly passed — the stage-step check cannot fail" >&2
  exit 1
fi
rm -f "$stage_decoy"
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

# DR-25 redo (codex catch, 2026-08-31): the asset denominator came from stage.sh's hand list, so a
# staged file OUTSIDE that list shipped absent from checksums/release under a green verifier — the
# denominator must come from the ACTUAL dist/. Every real file needs a disposition: published (the
# cross-checked ASSETS+sums set) or excluded here with a written reason. Both mutants below prove
# the new legs can fail, every run: an undispositioned extra file, and a zero-byte artifact (whose
# red must be accept.sh's EMPTY leg, not a checksum coincidence).
dist_enumeration_check() {
  python3 - "$1" checks/release/stage.sh <<'PY'
import pathlib
import re
import sys
dist = pathlib.Path(sys.argv[1])
text = open(sys.argv[2], encoding="utf-8").read()
assets = re.search(r"^ASSETS=\((.*?)\)$", text, re.S | re.M).group(1).split()
published = set(assets) | {"sha256sums.txt"}
excluded: dict[str, str] = {}  # name -> written reason; empty today, additions REQUIRE a reason
actual = {p.name for p in dist.iterdir()}  # dirs too: EVERY entry needs a disposition
unaccounted = sorted(actual - published - set(excluded))
missing = sorted(published - actual)
if unaccounted:
    raise SystemExit(f"VERIFY OSS-D: dist/ files with NO disposition (publish or exclude-with-reason): {unaccounted}")
if missing:
    raise SystemExit(f"VERIFY OSS-D: published set missing from actual dist/: {missing}")
PY
}
dist_enumeration_check dist
dist_mutant="$(mktemp -d)"
trap 'rm -f "$invalid_tag_log"; rm -rf "$dist_mutant"' EXIT
cp -a dist/. "$dist_mutant/"
echo stray > "$dist_mutant/EXTRA-README.md"
extra_log="$(mktemp)"
if dist_enumeration_check "$dist_mutant" >"$extra_log" 2>&1; then
  rm -f "$extra_log"
  echo "VERIFY OSS-D: extra-file mutant unexpectedly passed — the enumeration cannot fail" >&2
  exit 1
fi
grep -q "NO disposition" "$extra_log" || {
  rm -f "$extra_log"
  echo "VERIFY OSS-D: extra-file mutant failed for the wrong reason (not the disposition leg)" >&2
  exit 1
}
rm -f "$extra_log"
rm -f "$dist_mutant/EXTRA-README.md"
: > "$dist_mutant/PROVENANCE.md"
empty_log="$(mktemp)"
if SPLICE_EXPECTED_VERSION="$VERSION" bash checks/release/accept.sh "$dist_mutant" >"$empty_log" 2>&1; then
  rm -f "$empty_log"
  echo "VERIFY OSS-D: empty-artifact mutant unexpectedly passed accept.sh" >&2
  exit 1
fi
grep -q "EMPTY" "$empty_log" || {
  rm -f "$empty_log"
  echo "VERIFY OSS-D: empty-artifact mutant failed for the wrong reason (not the EMPTY leg)" >&2
  exit 1
}
rm -f "$empty_log"
echo "VERIFY OSS-D: OK"
