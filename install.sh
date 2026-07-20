#!/usr/bin/env bash
# splice installer (P5-INST) — no binaries in the repo; fetches the release jar to
# ~/.local/share/splice/, installs the launch shim, and links wrapper commands.
# Usage:
#   ./install.sh                 # from a checkout: build the jar, install from it
#   SPLICE_JAR=/path/to.jar ./install.sh   # prebuilt jar; sibling splice-launch is auto-detected
#   SPLICE_JAR=/path/to.jar SPLICE_SHIM=/path/to/splice-launch ./install.sh
#   curl -fsSL <raw>/install.sh | bash     # (release mode: downloads from GitHub Releases)
set -euo pipefail

SHARE_DIR="${SPLICE_SHARE_DIR:-$HOME/.local/share/splice}"
BIN_DIR="${SPLICE_BIN_DIR:-$HOME/.local/bin}"
SCRIPT_SOURCE="${BASH_SOURCE[0]-}"
REPO_ROOT=""
if [ -n "$SCRIPT_SOURCE" ]; then
  SOURCE_ROOT="$(cd "$(dirname "$SCRIPT_SOURCE")" && pwd)"
  if [ -f "${SOURCE_ROOT}/gateway/settings.gradle.kts" ]; then
    REPO_ROOT="$SOURCE_ROOT"
  fi
fi
JAR_DST="${SHARE_DIR}/splice.jar"
SHIM_SRC="${REPO_ROOT:+${REPO_ROOT}/bin/splice-launch}"

mkdir -p "$SHARE_DIR" "$BIN_DIR"
JAR_TMP="$(mktemp "${SHARE_DIR}/.splice.jar.XXXXXX")"
SHIM_TMP="$(mktemp "${SHARE_DIR}/.splice-launch.XXXXXX")"
JAR_BACKUP="$(mktemp "${SHARE_DIR}/.splice.jar.backup.XXXXXX")"
SHIM_BACKUP="$(mktemp "${SHARE_DIR}/.splice-launch.backup.XXXXXX")"
rm -f "$JAR_BACKUP" "$SHIM_BACKUP"
SUMS_TMP=""
cleanup() {
  rm -f "$JAR_TMP" "$SHIM_TMP" "$JAR_BACKUP" "$SHIM_BACKUP"
  [ -z "$SUMS_TMP" ] || rm -f "$SUMS_TMP"
}
trap cleanup EXIT

# verify_sum <file> <sums-file> <asset-name> — sha256 must match the published entry.
verify_sum() {
  local file="$1" sums="$2" name="$3" line expected actual escaped_name
  escaped_name="${name//./\.}"
  if ! line="$(grep -E "[[:space:]]${escaped_name}\$" "$sums")"; then
    echo "splice: no ${name} entry in sha256sums.txt — aborting" >&2
    exit 1
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    expected="$(echo "$line" | awk '{print $1}')"
    actual="$(sha256sum "$file" | awk '{print $1}')"
  elif command -v shasum >/dev/null 2>&1; then
    expected="$(echo "$line" | awk '{print $1}')"
    actual="$(shasum -a 256 "$file" | awk '{print $1}')"
  else
    echo "splice: no sha256sum or shasum available — cannot verify ${name} integrity" >&2
    exit 1
  fi
  if [ "$expected" != "$actual" ]; then
    echo "splice: sha256 verification FAILED for ${name} (expected $expected, got $actual)" >&2
    exit 1
  fi
  echo "${name}: OK"
}

