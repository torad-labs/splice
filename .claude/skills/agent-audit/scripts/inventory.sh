#!/usr/bin/env bash
set -euo pipefail

# Deterministic pre-computation for agent-audit.
# Produces structural facts about an agent directory.
# Input: agent directory path as $1
# Output: structured text with 8 sections (file inventory, patterns, locations)

AGENT_DIR="${1:?Usage: inventory.sh <agent-directory>}"

if [[ ! -d "$AGENT_DIR" ]]; then
  echo "ERROR: Directory does not exist: $AGENT_DIR" >&2
  exit 1
fi

TS_FILES=$(find "$AGENT_DIR" -name '*.ts' -o -name '*.tsx' | sort)

if [[ -z "$TS_FILES" ]]; then
  echo "ERROR: No .ts or .tsx files found in: $AGENT_DIR" >&2
  exit 1
fi

echo "=== AGENT INVENTORY: $AGENT_DIR ==="
echo ""

# --- Section 1: File Inventory ---
echo "### FILE INVENTORY"
echo "$TS_FILES" | while read -r f; do
  lines=$(wc -l < "$f" | tr -d ' ')
  echo "  $f  ($lines lines)"
done
echo ""

# --- Section 2: System Prompt Patterns ---
echo "### SYSTEM PROMPT PATTERNS"
grep -rn 'SYSTEM_PROMPT\|buildSystemPrompt\|system:\s*`' $TS_FILES 2>/dev/null || echo "  (none found)"
echo ""

# --- Section 3: Tool Definitions ---
echo "### TOOL DEFINITIONS"
grep -rn 'tool(\|z\.object(' $TS_FILES 2>/dev/null || echo "  (none found)"
echo ""

# --- Section 4: Type Definitions ---
echo "### TYPE DEFINITIONS"
grep -rn 'export interface\|export type' $TS_FILES 2>/dev/null || echo "  (none found)"
echo ""

# --- Section 5: Model Assignments ---
echo "### MODEL ASSIGNMENTS"
grep -rn 'anthropic\|haiku\|sonnet\|opus\|claude-\|model[=:]\|modelId' $TS_FILES 2>/dev/null || echo "  (none found)"
echo ""

# --- Section 6: Config Patterns ---
echo "### CONFIG PATTERNS"
grep -rn 'temperature\|maxTokens\|max_tokens\|effort\|budget' $TS_FILES 2>/dev/null || echo "  (none found)"
echo ""

# --- Section 7: Pipeline Patterns ---
echo "### PIPELINE PATTERNS"
grep -rn 'execute\|handleBlock\|runBlock\|step\|phase\|pipeline\|orchestrat' $TS_FILES 2>/dev/null || echo "  (none found)"
echo ""

# --- Section 8: QA Patterns ---
echo "### QA PATTERNS"
grep -rn 'validat\|check\|assert\|verify\|qa\|quality' $TS_FILES 2>/dev/null || echo "  (none found)"
echo ""

echo "=== END INVENTORY ==="
