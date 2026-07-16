---
name: architect-agent
description: >-
  Decomposes feature specifications into validated AI SDK v6 agent scaffolds
  using Hamiltonian coupling analysis and Louvain modularity maximization.
  Consumes crystallize pipeline output (spec + rejection documents) and produces
  typed tool definitions, ToolLoopAgent configs, middleware requirements, and
  telemetry hooks. Also reviews existing agent code — extracts what's implemented,
  identifies SDK violations, and produces a remediation scaffold showing what the
  architecture SHOULD look like. Use this skill whenever asked to plan agent
  architecture, decompose a feature into tools and agents, generate an AI SDK
  scaffold, validate an existing agent architecture, review agent code quality,
  or refactor an agent to use correct SDK patterns. Trigger phrases: "architect
  this feature", "plan the agent structure", "decompose into tools", "what agents
  do I need", "scaffold this", "review this agent", "refactor this to use the
  SDK correctly", "audit my agent code", or when user provides crystallize output
  (spec.md + rejection.md) or existing TypeScript agent source files.
---

# Architect Agent — Orchestrator

5-block sequential pipeline. Each block has one job, one output format, and one
validation gate. Execute blocks in order. Do not skip blocks. Do not merge
blocks. Do not produce output that doesn't match the specified format.

## HARD RULES — Violations Invalidate the Entire Run

1. **DO NOT write implementation code.** This skill ends at the scaffold JSON.
   After VALIDATE passes, present the scaffold and STOP. Implementation is a
   separate step — use `/forge` to build from the scaffold.
2. **DO NOT skip blocks.** Every block runs. Every output gate passes.
3. **DO NOT produce bullet-list code reviews.** The output is typed JSON per
   block, not prose recommendations. If your output looks like a code review,
   you have reverted to training. Start over.
4. **DO NOT invent concern types.** The 9-type basis is fixed. Classify into it.
5. **The coupling matrix and hyperedges are MANDATORY.** They are the
   mathematical foundation of the partition. Without them, the agent tree is
   arbitrary. Skip them and the skill has failed.
6. **Every schema in the scaffold is Valibot.** Zero `z.object()`. Zero Zod
   imports. If Zod appears anywhere in scaffold output, you have failed.

## Mode Detection — Deterministic

Determine the mode from the input. No judgment call.

| Input | Mode | First Block |
|-------|------|-------------|
| spec.md + rejection.md (crystallize output) | `feature` | DECOMPOSE |
| TypeScript source files (.ts) | `review` | EXTRACT |
| Existing scaffold JSON | `validate` | VALIDATE |
| Raw idea, no structured input | `blocked` | Tell user to run `generate_product` first. STOP. |

---

## Block 1a: DECOMPOSE (feature mode only)

**LOAD** `agents/decomposer.md` — that file IS the execution spec for this block.

**INPUT:** Spec document + rejection document from crystallize.

**EXECUTE** the full decomposer process: parse requirements, parse boundaries,
extract data flow, classify into 9-type basis, extract atomicity signals,
cross-check coverage.

**OUTPUT:** `DecomposeOutput` JSON with `featureSummary`, `concerns[]`,
`sharedTypes[]`.

### OUTPUT GATE — All must pass or output is INVALID:

- [ ] Every concern has all required fields: `id`, `name`, `type`,
  `description`, `reads`, `writes`, `state`, `invariants`,
  `externalSystems`, `atomicity`
- [ ] Every concern type is one of the 9: domain, command, query, event,
  adapter_in, adapter_out, transformer, validator, policy
- [ ] Every sharedType has ≥1 writer and ≥1 reader
- [ ] Concern count ≥ number of distinct requirements in spec
- [ ] Every rejection boundary maps to ≥1 invariant on ≥1 concern

**NEXT:** Block 2 (COUPLE)

---

## Block 1b: EXTRACT (review mode only)

**LOAD** `agents/extractor.md` — that file IS the execution spec for this block.

**INPUT:** Array of `{ path, content }` TypeScript source files.

**EXECUTE** the full extractor process: scan tools, scan agents, classify
concerns, extract data flow, flag atomicity, decompose hidden concerns,
audit biomeConfig, check SDK violations, apply Output mapping, run custom
Output detection.

**OUTPUT:** `ExtractOutput` JSON with `featureSummary`, `concerns[]`,
`sharedTypes[]`, `sdkViolations[]`, `missingConcernTypes[]`,
`biomeViolations`, `duplicatedCode`, `dataInCode`.

### OUTPUT GATE — All must pass or output is INVALID:

- [ ] Concern count > number of `tool({` definitions (hidden concerns exist
  in functions >50 lines — if you found the same count, you under-extracted)
- [ ] Every file >300 lines listed with exact line count
- [ ] Every function >50 lines listed with exact line count
- [ ] Total duplicated lines counted (number, not "some duplication exists")
- [ ] Total data-in-code lines counted (number, not "some constants exist")
- [ ] SDK violations listed with: file, line, invariant ID, description, fix
- [ ] `resolveOutputStrategy()` applied to every concern (show the table)
- [ ] All 5 custom Output detection rules run on every concern (show results)

