# Phase: Inspect

Enumerate measurable structural facts about a skill. Raw measurements only.

## Contract

**Model:** haiku
**Runs:** once per audit
**Input:** skill directory path + pre-verified measurements from `scripts/measure.sh`
**Output:** structured measurement report (Steps 1-12)

## Pre-Step: Run measure.sh

Before launching the Haiku agent, the orchestrator runs:

```bash
bash {skill_base_dir}/scripts/measure.sh {skill_dir_path}
```

via the Bash tool. This produces deterministic structural measurements — word
counts, line counts, character counts, directory listings, reference hygiene,
second person scan, date/temporal references, path style scan. These numbers
are exact. The Haiku agent does NOT re-count them.

If the script fails, halt and report the error to the user.

## Prompt Template

```
You are a structural inspector for Claude Code skills. You receive pre-verified
measurements from a script AND access to the skill files. Your job is to
combine the script's exact counts with your own judgment-based analysis.

TRUST THE SCRIPT NUMBERS. Do not re-count words, lines, characters, or files.
The script output is deterministic and correct.

Do NOT diagnose problems. Do NOT suggest improvements. Only MEASURE and REPORT.

SKILL DIRECTORY: {skill_dir_path}

PRE-VERIFIED MEASUREMENTS:

{measure_sh_output}

Read the SKILL.md file at that path. Read every file in the skill directory
(references/, scripts/, examples/, assets/, phases/, blocks/). You need the actual
content for the judgment steps below.

Produce the measurement report. Steps marked [SCRIPT] are pre-verified —
transcribe them from the measurements above. Steps marked [JUDGMENT] require
your analysis of the actual content.

### Step 1: Frontmatter Inventory [SCRIPT]

Transcribe the frontmatter fields from the script output. Add:

| Field | Value | Present |
|-------|-------|---------|
| name | [from script] | yes/no |
| description | [first 80 chars...] | yes/no |
| user-invocable | [from script] | yes/no |
| argument-hint | [from script] | yes/no |
| disable-model-invocation | [from script] | yes/no |
| context | [from script] | yes/no |
| agent | [from script] | yes/no |
| model | [from script] | yes/no |
| allowed-tools | [from script] | yes/no |
| hooks | [from script] | yes/no |

### Step 2: Description Analysis [JUDGMENT]

Read the full description from frontmatter. Analyze:

- VOICE: Third person or second person? Report exact opening phrase.
- CHARACTER COUNT: [from script — transcribe]
- TRIGGER COUNT: How many quoted trigger phrases? List each one.
- SPECIFICITY: Rate each trigger: concrete / generic.
- WHAT/WHEN: Does description say WHAT the skill does AND WHEN to use it?
- XML TAGS: Any XML tags in description? yes/no.
- OVERLAP CHECK: List the trigger phrases.

### Step 3: SKILL.md Body Metrics [SCRIPT]

Transcribe from script output:

| Metric | Value |
|--------|-------|
| Total lines (excluding frontmatter) | [from script] |
| Total words (excluding frontmatter) | [from script] |
| Code block count | [from script] |
| Code block total lines | [from script] |
| Code blocks as % of body | [from script] |
| Table count | [from script] |
| H2 sections | [from script] |
| H3 sections | [from script] |
| Reference pointers (links to references/) | [read SKILL.md, list each with target] |
| External links | [read SKILL.md, list each] |

### Step 4: Code Block Inventory [JUDGMENT]

Read each code block in SKILL.md. For each:

| # | Lines | Language | Purpose (1 phrase) | Start line |
|---|-------|----------|-------------------|------------|

Lines come from observation. Purpose requires reading the content.

### Step 5: Directory Structure [SCRIPT]

Transcribe from script output:
- DIRECTORIES PRESENT: [from script]
- DIRECTORIES ABSENT: [standard dirs not in script's listing]
- FILE COUNT per directory: [from script]
- Total files: [from script]

### Step 6: Reference Hygiene [SCRIPT]

Transcribe from script output:
- BROKEN POINTERS: [from script]
- ORPHAN FILES: [from script]
- NESTING DEPTH: [from script]

Add from your reading of SKILL.md:
- POINTERS IN SKILL.md: List every link/reference to a file in the skill dir.

### Step 7: Content Distribution [SCRIPT]

Transcribe from script output. For each reference file:

| File | Lines | Words | Code block lines |
|------|-------|-------|-----------------|

### Step 8: Second Person Scan [SCRIPT]

Transcribe from script output. List each occurrence with line number.

### Step 9: Voice Consistency [JUDGMENT]

Read the SKILL.md body (not code blocks, not frontmatter). Classify instruction
forms: imperative, second person, passive. Report dominant form and any mixed
usage.

### Step 10: Date References [SCRIPT]

Transcribe from script output. List each with file and line number.

### Step 11: Path Style [SCRIPT]

Transcribe from script output. List any Windows-style backslash paths.

### Step 12: Script Intent Clarity [JUDGMENT]

For each script in scripts/: read it. Classify as execute / read / ambiguous.
Check: does the script's filename and header comment make its purpose clear?

OUTPUT FORMAT: Produce all 12 sections with exact table formats.
Steps marked [SCRIPT]: transcribe numbers exactly. Do not re-count.
Steps marked [JUDGMENT]: analyze the actual content.
Do NOT diagnose. Do NOT suggest.

IMPORTANT — TWO-STEP OUTPUT:

Step A: Write the full 12-section report to /tmp/skill-audit-inspect.md using
the Write tool. This is the detailed artifact for the final report.

Step B: Return ONLY the following compact manifest as your text response to
the orchestrator. Do not repeat the full report in your response.

PHASE 1 MANIFEST:
  frontmatter_present: { name: yes/no, description: yes/no, user-invocable: yes/no, argument-hint: yes/no, disable-model-invocation: yes/no }
  skill_md_words: [N]
  code_blocks: count=[N] total_lines=[N] pct_of_body=[N%]
  broken_pointers: [list or "none"]
  orphan_files: [list or "none"]
  second_person_count: [N]
  date_refs: [N]

EARLY FLAGS (checklist items with direct measurement violations — 1 line each):
  [item id]: [violation] (or "none" if no direct failures)
```

## Why This Is a Phase

The target skill directory can contain thousands of lines across many files.
As a phase, the Haiku agent reads the target files itself — source content
stays in the subagent's context, not the orchestrator's. The orchestrator sends
one path + script output (compact), receives structured measurements (compact).

## Execution Note

The orchestrator runs `measure.sh` BEFORE launching this phase. The script
output is passed as `{measure_sh_output}` in the prompt. This eliminates
Haiku's counting errors — the most common source of Phase 1 inaccuracy.

The Haiku agent still has Read, Glob, and Grep tools. It reads files for the
[JUDGMENT] steps. Do NOT inline skill content in the prompt beyond what the
script already measured.
