#!/usr/bin/env bash
# Red-green fixture test for find-duplicates.sh.
#
# Serves synthetic issue data through a stub `gh` on PATH, so the test runs
# offline with zero network calls. Asserts that a known-duplicate pair scores
# above the threshold and a known-distinct pair scores below it, that the comment
# lists per-candidate scores, and that the optional label is skipped when it does
# not exist. Phase 2 generates a payload above the kernel argv ceiling and proves
# the duplicate is still detected via file transport.
set -euo pipefail

dir="$(cd "$(dirname "$0")" && pwd)"
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

# ---------------------------------------------------------------------------
# Phase 1: duplicate pair with comfortable headroom, distinct pair, scores, label.
# ---------------------------------------------------------------------------

mkdir -p "$tmpdir/phase1"

cat > "$tmpdir/phase1/gh" <<'GH'
#!/usr/bin/env bash
# Synthetic gh for offline testing of find-duplicates.sh.
case "$1" in
  issue)
    case "$2" in
      view)
        echo '{"number":1,"title":"Daemon crashes on startup","body":"When I start the splice daemon, it crashes with a segfault. This happens every time on Ubuntu 24.04 with the latest release."}'
        ;;
      list)
        echo '[{"number":2,"title":"Daemon crashes on startup","body":"When I start the splice daemon, it crashes with a segfault. This happens every time on Ubuntu 24.04 with the latest release!","labels":[]},{"number":3,"title":"Add dark mode to web UI","body":"Please add a dark theme for the web UI.","labels":[]}]'
        ;;
      comment)
        shift 2
        body=""
        while [[ $# -gt 0 ]]; do
          case "$1" in
            --body) body="$2"; shift 2 ;;
            *) shift ;;
          esac
        done
        printf '%s' "$body" > "$GH_COMMENT_FILE"
        ;;
      edit)
        shift 2
        label=""
        while [[ $# -gt 0 ]]; do
          case "$1" in
            --add-label) label="$2"; shift 2 ;;
            *) shift ;;
          esac
        done
        printf '%s' "$label" > "$GH_LABEL_FILE"
        ;;
    esac
    ;;
  label)
    # potential-duplicate label does not exist in this fixture.
    echo '[]'
    ;;
esac
exit 0
GH
chmod +x "$tmpdir/phase1/gh"

export GH_COMMENT_FILE="$tmpdir/phase1/comment.txt"
export GH_LABEL_FILE="$tmpdir/phase1/label.txt"
export PATH="$tmpdir/phase1:$PATH"

bash "$dir/find-duplicates.sh" 1

body="$(cat "$GH_COMMENT_FILE")"

if ! grep -q '#2' <<<"$body"; then
  echo "FAIL: expected duplicate candidate #2 in comment body" >&2
  echo "$body" >&2
  exit 1
fi

if ! grep -Eq 'score [0-9]+\.[0-9]+' <<<"$body"; then
  echo "FAIL: expected per-candidate scores in comment body" >&2
  echo "$body" >&2
  exit 1
fi

if grep -q '#3' <<<"$body"; then
  echo "FAIL: did not expect distinct candidate #3 in comment body" >&2
  echo "$body" >&2
  exit 1
fi

if [ -s "$GH_LABEL_FILE" ]; then
  echo "FAIL: label should not be added because potential-duplicate label does not exist" >&2
  cat "$GH_LABEL_FILE" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Phase 2: payload above the 131,072-byte argv ceiling with a planted duplicate.
# ---------------------------------------------------------------------------

mkdir -p "$tmpdir/phase2"

python3 - "$tmpdir/phase2/issues.json" <<'PY'
import json, sys

target_title = "Daemon crashes on startup"
target_body = "When I start the splice daemon, it crashes with a segfault. This happens every time on Ubuntu 24.04 with the latest release."

issues = []
# 199 realistic, distinct filler issues (~800 bytes each).
for i in range(1, 200):
    if i == 142:
        continue
    n = 1000 + i
    issues.append({
        "number": n,
        "title": f"Filler issue {i} about component behavior",
        "body": (
            f"This is a long description for issue {i} that is intentionally unrelated to the daemon "
            f"startup crash. It discusses configuration parsing, dependency resolution, logging output, "
            f"and platform-specific behavior on various operating systems. "
            f"Lorem ipsum dolor sit amet, consectetur adipiscing elit. " * 6
            + f"End of body for issue {i}."
        ),
        "labels": []
    })
# Planted verbatim duplicate of the target (number 142, as specified in the plan).
issues.append({
    "number": 142,
    "title": target_title,
    "body": target_body,
    "labels": []
})

with open(sys.argv[1], "w") as f:
    json.dump(issues, f)
PY

bytes=$(wc -c < "$tmpdir/phase2/issues.json")
if [ "$bytes" -le 131072 ]; then
  echo "FAIL: payload shrank below argv ceiling (131072 bytes), got $bytes" >&2
  exit 1
fi