# verify_attestation <file> <asset-name> <release-base> — remote release artifacts must
# be bound to this repository's GitHub Actions build provenance. Local file:// mirrors are
# acceptance fixtures assembled from the current checkout and cannot have a GitHub attestation.
verify_attestation() {
  local file="$1" name="$2" release_base="$3"
  case "$release_base" in
    file://*)
      echo "splice: local release base — skipping Sigstore attestation for ${name} (dev/acceptance artifact)" >&2
      ;;
    *)
      if ! command -v gh >/dev/null 2>&1; then
        echo "splice: GitHub CLI (gh) is required to verify remote release provenance — aborting" >&2
        echo "splice: install gh from https://cli.github.com/ and retry" >&2
        exit 1
      fi
      echo "splice: verifying build provenance attestation for ${name}"
      if ! gh attestation verify "$file" --repo torad-labs/splice; then
        echo "splice: attestation verification FAILED for ${name} — aborting" >&2
        exit 1
      fi
      echo "${name} attestation: OK"
      ;;
  esac
}

# 1. Obtain the jar. Prefer an explicit SPLICE_JAR, else build from the checkout, else (release
#    mode) download from GitHub Releases — the repo intentionally ships no binaries.
if [ -n "${SPLICE_JAR:-}" ]; then
  echo "splice: installing jar from $SPLICE_JAR"
  cp "$SPLICE_JAR" "$JAR_TMP"
  if [ -n "${SPLICE_SHIM:-}" ]; then
    SHIM_SRC="$SPLICE_SHIM"
  elif [ -f "$(dirname "$SPLICE_JAR")/splice-launch" ]; then
    SHIM_SRC="$(dirname "$SPLICE_JAR")/splice-launch"
  elif [ -x "${SHARE_DIR}/splice-launch" ]; then
    SHIM_SRC="${SHARE_DIR}/splice-launch"
  fi
elif [ -n "$REPO_ROOT" ]; then
  echo "splice: building the fat jar (./gradlew :app:shadowJar)…"
  ( cd "${REPO_ROOT}/gateway" && ./gradlew -q :app:shadowJar )
  BUILT="${REPO_ROOT}/gateway/app/build/libs/app-all.jar"
  [ -f "$BUILT" ] || { echo "splice: build produced no fat jar at $BUILT" >&2; exit 1; }
  cp "$BUILT" "$JAR_TMP"
else
  REPO="torad-labs/splice"
  if [ -n "${SPLICE_RELEASE_BASE_URL:-}" ]; then
    RELEASE_BASE="${SPLICE_RELEASE_BASE_URL%/}"
  elif [ -n "${SPLICE_VERSION:-}" ]; then
    RELEASE_BASE="https://github.com/${REPO}/releases/download/${SPLICE_VERSION}"
  else
    RELEASE_BASE="https://github.com/${REPO}/releases/latest/download"
  fi
  JAR_URL="${RELEASE_BASE}/splice.jar"
  SUMS_URL="${RELEASE_BASE}/sha256sums.txt"
  echo "splice: downloading $JAR_URL"
  curl -fsSL "$JAR_URL" -o "$JAR_TMP"
  echo "splice: verifying sha256 against $SUMS_URL"
  SUMS_TMP="$(mktemp)"
  curl -fsSL "$SUMS_URL" -o "$SUMS_TMP"
  verify_sum "$JAR_TMP" "$SUMS_TMP" splice.jar

  # A checksum binds the asset to sha256sums.txt; the attestation independently binds the
  # candidate bytes to this repository's release workflow.
  verify_attestation "$JAR_TMP" splice.jar "$RELEASE_BASE"

  # The launch shim is a release asset too — without it every wrapper symlink dangles.
  SHIM_URL="${RELEASE_BASE}/splice-launch"
  echo "splice: downloading $SHIM_URL"
  curl -fsSL "$SHIM_URL" -o "$SHIM_TMP"
  verify_sum "$SHIM_TMP" "$SUMS_TMP" splice-launch
  verify_attestation "$SHIM_TMP" splice-launch "$RELEASE_BASE"
  SHIM_SRC="$SHIM_TMP"
fi