**NEXT:** Block 2 (COUPLE)

---

## Block 2: COUPLE

**LOAD** `agents/coupler.md` — that file IS the execution spec for the LLM
scoring step. The Hamiltonian has 4 terms: data coupling, state coupling,
boundary cost (all deterministic), and domain coupling (LLM-scored).

**INPUT:** All concerns from Block 1 output.

**EXECUTE:**
1. Generate all significant concern pairs.
2. Score each pair's domain coupling (0.0–1.0) with a one-sentence reason.
3. Calibrate: verify >80% score <0.3, only 10-20% score >0.5.
4. Detect hyperedges: multi-way constraints binding 3+ concerns. Types:
   `shared_transaction`, `shared_invariant`, `shared_system`,
   `shared_type_cluster`.
5. Verify pairwise consistency with hyperedges: if A,B,C share a hyperedge,
   then A↔B, A↔C, B↔C must all score ≥0.5.

**OUTPUT:** Coupling matrix (pairs table with numeric scores + reasons) +
hyperedges list (IDs, types, members, rationale).

### OUTPUT GATE — All must pass or output is INVALID:

- [ ] Coupling scores table exists with numeric H values
- [ ] ≥15 scored pairs for codebases with >10 concerns
- [ ] Calibration check stated (what % of pairs score <0.3)
- [ ] Hyperedges listed with IDs, types, member concern IDs, rationale
- [ ] No pairwise score <0.5 between members of the same hyperedge

**NEXT:** Block 3 (PARTITION)

---

## Block 3: PARTITION

Pure computation. No LLM.

**INPUT:** Coupling matrix + hyperedges from Block 2.

**EXECUTE:**
1. Pre-merge: for each hyperedge, treat all member concerns as a single
   super-node that cannot be split.
2. Run Louvain modularity maximization on the coupling matrix.
3. Assign each partition: name, role, model tier (haiku/sonnet/opus by
   cognitive shape, not capability tier).
4. Compute modularity score Q.

**OUTPUT:** Agent tree — partitions with concern assignments, cohesion scores,
model tiers.

### OUTPUT GATE — All must pass or output is INVALID:

- [ ] Every concern assigned to exactly one partition
- [ ] No hyperedge cut (all members of each hyperedge in same partition)
- [ ] No partition has >7 concerns
- [ ] Modularity score Q stated
- [ ] Each partition has name, role, model tier

**NEXT:** Block 4 (SCAFFOLD)

---

## Block 4: SCAFFOLD

**LOAD** `agents/scaffolder.md` — that file IS the execution spec for this
block. Also load `references/scaffold-format.md` for the full JSON format and
`references/output-mapping.md` for the deterministic Output mapping.

**INPUT:** Partition from Block 3 + concerns from Block 1.

**EXECUTE** the full scaffolder process: apply Output mapping, run custom
Output detection, generate tool definitions with literal Valibot code, generate
agent definitions with callOptionsSchema + prepareCall + prepareStep +
telemetryIntegration, identify middleware, define telemetry hooks, cross-check.

**OUTPUT:** Scaffold JSON per `references/scaffold-format.md` — tools, agents,
middleware, telemetry, biomeConfig, coherenceScore, violations, pass.

### OUTPUT GATE — All must pass or output is INVALID:

- [ ] `resolveOutputStrategy()` mapping table shown for every concern
- [ ] All 5 custom Output detection rules run, results shown per concern
- [ ] Every tool has `inputSchemaCode` containing literal `v.object({...})`
- [ ] Every tool has `outputSchemaCode` or `customOutputCode`
- [ ] Every tool has `executeSignature`
- [ ] Every agent has `callOptionsSchemaCode` with literal
  `valibotSchema(v.object({...}))`
- [ ] Every agent has `prepareCallCode` and `prepareStepCode`
- [ ] Every agent has `telemetryIntegrationCode`
- [ ] Zero references to `z.object()` anywhere in scaffold output
- [ ] `biomeConfig` section present with maxLinesPerFile,
  maxLinesPerFunction, maxCognitiveComplexity

**NEXT:** Block 5 (VALIDATE)

---

## Block 5: VALIDATE

**LOAD** `agents/validator.md` — that file IS the execution spec for this block.

**INPUT:** Scaffold from Block 4 + partition from Block 3 + original input
from Block 1.

**EXECUTE** the full validator process: 18 deterministic checks, coupling
integrity, hyperedge integrity, requirement coverage, rejection enforcement,
custom Output validation, invariant gap detection, coherence score computation.

**OUTPUT:** Validation result — coherenceScore, violations[], pass, summary.

**PASS THRESHOLD:** coherence ≥ 0.70 AND zero critical violations.

### OUTPUT GATE — All must pass or output is INVALID:

- [ ] All 18 deterministic checks run with explicit pass/fail for each
- [ ] Coupling integrity checked — specific H values cited for high pairs
- [ ] Hyperedge integrity checked — specific hyperedge IDs cited
- [ ] Coherence score computed — formula shown with per-violation deductions
- [ ] Every violation has: type, severity, message, concernIds, suggestion
- [ ] Pass/fail decision stated

