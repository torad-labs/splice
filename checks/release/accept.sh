#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DIST="${1:-$ROOT/dist}"
EXPECTED_VERSION="${SPLICE_EXPECTED_VERSION:-}"

ASSETS=(
  splice.jar splice-launch install.sh
  LICENSE THIRD_PARTY_NOTICES.md THIRD_PARTY_LICENSES.txt PROVENANCE.md
  bom.cdx.json dependency-licenses.json
)
for asset in "${ASSETS[@]}" sha256sums.txt; do
  [ -f "$DIST/$asset" ] || { echo "release accept: missing $DIST/$asset" >&2; exit 1; }
done

jar_version="$(java -jar "$DIST/splice.jar" version)"
jar_version="${jar_version#splice }"
shim_gateway_version="$(
  awk -F'"' '/^SPLICE_GATEWAY_VERSION="/ { print $2; exit }' "$DIST/splice-launch"
)"
[ "$shim_gateway_version" = "$jar_version" ] || {
  echo "release accept: launcher expects gateway $shim_gateway_version but jar is $jar_version" >&2
  exit 1
}

(
  cd "$DIST"
  sha256sum -c sha256sums.txt
  [ "$(wc -l < sha256sums.txt)" -eq "${#ASSETS[@]}" ] || {
    echo "release accept: sha256sums.txt does not cover the exact asset set" >&2
    exit 1
  }
)

python3 - "$DIST" "$ROOT" <<'PY'
import json
import pathlib
import sys
import zipfile

dist = pathlib.Path(sys.argv[1])
root = pathlib.Path(sys.argv[2])
bom = json.loads((dist / "bom.cdx.json").read_text())
licenses = json.loads((dist / "dependency-licenses.json").read_text())
if bom.get("bomFormat") != "CycloneDX" or not bom.get("components"):
    raise SystemExit("release accept: SBOM is not a non-empty CycloneDX document")
if not licenses.get("dependencies"):
    raise SystemExit("release accept: dependency-license inventory is empty")

embedded = {
    "LICENSE": "META-INF/LICENSE",
    "THIRD_PARTY_NOTICES.md": "META-INF/THIRD_PARTY_NOTICES.md",
    "THIRD_PARTY_LICENSES.txt": "META-INF/THIRD_PARTY_LICENSES.txt",
    "PROVENANCE.md": "META-INF/PROVENANCE.md",
    "bom.cdx.json": "META-INF/bom.cdx.json",
    "dependency-licenses.json": "META-INF/dependency-licenses.json",
}
with zipfile.ZipFile(dist / "splice.jar") as jar:
    for sidecar, entry in embedded.items():
        if jar.read(entry) != (dist / sidecar).read_bytes():
            raise SystemExit(f"release accept: {sidecar} differs from {entry} in splice.jar")
    if jar.read("webui/index.html") != (root / "webui/dist/index.html").read_bytes():
        raise SystemExit("release accept: packaged dashboard differs from committed webui dist")
PY

run_install() {
  local assets="$1" sandbox="$2" output="$3"
  mkdir -p "$sandbox/home" "$sandbox/share" "$sandbox/bin" "$sandbox/work"
  (
    cd "$sandbox/work"
    HOME="$sandbox/home" \
    JAVA_TOOL_OPTIONS="-Duser.home=$sandbox/home" \
    SPLICE_SHARE_DIR="$sandbox/share" \
    SPLICE_BIN_DIR="$sandbox/bin" \
    SPLICE_RELEASE_BASE_URL="file://$assets" \
    PATH="$sandbox/bin:$PATH" \
      bash -s < "$assets/install.sh"
  ) >"$output" 2>&1
}

assert_failed_without_success() {
  local assets="$1" label="$2" sandbox output
  sandbox="$(mktemp -d)"
  output="$sandbox/output.log"
  if run_install "$assets" "$sandbox" "$output"; then
    echo "release accept: $label unexpectedly succeeded" >&2
    cat "$output" >&2
    exit 1
  fi
  if grep -q "splice: installed" "$output"; then
    echo "release accept: $label printed success while failing" >&2
    cat "$output" >&2
    exit 1
  fi
  rm -rf "$sandbox"
}

