#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DIST="${1:-$ROOT/dist}"
TAG="${SPLICE_RELEASE_TAG:-}"
if [ "${GITHUB_REF_TYPE:-}" = "tag" ]; then
  TAG="${GITHUB_REF_NAME:-}"
fi
VERSION="$(node -p "require('$ROOT/package.json').version")"
LOCK_VERSION="$(node -p "require('$ROOT/package-lock.json').version")"
LOCK_ROOT_VERSION="$(node -p "require('$ROOT/package-lock.json').packages[''].version")"
[ "$LOCK_VERSION" = "$VERSION" ] && [ "$LOCK_ROOT_VERSION" = "$VERSION" ] || {
  echo "release stage: package-lock versions ($LOCK_VERSION, $LOCK_ROOT_VERSION) do not match $VERSION" >&2
  exit 1
}
if [ -n "$TAG" ]; then
  [[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
    echo "release stage: tag must be vMAJOR.MINOR.PATCH, got $TAG" >&2
    exit 1
  }
  [ "$TAG" = "v$VERSION" ] || {
    echo "release stage: tag $TAG does not match package version $VERSION" >&2
    exit 1
  }
fi
JAR="${SPLICE_RELEASE_JAR:-$ROOT/gateway/app/build/libs/app-all.jar}"
COMPLIANCE="${SPLICE_COMPLIANCE_DIR:-$ROOT/gateway/app/build/reports/compliance}"
ASSETS=(
  splice.jar splice-launch install.sh
  LICENSE THIRD_PARTY_NOTICES.md THIRD_PARTY_LICENSES.txt PROVENANCE.md
  bom.cdx.json dependency-licenses.json
)

[ -f "$JAR" ] || { echo "release stage: expected fat jar missing at $JAR" >&2; exit 1; }
for report in bom.cdx.json dependency-licenses.json; do
  [ -f "$COMPLIANCE/$report" ] || { echo "release stage: compliance report missing at $COMPLIANCE/$report" >&2; exit 1; }
done
JAR_VERSION="$(java -jar "$JAR" version)"
[ "$JAR_VERSION" = "splice $VERSION" ] || {
  echo "release stage: package version $VERSION does not match '$JAR_VERSION'" >&2
  exit 1
}
rm -rf "$DIST"
mkdir -p "$DIST"

install -m 0644 "$JAR" "$DIST/splice.jar"
install -m 0755 "$ROOT/bin/splice-launch" "$DIST/splice-launch"
install -m 0755 "$ROOT/install.sh" "$DIST/install.sh"
install -m 0644 "$ROOT/LICENSE" "$ROOT/THIRD_PARTY_NOTICES.md" "$ROOT/PROVENANCE.md" "$DIST/"
install -m 0644 \
  "$COMPLIANCE/bom.cdx.json" \
  "$COMPLIANCE/dependency-licenses.json" \
  "$COMPLIANCE/THIRD_PARTY_LICENSES.txt" \
  "$DIST/"

(
  cd "$DIST"
  sha256sum "${ASSETS[@]}" > sha256sums.txt
)

echo "release stage: $DIST"
