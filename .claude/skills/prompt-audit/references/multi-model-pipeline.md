# Multi-Model Pipeline Pattern

Shared orchestration pattern for skills that use different models at different phases.

## Cognitive Shape Map

Each model has a distinct cognitive shape — not a speed, a shape:

| Model | Shape | Natural Task | Anti-Pattern |
|-------|-------|-------------|--------------|
| Haiku | Gap finder | Local comparison, contradiction detection, missing element search | Synthesis, rewriting, creative production |
| Sonnet | Path router | Solution synthesis, structured rewriting, applying rules | Questioning direction, stepping back |
| Opus | Leverage finder | Holding full context in tension, identifying highest-impact structural change | Being used for every step (expensive, no better than Sonnet for routing) |

## Orchestration Protocol

### 1. Phase Definition

Each phase has:
- A **model** (haiku, sonnet, or opus)
- A **prompt template** (in references/)
- A **context contract** — what it receives and what it produces
- A **rejection boundary** — what it must NOT do (prevents scope creep)

### 2. Context Forwarding

Each phase produces structured output that feeds into the next:

```
Phase 1 (Haiku) → structured analysis (findings, directive map)
     ↓
Phase 2 (Sonnet) → receives original + Phase 1 output → produces revision
     ↓
Phase 3 (Opus) → receives original + Phase 1 + Phase 2 → produces leverage point
```

Critical: Phase 3 needs ALL prior context. If it only receives Phase 2 output, it has nothing to measure leverage against. The leverage IS the gap between original and revision.

### 3. Task Tool Invocation

Each phase runs as a subagent via the Task tool:

```
Task tool call:
  subagent_type: general-purpose
  model: [haiku|sonnet|opus]
  prompt: [phase template with context inlined]
```

Phases run SEQUENTIALLY (each depends on the previous output). Do not parallelize across phases. Within a phase, parallel agents are fine (e.g., harden runs multiple Haiku agents per pillar simultaneously).

### 4. Scope Boundaries

Each phase has a rejection boundary that prevents it from doing the other phases' work:

- **Haiku (find)**: "Do NOT suggest improvements. Only find and report."
- **Sonnet (fix)**: "Do NOT re-analyze. The analysis is done. Trust it."
- **Opus (leverage)**: "Do NOT list multiple improvements. One lever. One move."

These boundaries prevent each model from drifting into a task better suited to another model's cognitive shape.

## Applying the Pattern

### To prompt-audit

1. Read target prompt/skill
2. Phase 1 (Haiku): Extract directives, map dimensions, detect overlaps/conflicts
3. Phase 2 (Sonnet): Resolve overlaps, add priority chains, produce revised version
4. Phase 3 (Opus): Identify the ONE leverage point
5. Present consolidated results

### To harden

1. Identify pillars, read files
2. Per round:
   - Phase 1 (Haiku): Find production gaps (parallel agents, one per pillar)
   - Phase 2 (Sonnet): Fix real findings (targeted code changes)
3. After all rounds:
   - Phase 3 (Opus): Identify the architectural weakness that would prevent the most bug classes
4. Present summary

### To future skills

Any skill that analyzes, fixes, and identifies leverage can use this pattern. The cognitive shapes are stable — they come from the model architecture, not the task domain.
