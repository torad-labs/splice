#!/usr/bin/env bash
# splice installer (P5-INST) — no binaries in the repo; fetches the release jar to
# ~/.local/share/splice/, installs the launch shim, and links wrapper commands.
# Platforms: Linux and macOS natively; Windows via WSL2 (native shells are refused with guidance).
# Every dependency is checked up front with the exact per-machine fix; on an interactive terminal
# the installer offers to run package-manager fixes itself (always with consent, never silently).
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
  if [ -f "${SOURCE_ROOT}/settings.gradle.kts" ]; then
    REPO_ROOT="$SOURCE_ROOT"
  fi
fi

# ── Platform + dependency preflight ────────────────────────────────────────────────────────────
# The launch shim and daemon are Unix programs: Linux (including WSL2) and macOS are supported;
# a native Windows shell gets pointed at WSL2 instead of failing later with something cryptic.
case "$(uname -s)" in
  Linux | Darwin) ;;
  MINGW* | MSYS* | CYGWIN*)
    echo "splice: native Windows shells are not supported — the launch shim and daemon target Unix." >&2
    echo "splice: install WSL2 (PowerShell:  wsl --install ), open the WSL shell, and re-run this" >&2
    echo "splice: installer inside it — everything then works exactly as on Linux." >&2
    exit 1
    ;;
  *)
    echo "splice: unsupported platform '$(uname -s)' — Linux, macOS, and Windows/WSL2 are supported" >&2
    exit 1
    ;;
esac

# The package-manager prefix for this machine, if one is recognized (empty otherwise).
PKG=""
if command -v apt-get >/dev/null 2>&1; then PKG="sudo apt-get install -y"
elif command -v dnf >/dev/null 2>&1; then PKG="sudo dnf install -y"
elif command -v pacman >/dev/null 2>&1; then PKG="sudo pacman -S --noconfirm"
elif command -v zypper >/dev/null 2>&1; then PKG="sudo zypper install -y"
elif command -v brew >/dev/null 2>&1; then PKG="brew install"
fi

# offer_fix <name> <fix-command> — print the exact fix; on an interactive terminal offer to run
# it now (explicit consent, default No; piped installs only print). Returns 0 when the fix ran ok.
offer_fix() {
  local name="$1" fix="$2" answer=""
  echo "splice:   fix: $fix"
  if [ -t 0 ] && [ -t 1 ]; then
    printf 'splice:   run that now to install %s? [y/N] ' "$name"
    read -r answer
    case "$answer" in
      y | Y | yes | YES)
        if bash -c "$fix"; then
          echo "splice:   $name installed"
          return 0
        fi
        echo "splice:   installing $name failed — run the fix above manually, then re-run" >&2
        ;;
    esac
  fi
  return 1
}

# java_fix — Java 21+ install command for this machine (JDK for checkout builds, JRE otherwise).
java_fix() {
  local flavor="jre"
  [ -z "$REPO_ROOT" ] || flavor="jdk"
  case "$PKG" in
    "sudo apt-get install -y") echo "$PKG openjdk-21-${flavor}-headless" ;;
    "sudo dnf install -y" | "sudo zypper install -y") echo "$PKG java-21-openjdk-headless" ;;
    "sudo pacman -S --noconfirm") echo "$PKG ${flavor}21-openjdk" ;;
    "brew install") echo "brew install --cask temurin@21" ;;
    *) echo "install Java 21+ from https://adoptium.net" ;;
  esac
}

# runtime_fix <package> <fallback-url> — package-manager one-liner, or the project URL.
runtime_fix() {
  if [ -n "$PKG" ]; then echo "$PKG $1"; else echo "install $1 from $2"; fi
}

echo "splice: checking this machine's prerequisites…"

# Java 21+ is the one HARD requirement — the installer itself runs the jar. Everything else is
# a runtime dependency: verified here with its fix (and an offer to install), re-checked by
# `splice doctor` at the end, but never a reason to abort an otherwise-working install.
JAVA_MAJOR=0
if command -v java >/dev/null 2>&1; then
  JAVA_MAJOR="$(java -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -1)"
  case "$JAVA_MAJOR" in '' | *[!0-9]*) JAVA_MAJOR=0 ;; esac
fi
if [ "$JAVA_MAJOR" -ge 21 ]; then
  echo "splice: ✓ java $JAVA_MAJOR"
else
  if [ "$JAVA_MAJOR" = 0 ]; then
    echo "splice: ✗ java not found — the splice daemon runs on the JVM (Java 21+)"
  else
    echo "splice: ✗ java $JAVA_MAJOR found, but splice needs Java 21+"
  fi
  if ! offer_fix "Java 21" "$(java_fix)"; then
    echo "splice: Java 21+ is required to continue — install it and re-run" >&2
    exit 1
  fi
