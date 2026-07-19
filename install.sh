#!/usr/bin/env bash
# splice installer (P5-INST) — no binaries in the repo; fetches the release jar to
# ~/.local/share/splice/, installs the launch shim, and links wrapper commands.
# Usage:
#   ./install.sh                 # from a checkout: build the jar, install from it
#   SPLICE_JAR=/path/to.jar ./install.sh   # install a prebuilt jar
#   curl -fsSL <raw>/install.sh | bash     # (release mode: downloads from GitHub Releases)
set -euo pipefail

SHARE_DIR="${SPLICE_SHARE_DIR:-$HOME/.local/share/splice}"
BIN_DIR="${SPLICE_BIN_DIR:-$HOME/.local/bin}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_DST="${SHARE_DIR}/splice.jar"
SHIM_SRC="${REPO_ROOT}/bin/splice-launch"

mkdir -p "$SHARE_DIR" "$BIN_DIR"

# 1. Obtain the jar. Prefer an explicit SPLICE_JAR, else build from the checkout, else (release
#    mode) download from GitHub Releases — the repo intentionally ships no binaries.
if [ -n "${SPLICE_JAR:-}" ]; then
  echo "splice: installing jar from $SPLICE_JAR"
  cp "$SPLICE_JAR" "$JAR_DST"
elif [ -f "${REPO_ROOT}/gateway/settings.gradle.kts" ]; then
  echo "splice: building the fat jar (./gradlew :app:shadowJar)…"
  ( cd "${REPO_ROOT}/gateway" && ./gradlew -q :app:shadowJar )
  BUILT="$(find "${REPO_ROOT}/gateway/app/build/libs" -name '*-all.jar' | head -1)"
  [ -n "$BUILT" ] || { echo "splice: build produced no fat jar" >&2; exit 1; }
  cp "$BUILT" "$JAR_DST"
else
  REPO="torad-labs/splice"
  if [ -n "${SPLICE_VERSION:-}" ]; then
    JAR_URL="https://github.com/${REPO}/releases/download/${SPLICE_VERSION}/splice.jar"
    SUMS_URL="https://github.com/${REPO}/releases/download/${SPLICE_VERSION}/sha256sums.txt"
  else
    JAR_URL="https://github.com/${REPO}/releases/latest/download/splice.jar"
    SUMS_URL="https://github.com/${REPO}/releases/latest/download/sha256sums.txt"
  fi
  echo "splice: downloading $JAR_URL"
  curl -fsSL "$JAR_URL" -o "$JAR_DST"

  echo "splice: verifying sha256 against $SUMS_URL"
  SUMS_TMP="$(mktemp)"
  curl -fsSL "$SUMS_URL" -o "$SUMS_TMP"
  if ! JAR_SUM_LINE="$(grep -E '[[:space:]]splice\.jar$' "$SUMS_TMP")"; then
    echo "splice: no splice.jar entry in sha256sums.txt — aborting" >&2
    exit 1
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    if ! ( cd "$SHARE_DIR" && echo "$JAR_SUM_LINE" | sha256sum -c - ); then
      echo "splice: sha256 verification FAILED for $JAR_DST" >&2
      exit 1
    fi
  elif command -v shasum >/dev/null 2>&1; then
    EXPECTED="$(echo "$JAR_SUM_LINE" | awk '{print $1}')"
    ACTUAL="$(shasum -a 256 "$JAR_DST" | awk '{print $1}')"
    if [ "$EXPECTED" != "$ACTUAL" ]; then
      echo "splice: sha256 verification FAILED for $JAR_DST (expected $EXPECTED, got $ACTUAL)" >&2
      exit 1
    fi
  else
    echo "splice: no sha256sum or shasum available — cannot verify jar integrity" >&2
    exit 1
  fi
  rm -f "$SUMS_TMP"
fi

# 2. Install the shared launch shim.
if [ -f "$SHIM_SRC" ]; then
  install -m 0755 "$SHIM_SRC" "${SHARE_DIR}/splice-launch"
else
  echo "splice: warning — launch shim not found at $SHIM_SRC" >&2
fi

# 3. Materialize the topology + link the wrapper commands (+ the `splice` command).
java -jar "$JAR_DST" init
SPLICE_JAR="$JAR_DST" java -jar "$JAR_DST" install --all

# 4. Fail loudly rather than report success when nothing actually landed. `install --all`
#    always links the `splice` admin command itself, so its absence means the install failed
#    (counting arbitrary symlinks would false-pass on unrelated tools already in BIN_DIR).
if [ ! -L "$BIN_DIR/splice" ]; then
  echo "splice: install failed — $BIN_DIR/splice was not created" >&2
  exit 1
fi

echo
echo "splice: installed  (jar: $JAR_DST)"
case ":$PATH:" in
  *":$BIN_DIR:"*)
    echo
    echo "Next:  splice setup      # sign in to your backends, then you're done"
    echo "       claudex           # Claude Code on ChatGPT Codex"
    echo "       claude-grok       # Claude Code on xAI Grok"
    ;;
  *)
    echo
    echo "Add $BIN_DIR to your PATH, then run:  splice setup"
    ;;
esac