sandbox="$(mktemp -d)"
output="$sandbox/output.log"
run_install "$DIST" "$sandbox" "$output" || { cat "$output" >&2; exit 1; }
[ -L "$sandbox/bin/splice" ] && [ -e "$sandbox/bin/splice" ] || {
  echo "release accept: installed splice command is missing or dangling" >&2
  exit 1
}
version="$(
  HOME="$sandbox/home" \
  JAVA_TOOL_OPTIONS="-Duser.home=$sandbox/home" \
  SPLICE_SHARE_DIR="$sandbox/share" \
  SPLICE_BIN_DIR="$sandbox/bin" \
  SPLICE_JAR="$sandbox/share/splice.jar" \
    "$sandbox/bin/splice" version
)"
case "$version" in
  "splice "*) ;;
  *) echo "release accept: unexpected version output: $version" >&2; exit 1 ;;
esac
if [ -n "$EXPECTED_VERSION" ] && [ "$version" != "splice $EXPECTED_VERSION" ]; then
  echo "release accept: expected 'splice $EXPECTED_VERSION', got '$version'" >&2
  exit 1
fi
rm -rf "$sandbox"

# Exercise the genuine remote-release path without network access. The fake downloader serves
# the staged assets, while the fake GitHub CLI records which candidates were attested.
remote_tools="$(mktemp -d)"
remote_log="$remote_tools/gh.log"
cat > "$remote_tools/curl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
cp "$SPLICE_FAKE_RELEASE_ASSETS/$(basename "$2")" "$4"
SH
cat > "$remote_tools/gh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$SPLICE_FAKE_GH_LOG"
SH
chmod +x "$remote_tools/curl" "$remote_tools/gh"
sandbox="$(mktemp -d)"
output="$sandbox/output.log"
mkdir -p "$sandbox/home" "$sandbox/share" "$sandbox/bin" "$sandbox/work"
(
  cd "$sandbox/work"
  HOME="$sandbox/home" \
  JAVA_TOOL_OPTIONS="-Duser.home=$sandbox/home" \
  SPLICE_SHARE_DIR="$sandbox/share" \
  SPLICE_BIN_DIR="$sandbox/bin" \
  SPLICE_RELEASE_BASE_URL="https://example.invalid/splice-release" \
  SPLICE_FAKE_RELEASE_ASSETS="$DIST" \
  SPLICE_FAKE_GH_LOG="$remote_log" \
  PATH="$remote_tools:$sandbox/bin:$PATH" \
    bash -s < "$DIST/install.sh"
) >"$output" 2>&1 || { cat "$output" >&2; exit 1; }
[ "$(grep -c '^attestation verify ' "$remote_log")" -eq 2 ] || {
  echo "release accept: remote install did not attest both release artifacts" >&2
  cat "$remote_log" >&2
  exit 1
}
grep -q "splice.jar attestation: OK" "$output"
grep -q "splice-launch attestation: OK" "$output"

# A failed provenance check must abort before the candidate artifacts become live.
cat > "$remote_tools/gh" <<'SH'
#!/usr/bin/env bash
exit 1
SH
printf 'previous jar\n' > "$sandbox/share/splice.jar"
printf 'previous shim\n' > "$sandbox/share/splice-launch"
if (
  cd "$sandbox/work"
  HOME="$sandbox/home" \
  JAVA_TOOL_OPTIONS="-Duser.home=$sandbox/home" \
  SPLICE_SHARE_DIR="$sandbox/share" \
  SPLICE_BIN_DIR="$sandbox/bin" \
  SPLICE_RELEASE_BASE_URL="https://example.invalid/splice-release" \
  SPLICE_FAKE_RELEASE_ASSETS="$DIST" \
  PATH="$remote_tools:$sandbox/bin:$PATH" \
    bash -s < "$DIST/install.sh"
) >"$output" 2>&1; then
  echo "release accept: failed provenance verification unexpectedly installed" >&2
  exit 1
