#!/usr/bin/env bash
# checks/e2e/docker/run.sh — prove a build on a NEW MACHINE: build the fresh-machine image, mount
# the artifacts and the checkout read-only, run checks/e2e/docker/inside.sh with no network, and
# keep the receipt.
#
# What gets installed is chosen ONCE, explicitly:
#   --release vX.Y.Z      the published GitHub release assets (jar, shim, sha256sums, install.sh)
#   --jar P --shim P      any prebuilt pair (sha256sums.txt beside the jar is verified if present)
#   (default)             this checkout: :app:shadowJar via buildgate when present, bin/splice-launch
#
# Usage: checks/e2e/docker/run.sh [--release vX.Y.Z | --jar PATH --shim PATH] [--keep] [--no-build]
#   --keep       keep the artifacts scratch dir and print its path
#   --no-build   reuse the image if it exists (skips docker build)
# Env: SPLICE_E2E_CLAUDE_VERSION — Claude Code version baked into the image (default: the host's
#      `claude --version`, else latest). NOT CLAUDE_CODE_VERSION: Claude Code exports that one to
#      its own child shells as "X.Y.Z (Claude Code)", which is not an npm tag.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
IMAGE="splice-e2e-fresh:local"
RELEASE=""; JAR=""; SHIM=""; KEEP=0; BUILD=1
while [ $# -gt 0 ]; do
  case "$1" in
    --release) RELEASE="$2"; shift 2 ;;
    --jar) JAR="$2"; shift 2 ;;
    --shim) SHIM="$2"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    --no-build) BUILD=0; shift ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "run.sh: unknown arg $1" >&2; exit 2 ;;
  esac
done

command -v docker >/dev/null || { echo "run.sh: docker is required" >&2; exit 2; }

ART="$(mktemp -d "${TMPDIR:-/tmp}/splice-e2e-artifacts.XXXXXX")"
OUT="$ROOT/checks/e2e/receipts"
mkdir -p "$OUT"
cleanup() { [ "$KEEP" = 1 ] && echo "artifacts kept at $ART" || rm -rf "$ART"; }
trap cleanup EXIT

if [ -n "$RELEASE" ]; then
  echo "run.sh: downloading release $RELEASE"
  gh release download "$RELEASE" -R torad-labs/splice -D "$ART" \
    -p splice.jar -p splice-launch -p sha256sums.txt -p install.sh
elif [ -n "$JAR" ]; then
  [ -n "$SHIM" ] || { echo "run.sh: --jar needs --shim" >&2; exit 2; }
  cp "$JAR" "$ART/splice.jar"; cp "$SHIM" "$ART/splice-launch"
  [ -f "$(dirname "$JAR")/sha256sums.txt" ] && cp "$(dirname "$JAR")/sha256sums.txt" "$ART/"
else
  echo "run.sh: building the fat jar from this checkout"
  if command -v buildgate >/dev/null; then
    (cd "$ROOT/gateway" && buildgate ./gradlew -q :app:shadowJar)
  else
    (cd "$ROOT/gateway" && ./gradlew -q :app:shadowJar)
  fi
  cp "$ROOT/gateway/app/build/libs/app-all.jar" "$ART/splice.jar"
  cp "$ROOT/bin/splice-launch" "$ART/splice-launch"
fi
chmod 0755 "$ART"; chmod 0644 "$ART"/*
echo "run.sh: artifacts: $(sha256sum "$ART/splice.jar" | cut -c1-16)… splice.jar, $(sha256sum "$ART/splice-launch" | cut -c1-16)… splice-launch"

if [ "$BUILD" = 1 ] || ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  CC_VERSION="$(printf '%s' "${SPLICE_E2E_CLAUDE_VERSION:-$(claude --version 2>/dev/null)}" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)"
  echo "run.sh: building $IMAGE (Claude Code ${CC_VERSION:-latest})"
  docker build -q --build-arg "CLAUDE_CODE_VERSION=${CC_VERSION:-latest}" --build-arg "UID=$(id -u)" \
    -t "$IMAGE" "$ROOT/checks/e2e/docker" >/dev/null
fi

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
RUN_OUT="$(mktemp -d "${TMPDIR:-/tmp}/splice-e2e-out.XXXXXX")"
chmod 0777 "$RUN_OUT"
echo "run.sh: running inside.sh with --network none"
set +e
docker run --rm --network none \
  -v "$ART:/artifacts:ro" -v "$ROOT:/repo:ro" -v "$RUN_OUT:/out" \
  "$IMAGE" bash /repo/checks/e2e/docker/inside.sh
RC=$?
set -e
if [ -f "$RUN_OUT/receipt.json" ]; then
  cp "$RUN_OUT/receipt.json" "$OUT/docker-$STAMP.json"
  mkdir -p "$OUT/docker-$STAMP" && cp -r "$RUN_OUT"/. "$OUT/docker-$STAMP/"
  echo "run.sh: receipt $OUT/docker-$STAMP.json (steps + daemon.log in $OUT/docker-$STAMP/)"
fi
rm -rf "$RUN_OUT" 2>/dev/null || true
exit "$RC"
