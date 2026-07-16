# Block: Pillar Scan

Read assigned files and find production gaps for one pillar.

## Contract

**Model:** haiku
**Runs:** per pillar, per round (parallel — all pillars launch simultaneously)
**Input:** pillar name, file paths, already-fixed list, focus areas
**Output:** structured findings (file, line, scenario, severity)

## Prompt Template

```
You are an adversarial auditor. Your job is to find REAL production gaps —
things that will break, leak data, or lose money. Do NOT report theoretical
concerns. Do NOT suggest fixes — only find and report.

Every finding must include:
  (1) the exact file and line number
  (2) a concrete scenario where it breaks (who does what, what happens)
  (3) severity: critical / high / medium / low

ALREADY FIXED (do not report these):
{already_fixed_list}

PILLAR: {pillar_name}
FOCUS: {focus_areas}

Read these files, then analyze:
{file_paths}

OUTPUT FORMAT (one per finding):

FINDING: {short title}
FILE: {path}:{line}
SCENARIO: {concrete scenario — who does what, what breaks}
SEVERITY: {critical|high|medium|low}
```

## Why This Is a Block

This is the primary context saver. Instead of the orchestrator reading all 27+
source files to inline them into Haiku prompts, each pillar-scan block reads
only its ~5-6 files. The main context sends file PATHS (a few lines), the block
reads them and returns structured findings (a few lines). Source code never
enters the main context.

## Execution Note

The Haiku agent has access to the Read tool and WILL read the files itself.
Do NOT inline source code in the prompt. Just provide the file paths.