fi
[ "$(cat "$sandbox/share/splice.jar")" = "previous jar" ]
[ "$(cat "$sandbox/share/splice-launch")" = "previous shim" ]
rm -rf "$remote_tools" "$sandbox"

bad_sum="$(mktemp -d)"
cp -a "$DIST/." "$bad_sum/"
printf '\ncorrupt\n' >> "$bad_sum/splice.jar"
assert_failed_without_success "$bad_sum" "checksum mismatch"
rm -rf "$bad_sum"

missing_shim="$(mktemp -d)"
cp -a "$DIST/." "$missing_shim/"
rm "$missing_shim/splice-launch"
assert_failed_without_success "$missing_shim" "missing launcher"
rm -rf "$missing_shim"

dangling="$(mktemp -d)"
cp -a "$DIST/." "$dangling/"
printf '#!/usr/bin/env bash\nset -euo pipefail\nif [ "${3:-}" = version ]; then echo "splice test"; exit 0; fi\nif [ "${3:-}" = init ]; then exit 0; fi\nif [ "${3:-}" = install ]; then ln -s /missing "$SPLICE_BIN_DIR/splice"; fi\n' > "$dangling/java"
chmod +x "$dangling/java"
sandbox="$(mktemp -d)"
output="$sandbox/output.log"
mkdir -p "$sandbox/home" "$sandbox/share" "$sandbox/bin" "$sandbox/work"
if (
  cd "$sandbox/work"
  HOME="$sandbox/home" \
  JAVA_TOOL_OPTIONS="-Duser.home=$sandbox/home" \
  SPLICE_SHARE_DIR="$sandbox/share" \
  SPLICE_BIN_DIR="$sandbox/bin" \
  SPLICE_RELEASE_BASE_URL="file://$DIST" \
  PATH="$dangling:$PATH" \
    bash -s < "$DIST/install.sh"
) >"$output" 2>&1; then
  echo "release accept: dangling command unexpectedly succeeded" >&2
  cat "$output" >&2
  exit 1
fi
if grep -q "splice: installed" "$output"; then
  echo "release accept: dangling command printed success" >&2
  cat "$output" >&2
  exit 1
fi
rm -rf "$dangling" "$sandbox"

rollback_java="$(mktemp -d)"
printf '#!/usr/bin/env bash\nset -euo pipefail\nif [ "${3:-}" = version ]; then echo "splice test"; exit 0; fi\nif [ "${3:-}" = init ]; then exit 0; fi\nif [ "${3:-}" = install ]; then exit 9; fi\n' > "$rollback_java/java"
chmod +x "$rollback_java/java"
sandbox="$(mktemp -d)"
output="$sandbox/output.log"
mkdir -p "$sandbox/home" "$sandbox/share" "$sandbox/bin" "$sandbox/work"
printf 'previous jar\n' > "$sandbox/share/splice.jar"
printf 'previous shim\n' > "$sandbox/share/splice-launch"
if (
  cd "$sandbox/work"
  HOME="$sandbox/home" \
  JAVA_TOOL_OPTIONS="-Duser.home=$sandbox/home" \
  SPLICE_SHARE_DIR="$sandbox/share" \
  SPLICE_BIN_DIR="$sandbox/bin" \
  SPLICE_RELEASE_BASE_URL="file://$DIST" \
  PATH="$rollback_java:$PATH" \
    bash -s < "$DIST/install.sh"
) >"$output" 2>&1; then
  echo "release accept: post-commit install failure unexpectedly succeeded" >&2
  cat "$output" >&2
  exit 1
fi
[ "$(cat "$sandbox/share/splice.jar")" = "previous jar" ] || {
  echo "release accept: failed install did not restore previous jar" >&2
  exit 1
}
[ "$(cat "$sandbox/share/splice-launch")" = "previous shim" ] || {
  echo "release accept: failed install did not restore previous shim" >&2
  exit 1
}
rm -rf "$rollback_java" "$sandbox"

echo "release accept: OK ($version)"
