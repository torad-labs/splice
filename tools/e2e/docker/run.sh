#!/usr/bin/env bash
# tools/e2e/docker/run.sh — prove a build on a NEW MACHINE: build the fresh-machine image, mount
# the artifacts and the checkout read-only, run tools/e2e/docker/inside.sh with no network, and
# keep the receipt.
#
# What gets installed is chosen ONCE, explicitly:
#   --release vX.Y.Z      published release assets (verified before the container runs)
#   --jar P --shim P      any prebuilt pair (sha256sums.txt beside the jar is verified if present)
#   (default)             this checkout: :app:shadowJar via a build wrapper if any, app/src/main/dist/bin/splice-launch
#
# Usage: tools/e2e/docker/run.sh [--release vX.Y.Z | --jar PATH --shim PATH] [--upgrade-from vX.Y.Z] [--scenario NAME] [--keep] [--no-build]
#   --upgrade-from vX.Y.Z  rehearse an upgrade from a published release (pre-0.4 installer or modern CLI).
#                --scenario upgrade-064 explicitly selects the modern scenario and requires a modern source.
#                Both paths refuse a candidate that reports the source's gateway version.
#   --scenario plan-limit  instead of a fresh machine, run tools/e2e/docker/plan-limit.sh: an Anthropic
#                plan-limit 429 through splice to the real Claude Code, which must wait it out and resume.
#   --plan-reset-s N  the plan-limit upstream resets N seconds after the turn's first request (default
#                90, which keeps CI short; a real five-hour window is 18000).
#   --probe-message TEXT  the plan-limit probe upstream's 429 says TEXT (V4-234: plant a phrase the client
#                reads as stop-waiting, and the probe step shows whether it reaches the client).
#   --scenario runtime-head  run tools/e2e/docker/runtime-head.sh: a local runtime head as `splice setup`
#                adds one, the real Claude Code through it in print mode (V4-232).
#   --client-model ID  the runtime row's client_model (default claude-sonnet-4-6, what setup writes);
#                `none` writes the row as setup wrote it before V4-232.
#   --runtime-model ID  the runtime row's own id (default bonsai-2-27b). With `--client-model none` and an id
#                spelled claude-… the client does not know, the window step prints what the client assumes for it.
#   --scenario no-node  run tools/e2e/docker/no-node.sh on a machine with Java and a natively installed Claude Code and no
#                Node anywhere: install.sh, `splice add codex`, claudex, four sessions, doctor, and curl missing.
#   --keep       keep the artifacts scratch dir and print its path
#   --no-build   reuse the image if it exists (skips docker build); the in-image version check still
#                fails if that image does not match Versions.kt's tested Claude Code pin.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
TESTED_CLAUDE_CODE="$(sed -nE 's/^((public|internal) )?const val TESTED_CLAUDE_CODE: String = "([0-9]+(\.[0-9]+)+)"$/\3/p' \
  "$ROOT/core/src/main/kotlin/splice/core/Versions.kt" | head -1)"
[ -n "$TESTED_CLAUDE_CODE" ] || { echo "run.sh: TESTED_CLAUDE_CODE is missing or malformed" >&2; exit 2; }
IMAGE="splice-e2e-fresh:local"
IMAGE_TARGET="fresh"
RELEASE=""; JAR=""; SHIM=""; KEEP=0; BUILD=1; UPGRADE_FROM=""; SCENARIO_NAME=""; PLAN_RESET_S=""; PROBE_MESSAGE=""
CLIENT_MODEL=""; RUNTIME_MODEL_ID=""
while [ $# -gt 0 ]; do
  case "$1" in
    --release) RELEASE="$2"; shift 2 ;;
    --jar) JAR="$2"; shift 2 ;;
    --shim) SHIM="$2"; shift 2 ;;
    --upgrade-from) UPGRADE_FROM="$2"; shift 2 ;;
    --scenario) SCENARIO_NAME="$2"; shift 2 ;;
    --plan-reset-s) PLAN_RESET_S="$2"; shift 2 ;;
    --probe-message) PROBE_MESSAGE="$2"; shift 2 ;;
    --client-model) CLIENT_MODEL="$2"; shift 2 ;;
    --runtime-model) RUNTIME_MODEL_ID="$2"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    --no-build) BUILD=0; shift ;;
    -h|--help) sed -n '2,/^set -euo pipefail/{ /^set -euo pipefail/!p; }' "$0"; exit 0 ;;
    *) echo "run.sh: unknown arg $1" >&2; exit 2 ;;
  esac