# A standalone prebuilt JAR can bootstrap its matching release shim when it has no sibling.
if [ ! -f "$SHIM_SRC" ]; then
  JAR_VERSION="$(java -jar "$JAR_TMP" version | awk '/^splice / { print $2 }')"
  [ -n "$JAR_VERSION" ] || { echo "splice: prebuilt jar has no readable splice version" >&2; exit 1; }
  REPO="torad-labs/splice"
  RELEASE_BASE="${SPLICE_RELEASE_BASE_URL:-https://github.com/${REPO}/releases/download/v${JAR_VERSION}}"
  SUMS_TMP="$(mktemp)"
  curl -fsSL "${RELEASE_BASE}/sha256sums.txt" -o "$SUMS_TMP"
  curl -fsSL "${RELEASE_BASE}/splice-launch" -o "$SHIM_TMP"
  verify_sum "$SHIM_TMP" "$SUMS_TMP" splice-launch
  verify_attestation "$SHIM_TMP" splice-launch "$RELEASE_BASE"
  SHIM_SRC="$SHIM_TMP"
fi

JAR_VERSION_OUTPUT="$(java -jar "$JAR_TMP" version)"
case "$JAR_VERSION_OUTPUT" in
  "splice "*) ;;
  *) echo "splice: candidate jar failed validation: ${JAR_VERSION_OUTPUT:-<empty>}" >&2; exit 1 ;;
esac

# 2. Atomically replace each live artifact only after every download/validation succeeded.
# A failed install leaves the previous working jar and shim untouched.
if [ "$SHIM_SRC" != "$SHIM_TMP" ]; then
  install -m 0755 "$SHIM_SRC" "$SHIM_TMP"
else
  chmod 0755 "$SHIM_TMP"
fi
chmod 0644 "$JAR_TMP"
SHIM_DST="${SHARE_DIR}/splice-launch"
HAD_JAR=0
HAD_SHIM=0
if [ -f "$JAR_DST" ]; then
  cp -p "$JAR_DST" "$JAR_BACKUP"
  HAD_JAR=1
fi
if [ -f "$SHIM_DST" ]; then
  cp -p "$SHIM_DST" "$SHIM_BACKUP"
  HAD_SHIM=1
fi

restore_previous_artifacts() {
  if [ "$HAD_JAR" = 1 ]; then
    mv -f "$JAR_BACKUP" "$JAR_DST"
  else
    rm -f "$JAR_DST"
  fi
  if [ "$HAD_SHIM" = 1 ]; then
    mv -f "$SHIM_BACKUP" "$SHIM_DST"
  else
    rm -f "$SHIM_DST"
  fi
}

if ! mv -f "$JAR_TMP" "$JAR_DST" || ! mv -f "$SHIM_TMP" "$SHIM_DST"; then
  restore_previous_artifacts
  echo "splice: failed to commit candidate artifacts; previous installation restored" >&2
  exit 1
fi

# 3. Materialize the topology + atomically link the wrapper commands (+ the `splice` command).
# A CLI/preflight failure rolls the jar and shim back as one installation generation.
if ! java -jar "$JAR_DST" init ||
  ! SPLICE_JAR="$JAR_DST" java -jar "$JAR_DST" install --all; then
  restore_previous_artifacts
  echo "splice: command installation failed; previous jar and shim restored" >&2
  exit 1
fi

# 4. Fail loudly rather than report success when nothing actually landed. `install --all`
#    always links the `splice` admin command itself, so its absence means the install failed
#    (counting arbitrary symlinks would false-pass on unrelated tools already in BIN_DIR).
if [ ! -L "$BIN_DIR/splice" ] || [ ! -e "$BIN_DIR/splice" ]; then
  restore_previous_artifacts
  echo "splice: install failed — $BIN_DIR/splice missing or dangling" >&2
  exit 1
fi
rm -f "$JAR_BACKUP" "$SHIM_BACKUP"

echo
echo "splice: installed  (jar: $JAR_DST)"
case ":$PATH:" in
  *":$BIN_DIR:"*)
    echo
    echo "Next:  export OPENROUTER_API_KEY=…"
    echo "       splice setup      # install the supported API-key starter"
    echo "       claudeor          # Claude Code through OpenRouter"
    ;;
  *)
    echo
    echo "Add $BIN_DIR to your PATH, then run:  splice setup"
    ;;
esac