fi

RUNTIME_GAPS=0
check_runtime() {
  local cmd="$1" why="$2" fix="$3"
  if command -v "$cmd" >/dev/null 2>&1; then
    echo "splice: ✓ $cmd"
    return 0
  fi
  echo "splice: ✗ $cmd not found — $why"
  if ! offer_fix "$cmd" "$fix"; then
    RUNTIME_GAPS=$((RUNTIME_GAPS + 1))
  fi
}
check_runtime curl "this installer's release downloads need it" \
  "$(runtime_fix curl https://curl.se)"
check_runtime node "Claude Code's runtime and the launch shim's (Node 24)" \
  "$(runtime_fix nodejs https://nodejs.org)"
# The shim replaces itself with `claude` through process.execve (Node 22.15+); an older Node would
# learn that at the first launch, from the shim's own message — cheaper to say it here.
if command -v node >/dev/null 2>&1 && ! node -e 'process.exit(typeof process.execve === "function" ? 0 : 1)'; then
  echo "splice: ✗ node $(node -v) is too old — the launch shim needs Node 22.15+ (24 recommended)"
  if ! offer_fix "Node 24" "$(runtime_fix nodejs https://nodejs.org)"; then
    RUNTIME_GAPS=$((RUNTIME_GAPS + 1))
  fi
fi
check_runtime claude "splice wraps Claude Code — install it before launching a head" \
  "npm install -g @anthropic-ai/claude-code"
if [ "$RUNTIME_GAPS" -gt 0 ]; then
  echo "splice: $RUNTIME_GAPS runtime dependency gap(s) noted — the install continues; fix them"
  echo "splice: before launching (splice doctor re-checks everything and prints each fix)."
fi
JAR_DST="${SHARE_DIR}/splice.jar"
SHIM_SRC="${REPO_ROOT:+${REPO_ROOT}/app/src/main/dist/bin/splice-launch}"

