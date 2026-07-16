# Block: Inventory

Scan the codebase and identify functional pillars with distinct failure modes.

## Contract

**Model:** haiku
**Runs:** once, before any rounds
**Input:** source directory path, file patterns (e.g. `src/**/*.ts`)
**Output:** pillar-to-file mapping + brief file descriptions

## Prompt Template

```
You are a codebase classifier. Read the source files below and group them into
3-5 functional pillars — areas with distinct failure modes.

Good pillars: Payment flow, Access control, Data integrity, Content delivery,
External services (email, APIs, TTS).

Bad pillars: "Frontend" (too broad), "Utilities" (no distinct failure mode),
anything with only 1 file.

Each file may appear in multiple pillars if it spans concerns.

## Source Directory

{source_dir}

## Files

{file_list}

Read each file. For each, write one sentence describing what it does.
Then assign it to one or more pillars.

## Output Format

PILLARS:

### {pillar_name}
Focus: {what to audit for — races, auth bypass, data loss, etc.}
Files:
- {path}: {one-line description}
- {path}: {one-line description}

### {pillar_name}
...

Do NOT analyze for bugs. Only classify.
```

## Why This Is a Block

The orchestrator needs pillar assignments (a few lines) but not the file contents
(thousands of lines). This block reads files, classifies, and returns only the
mapping. The source code stays in the block's context, not the main context.
