#!/usr/bin/env bash
# measure.sh — Deterministic structural measurements for a skill directory.
# Usage: bash measure.sh /path/to/skill-directory
#
# Produces exact counts that an LLM would estimate. Run BEFORE launching
# any audit block. Pass the output as pre-verified measurements.

set -euo pipefail

SKILL_DIR="${1:?Usage: measure.sh /path/to/skill-directory}"
SKILL_MD="$SKILL_DIR/SKILL.md"

if [ ! -f "$SKILL_MD" ]; then
  echo "ERROR: No SKILL.md found at $SKILL_MD"
  exit 1
fi

echo "=== STRUCTURAL MEASUREMENTS ==="
echo "Skill directory: $SKILL_DIR"
echo ""

# --- Folder and name validation ---
echo "## Folder and Name Validation"
FOLDER_NAME=$(basename "$SKILL_DIR")
echo "  Folder name: $FOLDER_NAME"

if echo "$FOLDER_NAME" | grep -qE '^[a-z0-9-]+$'; then
  echo "  Folder naming: VALID (kebab-case)"
else
  echo "  Folder naming: INVALID (must be lowercase letters, numbers, hyphens only)"
fi

# Name-folder match
FM_NAME=$(awk '/^---$/{n++; next} n==1 && /^name:/{sub(/^name: */, ""); print; exit}' "$SKILL_MD")
if [ -n "$FM_NAME" ]; then
  if [ "$FM_NAME" = "$FOLDER_NAME" ]; then
    echo "  Name-folder match: MATCH ($FM_NAME = $FOLDER_NAME)"
  else
    echo "  Name-folder match: MISMATCH (name: $FM_NAME, folder: $FOLDER_NAME)"
  fi
else
  echo "  Name-folder match: N/A (no name field — will use folder name)"
fi

# README.md check
if [ -f "$SKILL_DIR/README.md" ]; then
  echo "  README.md: FOUND (prohibited — use SKILL.md or references/ instead)"
else
  echo "  README.md: None (correct)"
fi
echo ""

# --- Frontmatter extraction ---
echo "## Frontmatter Fields"
awk '/^---$/{n++; next} n==1{print}' "$SKILL_MD" | while IFS= read -r line; do
  if echo "$line" | grep -qE '^[a-z]'; then
    echo "  $line"
  fi
done
echo ""

# --- Description character count ---
DESC=$(awk '/^---$/{n++; next} n==1{p=1} p && /^description:/{found=1; sub(/^description: *>-? */, ""); buf=$0; next} found && /^  /{sub(/^  /,""); buf=buf " " $0; next} found{print buf; exit}' "$SKILL_MD")
if [ -z "$DESC" ]; then
  DESC=$(awk '/^---$/{n++; next} n==1 && /^description:/{sub(/^description: */, ""); print; exit}' "$SKILL_MD")
fi
echo "## Description"
echo "  Characters: ${#DESC}"
echo ""

# --- Body metrics (excluding frontmatter) ---
BODY=$(awk 'BEGIN{n=0} /^---$/{n++; if(n==2){p=1; next}} p{print}' "$SKILL_MD")
BODY_LINES=$(echo "$BODY" | wc -l | tr -d ' ')
BODY_WORDS=$(echo "$BODY" | wc -w | tr -d ' ')

echo "## SKILL.md Body"
echo "  Lines (excluding frontmatter): $BODY_LINES"
echo "  Words (excluding frontmatter): $BODY_WORDS"

# Code blocks — use awk to avoid grep exit code 1 on zero matches
CODE_BLOCK_COUNT=$(echo "$BODY" | awk '/^```/{n++} END{print int(n/2)}')
CODE_BLOCK_LINES=$(echo "$BODY" | awk '/^```/{if(in_block){in_block=0}else{in_block=1};next} in_block{n++} END{print n+0}')
if [ "$BODY_LINES" -gt 0 ]; then
  CODE_PCT=$(awk "BEGIN{printf \"%.1f\", ($CODE_BLOCK_LINES/$BODY_LINES)*100}")
else
  CODE_PCT="0.0"
fi
echo "  Code blocks: $CODE_BLOCK_COUNT"
echo "  Code block lines: $CODE_BLOCK_LINES"
echo "  Code blocks as % of body: ${CODE_PCT}%"

# Tables — use awk to count distinct table groups
TABLE_COUNT=$(echo "$BODY" | awk 'BEGIN{n=0;in_table=0} /^\|.*\|.*\|/{if(!in_table){n++;in_table=1};next} {in_table=0} END{print n}')
echo "  Tables: $TABLE_COUNT"

