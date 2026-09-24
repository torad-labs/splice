#!/usr/bin/env bash
# tools/e2e/docker/run.sh — prove a build on a NEW MACHINE: build the fresh-machine image, mount
# the artifacts and the checkout read-only, run tools/e2e/docker/inside.sh with no network, and
# keep the receipt.
#
# What gets installed is chosen ONCE, explicitly:
#   --release vX.Y.Z      the published GitHub release assets (jar, shim, sha256sums, install.sh)
#   --jar P --shim P      any prebuilt pair (sha256sums.txt beside the jar is verified if present)
#   (default)             this checkout: :app:shadowJar via buildgate when present, app/src/main/dist/bin/splice-launch
#
# Usage: tools/e2e/docker/run.sh [--release vX.Y.Z | --jar PATH --shim PATH] [--upgrade-from vX.Y.Z] [--keep] [--no-build]
#   --upgrade-from vX.Y.Z  instead of a fresh machine, run tools/e2e/docker/upgrade.sh: install that
#                published release, use it, then install the build chosen above over it. Refused when
#                the two shims carry the same version marker, since that upgrade cannot replace the daemon.
#   --keep       keep the artifacts scratch dir and print its path
#   --no-build   reuse the image if it exists (skips docker build); the in-image version check still
#                fails if that image does not match Versions.kt's tested Claude Code pin.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
TESTED_CLAUDE_CODE="$(sed -nE 's/^public const val TESTED_CLAUDE_CODE: String = "([0-9]+(\.[0-9]+)+)"$/\1/p' \
  "$ROOT/core/src/main/kotlin/splice/core/Versions.kt" | head -1)"
[ -n "$TESTED_CLAUDE_CODE" ] || { echo "run.sh: TESTED_CLAUDE_CODE is missing or malformed" >&2; exit 2; }
IMAGE="splice-e2e-fresh:local"
RELEASE=""; JAR=""; SHIM=""; KEEP=0; BUILD=1; UPGRADE_FROM=""
while [ $# -gt 0 ]; do
  case "$1" in
    --release) RELEASE="$2"; shift 2 ;;
    --jar) JAR="$2"; shift 2 ;;
    --shim) SHIM="$2"; shift 2 ;;
    --upgrade-from) UPGRADE_FROM="$2"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    --no-build) BUILD=0; shift ;;
    -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
    *) echo "run.sh: unknown arg $1" >&2; exit 2 ;;
  esac
done

command -v docker >/dev/null || { echo "run.sh: docker is required" >&2; exit 2; }

ART="$(mktemp -d "${TMPDIR:-/tmp}/splice-e2e-artifacts.XXXXXX")"
FROM_ART=""
RUN_OUT=""
OUT="$ROOT/tools/e2e/receipts"
mkdir -p "$OUT"
# One trap covers EVERY temp dir on every exit path (review of #116: RUN_OUT used to be removed by
# a plain rm behind three failure-capable copies, so a failed archive leaked a 0777 tree in TMPDIR).
cleanup() {
  if [ "$KEEP" = 1 ]; then
    echo "artifacts kept at $ART${FROM_ART:+, $UPGRADE_FROM at $FROM_ART}${RUN_OUT:+, run output at $RUN_OUT}"
  else
    rm -rf "$ART"
    [ -z "$FROM_ART" ] || rm -rf "$FROM_ART"
    [ -z "$RUN_OUT" ] || rm -rf "$RUN_OUT" 2>/dev/null || true
  fi
}
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
    (cd "$ROOT" && buildgate ./gradlew -q :app:shadowJar)
  else
    (cd "$ROOT" && ./gradlew -q :app:shadowJar)
  fi
  cp "$ROOT/app/build/libs/app-all.jar" "$ART/splice.jar"
  cp "$ROOT/app/src/main/dist/bin/splice-launch" "$ART/splice-launch"