done
if [ -n "$PROBE_MESSAGE" ] && [ "$SCENARIO_NAME" != "plan-limit" ]; then
  echo "run.sh: --probe-message belongs to --scenario plan-limit" >&2; exit 2
fi
if [ -n "$PLAN_RESET_S" ]; then
  [ "$SCENARIO_NAME" = "plan-limit" ] || { echo "run.sh: --plan-reset-s belongs to --scenario plan-limit" >&2; exit 2; }
  [[ "$PLAN_RESET_S" =~ ^[1-9][0-9]*$ ]] || { echo "run.sh: --plan-reset-s takes whole seconds, got '$PLAN_RESET_S'" >&2; exit 2; }
fi
if [ -n "$CLIENT_MODEL" ]; then
  [ "$SCENARIO_NAME" = "runtime-head" ] || { echo "run.sh: --client-model belongs to --scenario runtime-head" >&2; exit 2; }
  [[ "$CLIENT_MODEL" =~ ^(none|claude-[a-z0-9.-]+)$ ]] || { echo "run.sh: --client-model takes claude-… or none, got '$CLIENT_MODEL'" >&2; exit 2; }
fi
if [ -n "$RUNTIME_MODEL_ID" ]; then
  [ "$SCENARIO_NAME" = "runtime-head" ] || { echo "run.sh: --runtime-model belongs to --scenario runtime-head" >&2; exit 2; }
  [[ "$RUNTIME_MODEL_ID" =~ ^[a-z0-9][a-z0-9.-]*$ ]] || { echo "run.sh: --runtime-model takes a model id, got '$RUNTIME_MODEL_ID'" >&2; exit 2; }
fi

release_tag() {
  [[ "$1" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)([+-][0-9A-Za-z][0-9A-Za-z.+-]*)?$ ]]
}
legacy_tag() { [[ "$1" =~ ^v0\.[0-3]\. ]]; }
for tag in "$RELEASE" "$UPGRADE_FROM"; do
  [ -z "$tag" ] || release_tag "$tag" || { echo "run.sh: invalid release tag: $tag" >&2; exit 2; }
done
if [ "$SCENARIO_NAME" = upgrade-064 ]; then
  [ -n "$UPGRADE_FROM" ] && ! legacy_tag "$UPGRADE_FROM" ||
    { echo "run.sh: --scenario upgrade-064 needs --upgrade-from v0.4.0 or later" >&2; exit 2; }
elif [ -n "$SCENARIO_NAME" ] && [ -n "$UPGRADE_FROM" ]; then
  echo "run.sh: --scenario $SCENARIO_NAME cannot be combined with --upgrade-from" >&2; exit 2
fi

manifest_lists() { # manifest basename
  awk -v name="$2" '$2 == name || $2 == "*" name { found=1 } END { exit !found }' "$1"
}

