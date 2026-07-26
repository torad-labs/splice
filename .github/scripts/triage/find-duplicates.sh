#!/usr/bin/env bash
# Find potential duplicate issues for a single issue number.
#
#   GH_REPO=torad-labs/splice .github/scripts/triage/find-duplicates.sh 21
#
# The issue number is numeric-validated and pinned through every call. Similarity
# is computed with python3 difflib.SequenceMatcher (stdlib only) over the
# normalized title + first 500 characters of body. The only mutation verbs are
# `gh issue comment` and `gh issue edit --add-label potential-duplicate`; the
# script never auto-closes and never applies the confirmed `duplicate` label.
#
# The `potential-duplicate` label is optional: it is probed first, and if it
# does not exist the label step is skipped with a warning (operator may create
# the label out-of-band to enable labeling). gh/API failures are logged skips,
# never red checks.
#
# Upgrade path (documented only, not implemented): if stdlib fuzzy matching is
# too weak, swap in embedding similarity via the existing OPENROUTER_API_KEY.
set -euo pipefail

n="${1:?usage: find-duplicates.sh <issue-number>}"
case "$n" in (*[!0-9]*|'') echo "issue number must be numeric, got: $n" >&2; exit 2 ;; esac

SIMILARITY_THRESHOLD=0.8
CANDIDATE_LIMIT=200
TOP_K=3

warn() { echo "::warning::find-duplicates: $*" >&2; }

target_json="$(gh issue view "$n" --json number,title,body 2>/dev/null)" || {
  warn "failed to fetch issue #$n; skipping duplicate search"
  exit 0
}

candidates_json="$(gh issue list --state open --json number,title,body,labels --limit "$CANDIDATE_LIMIT" 2>/dev/null || true)"
if [ -z "$candidates_json" ] || [ "$candidates_json" = "[]" ]; then
  warn "no open issues to compare against"
  exit 0
fi

# Probe for the optional label before trying to apply it.
label_present=0
if gh label list --search potential-duplicate --limit 1 --json name 2>/dev/null | grep -q '"potential-duplicate"'; then
  label_present=1
fi

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

printf '%s' "$target_json" > "$tmpdir/target.json"
printf '%s' "$candidates_json" > "$tmpdir/candidates.json"

jq -n --slurpfile t "$tmpdir/target.json" --slurpfile c "$tmpdir/candidates.json" \
  '{target: $t[0], candidates: $c[0]}' > "$tmpdir/combined.json" || {
  warn "failed to prepare issue data for scoring"
  exit 0
}

ranked="$(python3 - "$tmpdir/combined.json" "$SIMILARITY_THRESHOLD" "$TOP_K" <<'PY'
import json, difflib, sys

def normalize(text):
    if text is None:
        return ""
    t = str(text)[:500].lower()
    return " ".join(t.split())

def score(a_title, a_body, b_title, b_body):
    a = normalize(a_title) + " " + normalize(a_body)
    b = normalize(b_title) + " " + normalize(b_body)
    if not a or not b:
        return 0.0
    return difflib.SequenceMatcher(None, a, b).ratio()

threshold = float(sys.argv[2])
top_k = int(sys.argv[3])

with open(sys.argv[1]) as f:
    data = json.load(f)

target = data["target"]
candidates = [c for c in data["candidates"] if c.get("number") != target.get("number")]

scored = []
for c in candidates:
    s = score(target.get("title"), target.get("body"), c.get("title"), c.get("body"))
    if s >= threshold:
        scored.append({"number": c.get("number"), "title": c.get("title"), "score": round(s, 3)})

scored.sort(key=lambda x: x["score"], reverse=True)
print(json.dumps(scored[:top_k]))
PY
)" || {
  warn "similarity scoring failed; skipping duplicate search"
  exit 0
}

if [ -z "$ranked" ] || [ "$ranked" = "[]" ]; then
  warn "no duplicate candidates above threshold $SIMILARITY_THRESHOLD"
  exit 0
fi

body="$(jq -r --arg threshold "$SIMILARITY_THRESHOLD" '
  ["Potential duplicate candidates (similarity threshold: \($threshold)):"] +
  [.[] | "- #\(.number): \(.title) — score \(.score)"]
  | join("\n")
' <<<"$ranked")"

if ! gh issue comment "$n" --body "$body" 2>/dev/null; then
  warn "failed to comment on issue #$n"
fi

if [ "$label_present" -eq 1 ]; then
  if ! gh issue edit "$n" --add-label potential-duplicate 2>/dev/null; then
    warn "failed to add label potential-duplicate to issue #$n"
  fi
else
  warn "label potential-duplicate does not exist; skipping label (comment posted)"
fi