### After VALIDATE:

- **PASS:** Present scaffold to user. STOP. Do not write implementation code.
- **FAIL:** Re-run from Block 1 with violations as feedback. Max 2 cycles.
  After 2 failed cycles, present the best scaffold with its violations and
  let the user decide.

---

## Context Forks

Each agent `.md` file is a self-contained execution spec. Load only the
relevant one per block. Each block gets exactly the context it needs.

| Block | Agent File | Model | Cognitive Shape |
|-------|-----------|-------|----------------|
| DECOMPOSE | `agents/decomposer.md` | Sonnet | Classification + extraction |
| EXTRACT | `agents/extractor.md` | Sonnet | Code reading + reverse decomposition |
| COUPLE | `agents/coupler.md` | Haiku | Pairwise semantic scoring |
| SCAFFOLD | `agents/scaffolder.md` | Opus | Structural analysis + code gen |
| VALIDATE | `agents/validator.md` | Sonnet | Coherence checking |

Eval agents (grader, comparator, analyzer) run during benchmarking only.

## Reference Files

Load when a block requires them. Do not load all upfront.

| File | Load When |
|------|-----------|
| `references/scaffold-format.md` | Block 4 — full scaffold JSON format |
| `references/custom-output-patterns.md` | Block 4 — a concern triggers custom Output |
| `references/sdk-invariants.md` | Block 1b — checking SDK compliance |
| `references/concern-type-basis.md` | Block 1 — classifying concerns |
| `references/output-mapping.md` | Block 4 — resolveOutputStrategy() |
| `references/review-rejections.md` | Canonical reference — key rules inlined into each block's GATE |

## Quick Start

```ts
import { anthropic } from "@ai-sdk/anthropic";
import { createArchitectAgent } from "./scripts/agent.js";

const agent = createArchitectAgent({
  models: {
    decompose: anthropic("claude-sonnet-4-5-20250929"),
    couple:    anthropic("claude-haiku-4-5-20251001"),
    scaffold:  anthropic("claude-opus-4-6"),
    validate:  anthropic("claude-sonnet-4-5-20250929"),
  },
});

const result = await agent.generate({
  prompt: JSON.stringify({
    mode: "feature",
    title: "Story Building Agent",
    slug: "story-builder",
    spec: specContent,
    rejection: rejectionContent,
  }),
});
```

### Review Existing Agent

```ts
import { readFileSync, readdirSync } from "node:fs";
import { anthropic } from "@ai-sdk/anthropic";
import { reviewExistingAgent } from "./scripts/agent.js";

const agentDir = "./my-agent/src";
const sourceFiles = readdirSync(agentDir)
  .filter((f) => f.endsWith(".ts"))
  .map((f) => ({
    path: f,
    content: readFileSync(`${agentDir}/${f}`, "utf-8"),
  }));

const result = await reviewExistingAgent(
  {
    models: {
      decompose: anthropic("claude-sonnet-4-5-20250929"),
      couple:    anthropic("claude-haiku-4-5-20251001"),
      scaffold:  anthropic("claude-opus-4-6"),
      validate:  anthropic("claude-sonnet-4-5-20250929"),
    },
  },
  {
    title: "Story Building Agent",
    description: "Builds interactive stories with branching narratives",
    sourceFiles,
  }
);
```

## Eval Strategy

Four-agent eval pipeline:

1. **Executor**: Runs architect against test specs
2. **Grader**: Evaluates scaffold against assertions → `grading.json`
3. **Comparator**: Blind A/B between scaffold versions
4. **Analyzer**: Surfaces non-discriminating assertions, high-variance, cost
   tradeoffs

Test cases in `evals/cases/`. Assertions in `evals/evals.json`.

## Dependencies

```
ai ^6.0.0    @ai-sdk/anthropic ^1.0.0    @ai-sdk/valibot ^1.0.0
@ai-sdk/devtools ^0.1.0    valibot ^1.0.0
```

## Files

```
architect-agent/
├── SKILL.md                    ← you are here (orchestrator)
├── agents/                     ← execution specs (one per block)
│   ├── decomposer.md          ├── extractor.md
│   ├── coupler.md              ├── scaffolder.md
│   ├── validator.md            ├── grader.md
│   ├── comparator.md          └── analyzer.md
├── scripts/
│   ├── schemas.ts              ← Valibot schemas
│   ├── agent.ts                ← ToolLoopAgent orchestrator + loadAgentFiles()
│   ├── tools/pipeline.ts       ← 6 tool() definitions (5 pipeline + EXTRACT)
│   ├── math/coupling.ts        ← Hamiltonian + hyperedge detection
│   ├── math/partition.ts       ← Louvain modularity maximization
│   ├── validation/custom-output.ts ← Custom Output interface validation
│   └── middleware/telemetry.ts  ← TelemetryIntegration class
├── references/
│   ├── scaffold-format.md
│   ├── custom-output-patterns.md
│   ├── sdk-invariants.md
│   ├── concern-type-basis.md
│   ├── output-mapping.md
│   └── review-rejections.md
└── evals/
    ├── evals.json
    └── cases/
```