fi
chmod 0755 "$ART"; chmod 0644 "$ART"/*
echo "run.sh: artifacts: $(sha256sum "$ART/splice.jar" | cut -c1-16)… splice.jar, $(sha256sum "$ART/splice-launch" | cut -c1-16)… splice-launch"

# The launch shim's version marker, in either shim's spelling: the Node shim writes
# `const SPLICE_GATEWAY_VERSION = "x";`, the bash shim of 0.3.x `SPLICE_GATEWAY_VERSION="x"`.
shim_version() { sed -nE 's/^(const )?SPLICE_GATEWAY_VERSION ?= ?"([^"]+)";?$/\2/p' "$1" | head -1; }
SCENARIO="inside.sh"; MODE_ARGS=(); RECEIPT_NAME="docker"
if [ -n "$UPGRADE_FROM" ]; then
  FROM_ART="$(mktemp -d "${TMPDIR:-/tmp}/splice-e2e-from.XXXXXX")"
  echo "run.sh: downloading release $UPGRADE_FROM to upgrade from"
  gh release download "$UPGRADE_FROM" -R torad-labs/splice -D "$FROM_ART" \
    -p splice.jar -p splice-launch -p sha256sums.txt -p install.sh
  chmod 0755 "$FROM_ART"; chmod 0644 "$FROM_ART"/*
  FROM_VERSION="$(shim_version "$FROM_ART/splice-launch")"
  CANDIDATE_VERSION="$(shim_version "$ART/splice-launch")"
  [ -n "$FROM_VERSION" ] && [ -n "$CANDIDATE_VERSION" ] ||
    { echo "run.sh: a launch shim carries no SPLICE_GATEWAY_VERSION marker" >&2; exit 2; }
  if [ "$FROM_VERSION" = "$CANDIDATE_VERSION" ]; then
    echo "run.sh: the candidate says $CANDIDATE_VERSION, the same as $UPGRADE_FROM; its shim would never replace" \
      "the running $FROM_VERSION daemon, so the rehearsal would prove nothing. Bump the version first." >&2
    exit 2
  fi
  echo "run.sh: upgrade $UPGRADE_FROM ($FROM_VERSION) -> candidate ($CANDIDATE_VERSION)"
  # upgrade.sh installs the candidate through install.sh's RELEASE mode (the one-liner's path), which
  # verifies against the release's sha256sums.txt. A published candidate carries one; for a checkout
  # or --jar build it is written here, over the same two assets the release workflow sums.
  if [ ! -f "$ART/sha256sums.txt" ]; then
    (cd "$ART" && sha256sum splice.jar splice-launch > sha256sums.txt) && chmod 0644 "$ART/sha256sums.txt"
  fi
  SCENARIO="upgrade.sh"; RECEIPT_NAME="upgrade-$UPGRADE_FROM"
  MODE_ARGS=(-e "SPLICE_UPGRADE_FROM=$UPGRADE_FROM" -v "$FROM_ART:/from:ro")
fi

if [ "$BUILD" = 1 ] || ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  # The unprivileged tester account takes the host uid so the bind mounts read/write on both
  # sides; uid 0 cannot be that account (useradd exits 4 on a taken uid, and -o would make the
  # "unprivileged" user root). Refuse early with the reason instead of a bare useradd failure.
  HOST_UID="$(id -u)"
  [ "$HOST_UID" != 0 ] || { echo "run.sh: run as a non-root user (the image's tester account needs a non-zero uid)" >&2; exit 2; }
  echo "run.sh: building $IMAGE (Claude Code $TESTED_CLAUDE_CODE)"
  docker build -q --build-arg "CLAUDE_CODE_VERSION=$TESTED_CLAUDE_CODE" --build-arg "UID=$HOST_UID" \
    -t "$IMAGE" "$ROOT/tools/e2e/docker" >/dev/null
fi

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
RUN_OUT="$(mktemp -d "${TMPDIR:-/tmp}/splice-e2e-out.XXXXXX")"
chmod 0777 "$RUN_OUT"
echo "run.sh: running $SCENARIO with --network none"
set +e
docker run --rm --network none \
  -e "SPLICE_TESTED_CLAUDE_CODE=$TESTED_CLAUDE_CODE" \
  -v "$ART:/artifacts:ro" -v "$ROOT:/repo:ro" -v "$RUN_OUT:/out" "${MODE_ARGS[@]}" \
  "$IMAGE" bash "/repo/tools/e2e/docker/$SCENARIO"
RC=$?
set -e
if [ -f "$RUN_OUT/receipt.json" ]; then
  bun "$ROOT/tools/e2e/docker/receipt-selftest.ts" "$RUN_OUT/receipt.json" || RC=1
  cp "$RUN_OUT/receipt.json" "$OUT/$RECEIPT_NAME-$STAMP.json"
  mkdir -p "$OUT/$RECEIPT_NAME-$STAMP" && cp -r "$RUN_OUT"/. "$OUT/$RECEIPT_NAME-$STAMP/"
  echo "run.sh: receipt $OUT/$RECEIPT_NAME-$STAMP.json (steps + daemon.log in $OUT/$RECEIPT_NAME-$STAMP/)"
fi
exit "$RC"