verify_asset() { # dir basename — reject absent, malformed or duplicate entries
  local sum
  sum=$(awk -v name="$2" '
    $2 == name || $2 == "*" name { n++; hash=$1; fields=NF }
    END { if (n != 1 || fields != 2 || length(hash) != 64 || hash ~ /[^a-fA-F0-9]/) exit 1; print hash }
  ' "$1/sha256sums.txt") || { echo "run.sh: expected one SHA-256 for $2 in $1/sha256sums.txt" >&2; return 1; }
  (cd "$1" && printf '%s  %s\n' "$sum" "$2" | sha256sum --check --strict)
}

release_jar_asset() { # dir — these Docker images use Linux amd64
  if manifest_lists "$1/sha256sums.txt" splice-linux-amd64.jar; then
    printf '%s\n' splice-linux-amd64.jar
  else
    printf '%s\n' splice.jar
  fi
}

fetch_release() { # tag dest
  local tag="$1" dest="$2" base="https://splice.torad.ai/download/$1" status asset
  local -a files
  echo "run.sh: downloading release $tag"
  status=$(curl -sSL -w '%{http_code}' -o "$dest/sha256sums.txt" "$base/sha256sums.txt")
  case "$status" in
    200)
      files=("$(release_jar_asset "$dest")" splice-launch install.sh)
      if manifest_lists "$dest/sha256sums.txt" splice-mcp-holder; then
        manifest_lists "$dest/sha256sums.txt" splice-mcp-front ||
          { echo "run.sh: $tag advertises only half of the MCP hub pair" >&2; return 1; }
        files+=(splice-mcp-holder splice-mcp-front)
      elif manifest_lists "$dest/sha256sums.txt" splice-mcp-front; then
        echo "run.sh: $tag advertises only half of the MCP hub pair" >&2; return 1
      fi
      for asset in "${files[@]}"; do
        curl -fsSL "$base/$asset" -o "$dest/$asset"
        verify_asset "$dest" "$asset"
      done
      ;;
    404)
      legacy_tag "$tag" || { echo "run.sh: no CDN manifest for $tag ($base)" >&2; return 1; }
      echo "run.sh: $tag predates CDN hosting; using its GitHub release"
      rm -f "$dest/sha256sums.txt"
      gh release download "$tag" -R torad-labs/splice -D "$dest" \
        -p splice.jar -p splice-launch -p sha256sums.txt -p install.sh
      for asset in splice.jar splice-launch install.sh; do verify_asset "$dest" "$asset"; done
      ;;
    *) echo "run.sh: $base/sha256sums.txt returned HTTP $status" >&2; return 1 ;;
  esac
  chmod 0755 "$dest"; chmod 0644 "$dest"/*
}

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

JAR_ASSET=splice.jar
if [ -n "$RELEASE" ]; then
  fetch_release "$RELEASE" "$ART"
  JAR_ASSET="$(release_jar_asset "$ART")"
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
echo "run.sh: artifacts: $(sha256sum "$ART/$JAR_ASSET" | cut -c1-16)… $JAR_ASSET, $(sha256sum "$ART/splice-launch" | cut -c1-16)… splice-launch"

# The launch shim's version marker, in either shim's spelling: the Node shim writes
# `const SPLICE_GATEWAY_VERSION = "x";`, the bash shim of 0.3.x `SPLICE_GATEWAY_VERSION="x"`.
shim_version() { sed -nE 's/^(const )?SPLICE_GATEWAY_VERSION ?= ?"([^"]+)";?$/\2/p' "$1" | head -1; }
SCENARIO="inside.sh"; MODE_ARGS=(); RECEIPT_NAME="docker"
if [ -n "$SCENARIO_NAME" ] && [ "$SCENARIO_NAME" != upgrade-064 ]; then
  case "$SCENARIO_NAME" in
    plan-limit) SCENARIO="plan-limit.sh"; RECEIPT_NAME="plan-limit" ;;
    runtime-head) SCENARIO="runtime-head.sh"; RECEIPT_NAME="runtime-head" ;;
    auto-mode) SCENARIO="auto-mode.sh"; RECEIPT_NAME="auto-mode"; IMAGE="splice-e2e-nonode:local"; IMAGE_TARGET="nonode" ;;
    no-node) SCENARIO="no-node.sh"; RECEIPT_NAME="no-node"; IMAGE="splice-e2e-nonode:local"; IMAGE_TARGET="nonode" ;;
    *) echo "run.sh: unknown scenario $SCENARIO_NAME (plan-limit, runtime-head, no-node, auto-mode)" >&2; exit 2 ;;
  esac
  [ -z "$PLAN_RESET_S" ] || MODE_ARGS+=(-e "PLAN_RESET_S=$PLAN_RESET_S")
  [ -z "$PROBE_MESSAGE" ] || MODE_ARGS+=(-e "PLAN_PROBE_MESSAGE=$PROBE_MESSAGE")
  [ -z "$CLIENT_MODEL" ] || MODE_ARGS+=(-e "RUNTIME_CLIENT_MODEL=$CLIENT_MODEL")
  [ -z "$RUNTIME_MODEL_ID" ] || MODE_ARGS+=(-e "RUNTIME_MODEL=$RUNTIME_MODEL_ID")
fi
if [ -n "$UPGRADE_FROM" ]; then
  FROM_ART="$(mktemp -d "${TMPDIR:-/tmp}/splice-e2e-from.XXXXXX")"
  fetch_release "$UPGRADE_FROM" "$FROM_ART"
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
  RECEIPT_NAME="upgrade-$UPGRADE_FROM"
  if legacy_tag "$UPGRADE_FROM"; then
    SCENARIO="upgrade.sh"
    IMAGE="splice-e2e-upgrade:local"; IMAGE_TARGET="upgrade"
  else
    SCENARIO="upgrade-064.sh"
  fi
  MODE_ARGS=(-e "SPLICE_UPGRADE_FROM=$UPGRADE_FROM" \
    -e "SPLICE_E2E_FROM_JAR_ASSET=$(release_jar_asset "$FROM_ART")" -v "$FROM_ART:/from:ro")
fi

if [ "$BUILD" = 1 ] || ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  # The unprivileged tester account takes the host uid so the bind mounts read/write on both
  # sides; uid 0 cannot be that account (useradd exits 4 on a taken uid, and -o would make the
  # "unprivileged" user root). Refuse early with the reason instead of a bare useradd failure.
  HOST_UID="$(id -u)"
  [ "$HOST_UID" != 0 ] || { echo "run.sh: run as a non-root user (the image's tester account needs a non-zero uid)" >&2; exit 2; }
  echo "run.sh: building $IMAGE (Claude Code $TESTED_CLAUDE_CODE)"
  docker build -q --target "$IMAGE_TARGET" --build-arg "CLAUDE_CODE_VERSION=$TESTED_CLAUDE_CODE" --build-arg "UID=$HOST_UID" \
    -t "$IMAGE" "$ROOT/tools/e2e/docker" >/dev/null
fi

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
RUN_OUT="$(mktemp -d "${TMPDIR:-/tmp}/splice-e2e-out.XXXXXX")"
chmod 0777 "$RUN_OUT"
echo "run.sh: running $SCENARIO with --network none"
set +e
docker run --rm --network none \
  -e "SPLICE_TESTED_CLAUDE_CODE=$TESTED_CLAUDE_CODE" -e "SPLICE_E2E_JAR_ASSET=$JAR_ASSET" \
  -v "$ART:/artifacts:ro" -v "$ROOT:/repo:ro" -v "$RUN_OUT:/out" "${MODE_ARGS[@]}" \
  "$IMAGE" bash "/repo/tools/e2e/docker/$SCENARIO"
RC=$?
set -e
if [ -f "$RUN_OUT/receipt.json" ]; then
  bun "$ROOT/tools/e2e/docker/receipt-selftest.ts" "$RUN_OUT/receipt.json" || RC=1
  cp "$RUN_OUT/receipt.json" "$OUT/$RECEIPT_NAME-$STAMP.json"
  mkdir -p "$OUT/$RECEIPT_NAME-$STAMP" && cp -r "$RUN_OUT"/. "$OUT/$RECEIPT_NAME-$STAMP/"
  echo "run.sh: receipt $OUT/$RECEIPT_NAME-$STAMP.json (steps + daemon.log in $OUT/$RECEIPT_NAME-$STAMP/)"
else
  echo "run.sh: $SCENARIO produced no receipt" >&2
  RC=1
fi
exit "$RC"