# H2 and H3 sections — || true to handle zero matches
echo "  H2 sections:"
echo "$BODY" | grep '^## ' | sed 's/^## /    /' || true
echo "  H3 sections:"
echo "$BODY" | grep '^### ' | sed 's/^### /    /' || true
echo ""

# --- Block files (1-7) ---
echo "## Blocks (1-7)"
BLOCK_PASS=0
BLOCK_FAIL=0
for block in \
  "blocks/1-problem-statement.md" \
  "blocks/2-feature-staging.md" \
  "blocks/3-demand-propagation.md" \
  "blocks/4-constraint-propagation.md" \
  "blocks/5-viability-mapping.md" \
  "blocks/6-constraint-audit.md" \
  "blocks/7-ship-hold-kill.md"; do
  if [ -f "$SKILL_DIR/$block" ]; then
    LINES=$(wc -l < "$SKILL_DIR/$block" | tr -d ' ')
    echo "  FOUND: $block ($LINES lines)"
    BLOCK_PASS=$((BLOCK_PASS + 1))
  else
    echo "  MISSING: $block"
    BLOCK_FAIL=$((BLOCK_FAIL + 1))
  fi
done
echo "  Result: $BLOCK_PASS/7 found, $BLOCK_FAIL missing"
echo ""

# --- Reference files ---
echo "## Reference Files"
REF_PASS=0
REF_FAIL=0
for ref in \
  "references/equations.md" \
  "references/patterns.md" \
  "references/constraints.md"; do
  if [ -f "$SKILL_DIR/$ref" ]; then
    LINES=$(wc -l < "$SKILL_DIR/$ref" | tr -d ' ')
    echo "  FOUND: $ref ($LINES lines)"
    REF_PASS=$((REF_PASS + 1))
  else
    echo "  MISSING: $ref"
    REF_FAIL=$((REF_FAIL + 1))
  fi
done
echo "  Result: $REF_PASS/3 found, $REF_FAIL missing"
echo ""

# --- Evals ---
echo "## Evals"
EVAL_PASS=0
EVAL_FAIL=0
if [ -f "$SKILL_DIR/evals/evals.json" ]; then
  echo "  FOUND: evals/evals.json"
  EVAL_PASS=$((EVAL_PASS + 1))
else
  echo "  MISSING: evals/evals.json"
  EVAL_FAIL=$((EVAL_FAIL + 1))
fi
if [ -f "$SKILL_DIR/evals/trigger-evals.json" ]; then
  echo "  FOUND: evals/trigger-evals.json"
  EVAL_PASS=$((EVAL_PASS + 1))
else
  echo "  MISSING: evals/trigger-evals.json"
  EVAL_FAIL=$((EVAL_FAIL + 1))
fi
echo "  Result: $EVAL_PASS/2 found, $EVAL_FAIL missing"
echo ""

# --- Assets ---
echo "## Assets"
if [ -f "$SKILL_DIR/assets/report-template.html" ]; then
  LINES=$(wc -l < "$SKILL_DIR/assets/report-template.html" | tr -d ' ')
  echo "  FOUND: assets/report-template.html ($LINES lines)"
else
  echo "  MISSING: assets/report-template.html"
fi
echo ""

# --- Directory structure ---
# Use -not -name '.*' (filename only) instead of -not -path '*/\.*' (full path)
# because skills live inside .claude/ which would match the path pattern
echo "## Directory Structure"
echo "  Files:"
find "$SKILL_DIR" -type f -not -name '.*' | sort | while read -r f; do
  echo "    ${f#$SKILL_DIR/}"
done
echo ""
echo "  Directories:"
find "$SKILL_DIR" -type d -not -name '.*' -not -path "$SKILL_DIR" | sort | while read -r d; do
  echo "    ${d#$SKILL_DIR/}/"
done
TOTAL_FILES=$(find "$SKILL_DIR" -type f -not -name '.*' | wc -l | tr -d ' ')
echo "  Total files: $TOTAL_FILES"
echo ""

# --- Reference hygiene ---
echo "## Reference Hygiene"

echo "  Links in SKILL.md:"
echo "$BODY" | grep -oE '\[([^]]+)\]\(([^)]+)\)' | while read -r link; do
  target=$(echo "$link" | sed 's/.*](\(.*\))/\1/')
  echo "    $target"
done || true

echo ""
echo "  Broken pointers:"
BROKEN_COUNT=0
while read -r target; do
  # Skip external links and anchors
  if echo "$target" | grep -qE '^(http|#)'; then continue; fi
  RESOLVED="$SKILL_DIR/$target"
  if [ ! -e "$RESOLVED" ]; then
    echo "    BROKEN: $target"
    BROKEN_COUNT=$((BROKEN_COUNT + 1))
  fi
