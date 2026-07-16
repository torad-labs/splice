# Agent Audit — Orchestration Protocol

Procedural protocol for the orchestrator. Defines context budget, temp file
strategy, dispatch table, gates, parallelization rules, code extraction
patterns, per-phase context guidance, and final assembly.

## Context Architecture (Budget)

The orchestrator holds in its own context:

- Agent directory path (1 line)
- Script output from `inventory.sh` (compact structured text)
- Agent map (~20-30 lines, built during inventory step)
- Phase 1 compact manifests (~3 lines per block × N blocks)
- Phase 2 compact summary (~10 lines)
- Phase 3 leverage point (~15 lines, terminal output)

**Nothing else.** No full Phase 1 directive maps. No full Phase 2 findings.
No target agent source code. No reference document content. No types or
tool schema content.

Full phase artifacts live in `/tmp/` files. Subagents read them directly.

## Temp File Strategy

```
/tmp/agent-audit-inventory.txt        ← inventory.sh output
/tmp/agent-audit-extract-{block}.md   ← Phase 1 per-block full output
/tmp/agent-audit-synthesize.md        ← Phase 2 full output
```

Phase 3 has no temp file — it's the terminal phase, output goes directly
to the orchestrator for final assembly.

## Dispatch Table

| Step | Action | Model | Input | Output |
|------|--------|-------|-------|--------|
| 0 | `scripts/inventory.sh` | bash | agent dir path | `/tmp/agent-audit-inventory.txt` |
| 1 | Build agent map | orchestrator | script output + file reads | agent map (in orchestrator context) |
| 2 | Phase 1: Extract | Haiku (×N or ×1) | `blocks/extract.md` + per-block context | `/tmp/agent-audit-extract-{block}.md` + compact manifests |
| 3 | Phase 2: Synthesize | Sonnet | `blocks/synthesize.md` + agent map + manifests | `/tmp/agent-audit-synthesize.md` + compact summary |
| 4 | Phase 3: Leverage | Opus | `blocks/leverage.md` + agent map + manifests + summary | leverage point (direct) |
| 5 | Assemble | orchestrator | read all `/tmp/` files + Phase 3 | final report |

## Parallelization Rules

Read boundary count from agent map:

- **4+ boundaries**: One Haiku subagent per block, all launched in the same
  Task tool message (parallel). Each writes its own temp file.
- **3 or fewer**: One Haiku subagent with all blocks labeled. Writes one
  combined temp file.

## Gate Validation

| Phase | Gate Check | On Failure |
|-------|-----------|------------|
| Script | Exit code 0, output non-empty | Halt, report to user |
| Phase 1 | Each subagent response contains `DIRECTIVE MAP:` and `BOUNDARY SPECS:` (case-sensitive, must match exact section headers in blocks/extract.md STEP A) | Re-run that block. Two failures: halt. |
| Phase 2 | Response contains `PER-BOUNDARY FINDINGS:` and `REVISED PROMPTS:` | Re-run. Two failures: halt. |
| Phase 3 | Response contains exactly one `LEVERAGE POINT:` | Re-run. Two failures: halt. |

## Code Extraction Patterns

Guidance for the orchestrator when building Phase 1 context. Three patterns
for system prompts in TypeScript:

1. **Constant**: `const SYSTEM_PROMPT = \`...\``
2. **Builder**: `function buildSystemPrompt(input): string { return \`...\` }`
3. **Inline**: `system: \`You are...\``

For builders with interpolation: note dynamic fields as `${variable}`.
Flag any interpolation that could produce a contradictory directive for
certain input values.

## Context to Include Per Phase

### Phase 1 (Extract) — per block

- Block name, file path, model assignment, pipeline position
- Upstream/downstream block names and types
- Downstream tool schema fields (field names, types, required/optional)
- The system prompt text (extracted from code by orchestrator)
- User message template if any
- Do NOT include other blocks' prompts
- Do NOT include reference documents

### Phase 2 (Synthesize)

- Agent map (compact, from orchestrator context)
- Phase 1 compact manifests (from orchestrator context)
- Paths to Phase 1 temp files (subagent reads them)
- Paths to types/tools source files (subagent reads them)
- Paths to `references/best-practices.md` and `references/agent-architecture.md`
  (subagent reads them)
- Do NOT inline types, tools, or reference content

## Template Fill Procedure (Phase 1)

For each block, the orchestrator fills blocks/extract.md mechanically:

1. Read blocks/extract.md as a string
2. Replace {block_name} with the block's name from the agent map
3. Replace {block_number} with the pipeline position
4. Replace {model} with the model assignment
5. Replace {upstream} with upstream block name + TypeScript type
6. Replace {downstream} with downstream block name + tool name
7. Replace {downstream_tool_schema} with tool schema fields
   (extract from tools.ts: field names, types, required/optional)
8. Replace {system_prompt} with the extracted system prompt text
9. Replace {user_message_template} with the user message construction
10. The filled template becomes the subagent prompt

Pass the filled template to the Task tool with model: "haiku".
Include in the task prompt: the temp file path for STEP A output.

### Phase 3 (Leverage)

- Agent map (compact)
- Phase 1 compact manifests
- Phase 2 compact summary
- Paths to all temp files (subagent reads them)
- Do NOT include reference documents (Opus needs artifacts, not frameworks)
- Do NOT include raw code

## Final Assembly

After Phase 3 completes, the orchestrator:

1. Reads all `/tmp/agent-audit-*` files
2. Reads the Phase 3 response
3. Formats everything into the Output Contract from SKILL.md
4. Presents the assembled report to the user
