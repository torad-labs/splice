#!/usr/bin/env bash
set -euo pipefail

# Deterministic pre-computation for pipeline-xray.
# Extracts structural data-flow facts from an agent directory.
# Input: agent directory path as $1
# Output: structured text with 9 sections to /tmp/pipeline-xray-inventory.txt
# Pure grep/sed — no model, deterministic.

AGENT_DIR="${1:?Usage: extract-flow.sh <agent-directory>}"

if [[ ! -d "$AGENT_DIR" ]]; then
  echo "ERROR: Directory does not exist: $AGENT_DIR" >&2
  exit 1
fi

TS_FILES=$(find "$AGENT_DIR" -name '*.ts' -o -name '*.tsx' | sort)

if [[ -z "$TS_FILES" ]]; then
  echo "ERROR: No .ts or .tsx files found in: $AGENT_DIR" >&2
  exit 1
fi

OUT="/tmp/pipeline-xray-inventory.txt"

{
echo "=== PIPELINE X-RAY INVENTORY: $AGENT_DIR ==="
echo ""

# --- Section 1: File Inventory ---
echo "### FILE INVENTORY"
echo "$TS_FILES" | while read -r f; do
  lines=$(wc -l < "$f" | tr -d ' ')
  echo "  $f  ($lines lines)"
done
echo ""

# --- Section 2: Handler Signatures ---
echo "### HANDLER SIGNATURES"
grep -rn 'export async function handle\|export function handle\|export async function run\|export function run' $TS_FILES 2>/dev/null | \
  sed 's/^/  /' || echo "  (none found)"
echo ""

# --- Section 3: State Type Fields ---
echo "### STATE TYPE FIELDS"
# Match PipelineState or similar mutable state interfaces — extract field lines
found_state=false
for f in $TS_FILES; do
  if grep -q 'export interface PipelineState\|export interface State\b' "$f" 2>/dev/null; then
    echo "  [from $f]"
    grep -A 50 'export interface PipelineState\|export interface State\b' "$f" 2>/dev/null | \
      grep -E '^\s+\w+[\?:]' | \
      sed 's/^/  /'
    found_state=true
  fi
done
if [[ "$found_state" == "false" ]]; then echo "  (none found)"; fi
echo ""

# --- Section 4: Tool Schema Fields ---
echo "### TOOL SCHEMA FIELDS"
# Extract tool name + required fields from tool definitions
for f in $TS_FILES; do
  if grep -q 'name:.*"emit_\|name:.*"sentiment_\|name:.*"qa_' "$f" 2>/dev/null; then
    echo "  [from $f]"
    grep -n 'name:\s*"\|required:\s*\[' "$f" 2>/dev/null | \
      sed 's/^/  /'
  fi
done | head -60
echo ""

# --- Section 5: State Writes ---
echo "### STATE WRITES"
# state.fieldName = assignments
grep -rn 'state\.\w\+ =' $TS_FILES 2>/dev/null | \
  grep -v '^\s*//' | \
  sed 's/^/  /' || echo "  (none found)"
echo ""

# --- Section 6: State Reads ---
echo "### STATE READS"
# state.fieldName reads (not assignments)
grep -rn 'state\.\w\+[^=]' $TS_FILES 2>/dev/null | \
  grep -v 'state\.\w\+ =' | \
  grep -v '^\s*//' | \
  grep -v 'state\.completedSteps\.' | \
  head -80 | \
  sed 's/^/  /' || echo "  (none found)"
echo ""

# --- Section 7: QA Patterns ---
echo "### QA PATTERNS"
# Regex patterns used in validation/QA
grep -rn 'new RegExp\|/[^/]\+/[gim]\|\.test(\|\.match(\|\.exec(' $TS_FILES 2>/dev/null | \
  grep -v 'node_modules' | \
  sed 's/^/  /' || echo "  (none found)"
echo ""

# --- Section 8: Post-process Injections ---
echo "### POST-PROCESS INJECTIONS"
# Functions that modify HTML or final output
grep -rn 'function inject\|\.replace(\|\.replaceAll(\|html\.slice\|html +=' $TS_FILES 2>/dev/null | \
  grep -v 'node_modules' | \
  grep -v '^\s*//' | \
  sed 's/^/  /' || echo "  (none found)"
echo ""

# --- Section 9: Type Imports ---
echo "### TYPE IMPORTS"
# Which files import which types
grep -rn 'import.*from\|import type' $TS_FILES 2>/dev/null | \
  grep -v 'node_modules' | \
  sed 's/^/  /' || echo "  (none found)"
echo ""

echo "=== END INVENTORY ==="
} > "$OUT"

echo "Inventory written to $OUT ($(wc -l < "$OUT" | tr -d ' ') lines)"