cat > "$tmpdir/phase2/gh" <<'GH'
#!/usr/bin/env bash
# Synthetic gh for the big-payload phase of find-duplicates.sh.
case "$1" in
  issue)
    case "$2" in
      view)
        echo '{"number":1,"title":"Daemon crashes on startup","body":"When I start the splice daemon, it crashes with a segfault. This happens every time on Ubuntu 24.04 with the latest release."}'
        ;;
      list)
        cat "$GH_ISSUES_FILE"
        ;;
      comment)
        shift 2
        body=""
        while [[ $# -gt 0 ]]; do
          case "$1" in
            --body) body="$2"; shift 2 ;;
            *) shift ;;
          esac
        done
        printf '%s' "$body" > "$GH_COMMENT_FILE"
        ;;
      edit)
        shift 2
        label=""
        while [[ $# -gt 0 ]]; do
          case "$1" in
            --add-label) label="$2"; shift 2 ;;
            *) shift ;;
          esac
        done
        printf '%s' "$label" > "$GH_LABEL_FILE"
        ;;
    esac
    ;;
  label)
    echo '[]'
    ;;
esac
exit 0
GH
chmod +x "$tmpdir/phase2/gh"

export GH_COMMENT_FILE="$tmpdir/phase2/comment.txt"
export GH_LABEL_FILE="$tmpdir/phase2/label.txt"
export GH_ISSUES_FILE="$tmpdir/phase2/issues.json"
export PATH="$tmpdir/phase2:$PATH"

bash "$dir/find-duplicates.sh" 1

body2="$(cat "$GH_COMMENT_FILE")"

if ! grep -q '#142' <<<"$body2"; then
  echo "FAIL: expected planted verbatim duplicate #142 in big-payload comment body" >&2
  echo "$body2" >&2
  exit 1
fi

if [ -s "$GH_LABEL_FILE" ]; then
  echo "FAIL: label should not be added in big-payload phase (potential-duplicate label does not exist)" >&2
  cat "$GH_LABEL_FILE" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Red probes: verify the test is sensitive to broken thresholds / broken view.
# ---------------------------------------------------------------------------

probe_dir="$tmpdir/probes"
mkdir -p "$probe_dir"

# Probe 1: threshold 0.0 -> candidate #3 (distinct) also gets flagged.
cp "$dir/find-duplicates.sh" "$probe_dir/find-duplicates-threshold-0.sh"
sed -i 's/^SIMILARITY_THRESHOLD=0.8$/SIMILARITY_THRESHOLD=0.0/' "$probe_dir/find-duplicates-threshold-0.sh"
export GH_COMMENT_FILE="$probe_dir/comment-0.txt"
export GH_LABEL_FILE="$probe_dir/label-0.txt"
export GH_ISSUES_FILE=""
export PATH="$tmpdir/phase1:$PATH"
bash "$probe_dir/find-duplicates-threshold-0.sh" 1
probe_body="$(cat "$GH_COMMENT_FILE")"
if ! grep -q '#3' <<<"$probe_body"; then
  echo "FAIL: red probe threshold 0.0 did not flag distinct candidate #3" >&2
  echo "$probe_body" >&2
  exit 1
fi

# Probe 2: threshold 1.1 -> no candidate gets flagged (no comment file).
cp "$dir/find-duplicates.sh" "$probe_dir/find-duplicates-threshold-11.sh"
sed -i 's/^SIMILARITY_THRESHOLD=0.8$/SIMILARITY_THRESHOLD=1.1/' "$probe_dir/find-duplicates-threshold-11.sh"
export GH_COMMENT_FILE="$probe_dir/comment-11.txt"
export GH_LABEL_FILE="$probe_dir/label-11.txt"
bash "$probe_dir/find-duplicates-threshold-11.sh" 1
if [ -e "$GH_COMMENT_FILE" ]; then
  echo "FAIL: red probe threshold 1.1 should not have written a comment file" >&2
  exit 1
fi

# Probe 3: broken `gh issue view` -> no comment posted.
cat > "$probe_dir/gh" <<'GH'
#!/usr/bin/env bash
case "$1" in
  issue)
    case "$2" in
      view) exit 1 ;;
      list)
        echo '[{"number":2,"title":"Daemon crashes on startup","body":"The splice daemon crashes with a segfault when I start it.","labels":[]}]'
        ;;
      comment)
        shift 2
        body=""
        while [[ $# -gt 0 ]]; do
          case "$1" in
            --body) body="$2"; shift 2 ;;
            *) shift ;;
          esac
        done
        printf '%s' "$body" > "$GH_COMMENT_FILE"
        ;;
      edit)
        shift 2
        label=""
        while [[ $# -gt 0 ]]; do
          case "$1" in
            --add-label) label="$2"; shift 2 ;;
            *) shift ;;
          esac
        done
        printf '%s' "$label" > "$GH_LABEL_FILE"
        ;;
    esac
    ;;
  label)
    echo '[]'
    ;;
esac
exit 0
GH
chmod +x "$probe_dir/gh"
export GH_COMMENT_FILE="$probe_dir/comment-broken.txt"
export GH_LABEL_FILE="$probe_dir/label-broken.txt"
export PATH="$probe_dir:$PATH"
bash "$dir/find-duplicates.sh" 1
if [ -e "$GH_COMMENT_FILE" ]; then
  echo "FAIL: red probe broken view should not have written a comment file" >&2
  exit 1
fi

echo "PASS: duplicate candidate scored >= threshold, distinct candidate < threshold, scores present, big-payload duplicate detected, red probes sensitive"