done < <(echo "$BODY" | grep -oE '\]\(([^)]+)\)' | sed 's/](\(.*\))/\1/' || true)
if [ "$BROKEN_COUNT" -eq 0 ]; then echo "    None"; fi

# Orphan files
echo ""
echo "  Orphan files (not linked from SKILL.md):"
ORPHAN_COUNT=0
for subdir in references scripts examples assets blocks phases evals; do
  if [ -d "$SKILL_DIR/$subdir" ]; then
    while read -r f; do
      REL="${f#$SKILL_DIR/}"
      if ! grep -q "$REL" "$SKILL_MD" 2>/dev/null; then
        echo "    ORPHAN: $REL"
        ORPHAN_COUNT=$((ORPHAN_COUNT + 1))
      fi
    done < <(find "$SKILL_DIR/$subdir" -type f)
  fi
done
if [ "$ORPHAN_COUNT" -eq 0 ]; then echo "    None"; fi

# Nesting depth
MAX_DEPTH=$(find "$SKILL_DIR" -type f -not -name '.*' | awk -F/ '{print NF}' | sort -rn | head -1)
SKILL_DEPTH=$(echo "$SKILL_DIR" | awk -F/ '{print NF}')
NESTING=$((MAX_DEPTH - SKILL_DEPTH - 1))
echo ""
echo "  Max nesting depth: $NESTING"
echo ""

# --- Content distribution ---
echo "## Content Distribution"
for subdir in references scripts examples assets blocks phases evals; do
  if [ -d "$SKILL_DIR/$subdir" ]; then
    while read -r f; do
      REL="${f#$SKILL_DIR/}"
      LINES=$(wc -l < "$f" | tr -d ' ')
      WORDS=$(wc -w < "$f" | tr -d ' ')
      CB_LINES=$(awk '/^```/{if(b){b=0}else{b=1};next} b{n++} END{print n+0}' "$f")
      echo "  $REL: ${LINES} lines, ${WORDS} words, ${CB_LINES} code block lines"
    done < <(find "$SKILL_DIR/$subdir" -type f -name '*.md' | sort)
  fi
done
echo ""

# --- Second person scan ---
echo "## Second Person Scan"
echo "$BODY" | awk '/^```/{b=!b;next} !b{print NR": "$0}' | grep -inE '\byou\b|\byour\b|\byou.ll\b|\byou.ve\b|\byou.d\b|\byourself\b' || echo "  None found"
echo ""

# --- Windows path scan ---
echo "## Windows Path Scan"
find "$SKILL_DIR" -type f -name '*.md' -exec grep -Hn '\\\\' {} \; | grep -v '```' || echo "  None found"
echo ""

# --- Date/temporal scan ---
echo "## Date and Temporal References"
find "$SKILL_DIR" -type f -name '*.md' -exec grep -HinE '(before|after|as of|since) (january|february|march|april|may|june|july|august|september|october|november|december|20[0-9]{2})|currently|recently|soon' {} \; || echo "  None found"
echo ""

# --- Agent-skill detection ---
echo "## Agent Skill Detection"
if [ -f "$SKILL_DIR/tools.ts" ]; then
  echo "  tools.ts: FOUND"
  AGENT_SKILL="yes"
elif [ -d "$SKILL_DIR/blocks" ]; then
  TS_BLOCKS=$(find "$SKILL_DIR/blocks" -name '*.ts' 2>/dev/null | wc -l | tr -d ' ')
  if [ "$TS_BLOCKS" -gt 0 ]; then
    echo "  blocks/*.ts: FOUND ($TS_BLOCKS files)"
    AGENT_SKILL="yes"
  else
    echo "  Agent skill markers: NOT FOUND"
    AGENT_SKILL="no"
  fi
else
  echo "  Agent skill markers: NOT FOUND"
  AGENT_SKILL="no"
fi
echo "  Is agent-skill: $AGENT_SKILL"
echo ""

# --- Summary ---
echo "## Summary"
TOTAL_LINES=0
while IFS= read -r f; do
  L=$(wc -l < "$f" | tr -d ' ')
  TOTAL_LINES=$((TOTAL_LINES + L))
done < <(find "$SKILL_DIR" -type f -not -name '.*')
echo "  Total files: $TOTAL_FILES"
echo "  Total lines: $TOTAL_LINES"
echo ""

echo "=== END MEASUREMENTS ==="
