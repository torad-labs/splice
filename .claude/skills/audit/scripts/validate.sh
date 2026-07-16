#!/usr/bin/env bash
# validate.sh — Structural validation for a skill directory.
# Usage: bash validate.sh /path/to/skill-directory
#
# Checks: SKILL.md exists, frontmatter valid, name matches folder,
# internal links resolve, no orphan files, references/ plural.
# Reports PASS/WARN/FAIL.

set -euo pipefail

SKILL_DIR="${1:?Usage: validate.sh /path/to/skill-directory}"
SKILL_DIR=$(cd "$SKILL_DIR" && pwd)
SKILL_MD="$SKILL_DIR/SKILL.md"

PASS=0
WARN=0
FAIL=0

pass() { echo "  PASS: $1"; PASS=$((PASS + 1)); }
warn() { echo "  WARN: $1"; WARN=$((WARN + 1)); }
fail() { echo "  FAIL: $1"; FAIL=$((FAIL + 1)); }

echo "=== SKILL VALIDATION ==="
echo "Directory: $SKILL_DIR"
echo ""

# --- 1. SKILL.md exists ---
if [ ! -f "$SKILL_MD" ]; then
  fail "SKILL.md not found"
  echo ""
  echo "=== RESULT: FAIL ($FAIL failures) ==="
  exit 1
else
  pass "SKILL.md exists"
fi

# --- 2. Valid YAML frontmatter ---
FIRST_LINE=$(head -1 "$SKILL_MD")
if [ "$FIRST_LINE" = "---" ]; then
  # Check closing delimiter exists
  CLOSING=$(awk '/^---$/{n++; if(n==2){print "found"; exit}}' "$SKILL_MD")
  if [ "$CLOSING" = "found" ]; then
    pass "YAML frontmatter has opening and closing delimiters"
  else
    fail "YAML frontmatter missing closing ---"
  fi
else
  fail "SKILL.md does not start with --- (no YAML frontmatter)"
fi

# --- 3. Name field matches directory name ---
FOLDER_NAME=$(basename "$SKILL_DIR")
FM_NAME=$(awk '/^---$/{n++; next} n==1 && /^name:/{sub(/^name: */, ""); print; exit}' "$SKILL_MD")

if [ -n "$FM_NAME" ]; then
  if [ "$FM_NAME" = "$FOLDER_NAME" ]; then
    pass "name field matches directory ($FM_NAME)"
  else
    fail "name field ($FM_NAME) does not match directory ($FOLDER_NAME)"
  fi
else
  warn "No name field in frontmatter (will use directory name: $FOLDER_NAME)"
fi

# --- 4. Kebab-case directory name ---
if echo "$FOLDER_NAME" | grep -qE '^[a-z0-9-]+$'; then
  pass "Directory name is kebab-case"
else
  fail "Directory name is not kebab-case: $FOLDER_NAME"
fi

# --- 5. No singular reference/ directory ---
if [ -d "$SKILL_DIR/reference" ] && [ ! -d "$SKILL_DIR/references" ]; then
  fail "Uses singular reference/ — rename to references/"
elif [ -d "$SKILL_DIR/reference" ] && [ -d "$SKILL_DIR/references" ]; then
  warn "Both reference/ and references/ exist — consolidate to references/"
else
  pass "No singular reference/ directory"
fi

# --- 6. Internal links resolve ---
BODY=$(awk 'BEGIN{n=0} /^---$/{n++; if(n==2){p=1; next}} p{print}' "$SKILL_MD")
BROKEN_COUNT=0
while IFS= read -r target; do
  [ -z "$target" ] && continue
  # Skip external links and anchors
  if echo "$target" | grep -qE '^(http|#)'; then continue; fi
  RESOLVED="$SKILL_DIR/$target"
  if [ ! -e "$RESOLVED" ]; then
    fail "Broken link: $target"
    BROKEN_COUNT=$((BROKEN_COUNT + 1))
  fi
done < <(echo "$BODY" | grep -oE '\]\(([^)]+)\)' | sed 's/](\(.*\))/\1/' || true)
if [ "$BROKEN_COUNT" -eq 0 ]; then
  pass "All internal links resolve"
fi

# --- 7. Orphan files ---
ORPHAN_COUNT=0
for subdir in references scripts assets blocks phases agents commands; do
  if [ -d "$SKILL_DIR/$subdir" ]; then
    while IFS= read -r f; do
      REL="${f#$SKILL_DIR/}"
      if ! grep -q "$REL" "$SKILL_MD" 2>/dev/null && \
         ! grep -rq "$REL" "$SKILL_DIR/blocks/" 2>/dev/null; then
        warn "Orphan file: $REL"
        ORPHAN_COUNT=$((ORPHAN_COUNT + 1))
      fi
    done < <(find "$SKILL_DIR/$subdir" -type f)
  fi
done
if [ "$ORPHAN_COUNT" -eq 0 ]; then
  pass "No orphan files in subdirectories"
fi

# --- 8. No README.md ---
if [ -f "$SKILL_DIR/README.md" ]; then
  warn "README.md found — use SKILL.md or references/ instead"
else
  pass "No README.md"
fi

# --- Summary ---
echo ""
TOTAL=$((PASS + WARN + FAIL))
echo "=== RESULT: $PASS pass, $WARN warn, $FAIL fail (of $TOTAL checks) ==="

if [ "$FAIL" -gt 0 ]; then
  echo "STATUS: FAIL"
  exit 1
elif [ "$WARN" -gt 0 ]; then
  echo "STATUS: WARN"
  exit 0
else
  echo "STATUS: PASS"
  exit 0
fi