mkdir -p "$SHARE_DIR" "$BIN_DIR"
JAR_TMP="$(mktemp "${SHARE_DIR}/.splice.jar.XXXXXX")"
SHIM_TMP="$(mktemp "${SHARE_DIR}/.splice-launch.XXXXXX")"
JAR_BACKUP="$(mktemp "${SHARE_DIR}/.splice.jar.backup.XXXXXX")"
SHIM_BACKUP="$(mktemp "${SHARE_DIR}/.splice-launch.backup.XXXXXX")"
rm -f "$JAR_BACKUP" "$SHIM_BACKUP"
SUMS_TMP=""
# Set by roll_back when the previous installation could not be put back: the backups are then the
# only copy of it, so the EXIT trap leaves them for the operator (V4-299).
KEEP_BACKUPS=0
cleanup() {
  rm -f "$JAR_TMP" "$SHIM_TMP"
  [ "$KEEP_BACKUPS" = 1 ] || rm -f "$JAR_BACKUP" "$SHIM_BACKUP"
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

# gh_attestation_gap — empty when gh can verify a build provenance attestation (installed and
# signed in), else why not: "is not installed" or "is not signed in". Asked once per run.
GH_GAP_KNOWN=0
GH_GAP=""
gh_attestation_gap() {
  if [ "$GH_GAP_KNOWN" = 0 ]; then
    GH_GAP_KNOWN=1
    if ! command -v gh >/dev/null 2>&1; then
      GH_GAP="is not installed"
    elif command -v timeout >/dev/null 2>&1; then
      timeout 20 gh auth status >/dev/null 2>&1 || GH_GAP="is not signed in"
    else
      gh auth status >/dev/null 2>&1 || GH_GAP="is not signed in"
    fi
  fi
  printf '%s' "$GH_GAP"
}

# verify_attestation <file> <asset-name> <release-base> — binds a remote release artifact to this
# repository's GitHub Actions build provenance whenever gh can check it, and refuses when the check
# fails. Without a signed-in gh the install continues on the sha256 match alone (V4-217: a first
# install needs no GitHub account) and the end of the run prints the command that verifies it later.
# The attestation cannot guard `curl … | bash` on its own anyway: install.sh comes from the same
# release, so whoever could swap the jar could swap the check. Local file:// mirrors are acceptance
# fixtures assembled from the current checkout and cannot have a GitHub attestation.
PROVENANCE_DEFERRED=""
verify_attestation() {
  local file="$1" name="$2" release_base="$3" gap
  case "$release_base" in
    file://*)
      echo "splice: local release base — skipping Sigstore attestation for ${name} (dev/acceptance artifact)" >&2
      return 0
      ;;
  esac
  gap="$(gh_attestation_gap)"
  if [ -n "$gap" ]; then
    echo "splice: ${name}: build provenance not checked here, gh ${gap}"
    PROVENANCE_DEFERRED="$gap"
    return 0
  fi
  echo "splice: verifying build provenance attestation for ${name}"
  if ! gh attestation verify "$file" --repo torad-labs/splice; then
    echo "splice: attestation verification FAILED for ${name} — aborting" >&2
    exit 1
  fi
  echo "${name} attestation: OK"
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
  ( cd "${REPO_ROOT}" && ./gradlew -q :app:shadowJar )
  BUILT="${REPO_ROOT}/app/build/libs/app-all.jar"
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

# restore_previous_artifacts — put the previous jar and shim back, or remove the candidates when there
# were none. Every move and removal is checked (V4-299): under `set -e` the first failed move ended the
# script with no line of its own, and the EXIT trap then deleted the backups. Each step that fails is
# added to RESTORE_BY_HAND, one command per line, and the function returns non-zero.
RESTORE_BY_HAND=""
restore_previous_artifacts() {
  RESTORE_BY_HAND=""
  if [ "$HAD_JAR" = 1 ]; then
    mv -f "$JAR_BACKUP" "$JAR_DST" || RESTORE_BY_HAND="${RESTORE_BY_HAND}  mv -f '$JAR_BACKUP' '$JAR_DST'
"
  else
    rm -f "$JAR_DST" || RESTORE_BY_HAND="${RESTORE_BY_HAND}  rm -f '$JAR_DST'
"
  fi
  if [ "$HAD_SHIM" = 1 ]; then
    mv -f "$SHIM_BACKUP" "$SHIM_DST" || RESTORE_BY_HAND="${RESTORE_BY_HAND}  mv -f '$SHIM_BACKUP' '$SHIM_DST'
"
  else
    rm -f "$SHIM_DST" || RESTORE_BY_HAND="${RESTORE_BY_HAND}  rm -f '$SHIM_DST'
"
  fi
  [ -z "$RESTORE_BY_HAND" ]
}

# roll_back <what failed> — restore the previous installation, say which outcome actually happened,
# and exit 1. A restore that failed keeps the backups and names the commands that finish it by hand.
roll_back() {
  if restore_previous_artifacts; then
    echo "splice: $1; previous installation restored" >&2
  else
    KEEP_BACKUPS=1
    echo "splice: $1; the previous installation could NOT be restored. Finish it by hand:" >&2
    printf '%s' "$RESTORE_BY_HAND" >&2
  fi
  exit 1
}

if ! mv -f "$JAR_TMP" "$JAR_DST" || ! mv -f "$SHIM_TMP" "$SHIM_DST"; then
  roll_back "failed to commit candidate artifacts"
fi

# run_jar <jar> <args…> — the jar's verbs that touch an install, in THIS script's home and dirs. The
# jar resolves its dirs from the JVM's user.home, which the JDK reads from the passwd entry rather
# than $HOME, so under a HOME of its own (a container, a CI job, a demo home) `init` and `install
# --all` wrote into the passwd home while step 4 checked $BIN_DIR, and doctor checked that other
# install (V4-231, 2026-09-25). `version` reads only the jar, so it runs bare.
run_jar() {
  SPLICE_BIN_DIR="$BIN_DIR" SPLICE_SHARE_DIR="$SHARE_DIR" java -Duser.home="$HOME" -jar "$@"
}

# 3. Materialize the topology + atomically link the wrapper commands (+ the `splice` command).
# A CLI/preflight failure rolls the jar and shim back as one installation generation.
if ! run_jar "$JAR_DST" init ||
  ! SPLICE_JAR="$JAR_DST" run_jar "$JAR_DST" install --all; then
  roll_back "command installation failed"
fi

# 4. Fail loudly rather than report success when nothing actually landed. `install --all`
#    always links the `splice` admin command itself, so its absence means the install failed
#    (counting arbitrary symlinks would false-pass on unrelated tools already in BIN_DIR).
if [ ! -L "$BIN_DIR/splice" ] || [ ! -e "$BIN_DIR/splice" ]; then
  roll_back "install failed — $BIN_DIR/splice missing or dangling"
fi
# 4b. Keep a PRISTINE copy of this release under releases/<version>/ for `splice upgrade`: it is
#     what rollback repoints at, and what the upgrade compares the live launch shim against so a
#     local edit (a host that patches the launcher does so AFTER this script) is kept rather than
#     overwritten. Best effort: an install never fails for want of its own archive copy.
JAR_VERSION="${JAR_VERSION_OUTPUT#splice }"
RELEASE_DIR="${SHARE_DIR}/releases/${JAR_VERSION}"
CURRENT_LINK="${SHARE_DIR}/releases/current"
# The release that was current becomes `previous`, so the first `splice upgrade --rollback` after
# an install.sh run has somewhere to go instead of skipping a version (review 2026-09-14).
# `ln -sfn` into a REAL directory named previous succeeds by linking inside it, so the link is
# read back: a pointer that did not land is said, and the archive stays best effort (review 2026-09-14).
link_previous() {
  ln -sfn "$1" "${SHARE_DIR}/releases/previous" || true
  if [ "$(readlink "${SHARE_DIR}/releases/previous" 2>/dev/null)" != "$1" ]; then
    echo "splice: warning: ${SHARE_DIR}/releases/previous is not a link to $1 (something else is in its way);" \
      "splice upgrade --rollback has no target until it is removed" >&2
    return 1
  fi
}
if [ -L "$CURRENT_LINK" ] && [ "$(readlink "$CURRENT_LINK")" != "$JAR_VERSION" ]; then
  link_previous "$(readlink "$CURRENT_LINK")" || true
elif [ ! -L "$CURRENT_LINK" ] && [ "$HAD_JAR" = 1 ]; then
  # A flat install (before 0.4.0) is being replaced: its jar and shim are still in hand as the
  # backups, so record them under their version and make them `previous` — otherwise the first
  # install.sh run that introduces releases/ leaves nothing to roll back to (review 2026-09-14).
  OLD_VERSION="$(java -jar "$JAR_BACKUP" version 2>/dev/null | sed -n 's/^splice //p' | head -1)"
  if [ -n "$OLD_VERSION" ] && [ "$OLD_VERSION" != "$JAR_VERSION" ] &&
    printf '%s' "$OLD_VERSION" | grep -Eq '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)([-+][0-9A-Za-z.-]+)?$'; then
    OLD_DIR="${SHARE_DIR}/releases/${OLD_VERSION}"
    if mkdir -p "$OLD_DIR" && cp -p "$JAR_BACKUP" "$OLD_DIR/splice.jar" &&
      { [ "$HAD_SHIM" != 1 ] || cp -p "$SHIM_BACKUP" "$OLD_DIR/splice-launch"; } &&
      link_previous "$OLD_VERSION"; then
      echo "splice: previous release $OLD_VERSION kept at $OLD_DIR (splice upgrade --rollback target)"
    fi
  fi
fi
if mkdir -p "$RELEASE_DIR" &&
  cp -p "$JAR_DST" "$RELEASE_DIR/splice.jar" &&
  cp -p "$SHIM_DST" "$RELEASE_DIR/splice-launch" &&
  ln -sfn "$JAR_VERSION" "$CURRENT_LINK"; then
  echo "splice: release copy kept at $RELEASE_DIR (splice upgrade --rollback target)"
else
  echo "splice: WARNING — could not keep a release copy under $RELEASE_DIR; splice upgrade will record the live copy" >&2
fi
rm -f "$JAR_BACKUP" "$SHIM_BACKUP"

echo
echo "splice: installed  (jar: $JAR_DST)"
if [ -n "$PROVENANCE_DEFERRED" ]; then
  echo "splice: sha256 matched the release's sha256sums.txt; build provenance was not checked (gh ${PROVENANCE_DEFERRED})."
  echo "splice: to verify it later, with gh installed and signed in:"
  echo "  gh attestation verify $JAR_DST --repo torad-labs/splice"
  echo "  gh attestation verify $SHIM_DST --repo torad-labs/splice"
fi

# 5. Verify, don't assume: run the same checkup a user would. Its findings are NEXT STEPS
#    (a missing API key is expected before `splice setup`), never an installer failure —
#    everything above already validated the artifacts that this script is responsible for.
echo
echo "splice: verifying the install (splice doctor)…"
if SPLICE_JAR="$JAR_DST" run_jar "$JAR_DST" doctor; then
  :
else
  echo
  echo "splice: the ✗/! checks above are next steps with their fixes — the install itself landed."
fi

# A command left linked to the shim after its head was renamed or removed (`install --all` links
# the topology's commands and never prunes one) is doctor's row above, with the rm that clears it:
# only the topology knows whether a head still claims a name. The hardcoded claudeor notice that
# stood here told an operator whose topology names `command = "claudeor"` to delete a working
# head (2026-09-23).

case ":$PATH:" in
  *":$BIN_DIR:"*)
    echo
    echo "Next:  export OPENROUTER_API_KEY=…   # create one at https://openrouter.ai/keys"
    echo "       splice setup      # install the supported API-key starter"
    echo "       claude-openrouter          # Claude Code through OpenRouter"
    echo "       splice doctor     # if anything misbehaves — every check prints its fix"
    ;;
  *)
    echo
    echo "Add $BIN_DIR to your PATH (export PATH=\"$BIN_DIR:\$PATH\"), then run:  splice setup"
    echo "(splice doctor checks the install and prints the fix for anything wrong)"
    ;;
esac
