# Block: Orchestrator

Execution protocol for the forge pipeline. The orchestrator reads this block
at the start of the pipeline — it is not launched as a subagent.

## Process

### 1. Ingest (Haiku)

Read [`1-ingest.md`](1-ingest.md). Launch one Haiku agent:

```
Task tool, model: haiku, subagent_type: general-purpose
```

Input: `$ARGUMENTS` path to the product directory (e.g., `web/preview/{slug}/`)
or to individual markdown/HTML files. The directory may contain HTML files
(index.html, prd.html, rejection.html) or markdown files (prd.md, rejection.md,
or a single combined product brief). meta.json is optional. If any file is
missing, report MISSING for that file's sections — do not fail.

**Format detection:** The ingest block auto-detects input format by checking
file extensions. HTML inputs use CSS selector extraction per
[`../references/html-extraction.md`](../references/html-extraction.md).
Markdown inputs use heading/section extraction per
[`../references/md-extraction.md`](../references/md-extraction.md).

Output: Structured extraction of all product data — components, build sequence,
connections, quality profile, open questions, immune memory, assumptions,
anti-patterns, rejected patterns. Same output schema regardless of input format.

### 2. Plan (Sonnet)

Read [`2-plan.md`](2-plan.md). Launch one Sonnet agent:

```
Task tool, model: sonnet, subagent_type: general-purpose
```

Input: Ingest output + codebase file listing (the agent reads the codebase
structure using Glob/Read tools).

Output:
- **BUILD_ITEMS**: Ordered list of components to implement, each with files,
  constraints, rejections, immune memory, and acceptance criteria
- **BUILD_WAVES**: BuildItems grouped by dependency level. Items in the same
  wave have no mutual dependencies and run concurrently. Wave N+1 starts
  only after all items in wave N complete.
- **DEPLOY_STEPS**: Items from the PRD build sequence that are deployment
  actions (publish, release, deploy), not code implementations. Excluded
  from the build/verify loop. Reported in the forge summary as manual
  post-forge actions with their pre-conditions listed.
- **INTERFACE_CONTRACTS**: Per-component exports (name, shape, consumers) and
  imports (name, shape, provider) derived from CONNECTIONS + DATA_FLOW.
  Forwarded to Build, Verify, and Assemble.
- **CONSTRAINT_MATRIX**: Each component mapped to applicable harden constraints
- **REJECTION_MAP**: Each PRD rejection mapped to specific components
- **IMMUNE_MEMORY_ACTIVE**: Evidence-based overrides loaded as constraints
- **OPEN_QUESTION_IMPACT**: How unresolved questions affect implementation

### 3. Build Loop (Sonnet x N, wave-parallel)

The Plan phase outputs BUILD_WAVES — groups of BuildItems whose dependencies
are satisfied by prior waves. Items within the same wave have no dependencies
on each other and run concurrently. Observations from wave W are accumulated
and forwarded to all items in wave W+1.

For each wave, for each BuildItem in the wave (launch concurrently):

#### 3a. Build (worktree-isolated)

Read [`3-build.md`](3-build.md). Launch one Sonnet agent per
BuildItem in the wave:

```
Task tool, model: sonnet, subagent_type: general-purpose, isolation: worktree
```

Each Build agent runs in an isolated git worktree. This prevents concurrent
agents in the same wave from interfering with each other's file writes.

Input: One BuildItem with its interface contract, constraints, rejections,
and immune memory inlined in the prompt. The interface contract declares what
this component must export and what it may import — the builder implements to
these shapes. Observations from all prior waves are included — forwarded
verbatim from the orchestrator state.

Output: Implemented component files, self-check results, observations,
worktree branch name.

After all Build agents in a wave complete, merge each worktree branch back
into the main branch. If a merge conflicts (two builders modified the same
file despite being assigned different files), report it as an
INTEGRATION_ISSUE — surface it early rather than at Assembly.

#### 3b. Verify (dual-fork)

After worktree merges complete for the wave, launch two Haiku agents per
component in parallel — a systematic checker and an adversarial prober:

**Fork A — Systematic** ([`4-verify.md`](4-verify.md)):
```
Task tool, model: haiku, subagent_type: general-purpose
```
Runs Steps 1-5: acceptance criteria, interface contract, harden constraints,
rejection boundaries. Checklist mode.

**Fork B — Adversarial** ([`4-verify.md`](4-verify.md),
Step 6 only):
```
Task tool, model: haiku, subagent_type: general-purpose
```
Runs Step 1 (read files) then Step 6: edge case probing, null inputs,
mid-way failures, swallowed errors. Hunting mode.

Input (both forks): File paths from Build output + component's interface
contract + harden constraints + acceptance criteria.

Output: Merge results from both forks. If either fork reports a FAIL, the
component FAILs. BLOCKS_ASSEMBLY_COUNT is the sum from both forks.

#### 3c. Handle Failures

If merged Verify reports `BLOCKS_ASSEMBLY_COUNT > 0`:
- Re-invoke Build with the FAIL findings from both forks (not passes).
  Max 2 retries.
- On the second retry, include: original failures + first-retry failures.
- If both retries fail: continue to next component, flag component as
  ESCALATED, and forward ESCALATED status to Fortify.

Wait for all items in the wave to complete (including retries) before
starting the next wave. Update orchestrator state: component name, verdict,
fail count.

### 4. Assemble (Sonnet)

Read [`5-assemble.md`](5-assemble.md). Launch one Sonnet agent:

```
Task tool, model: sonnet, subagent_type: general-purpose
```

Input: All component file paths, verify summaries, interface contracts (full set),
connection table from ingest, data flow from ingest.

Output: Integration layer — imports, exports, entry points, route registrations,
type definitions. Report INTEGRATION_ISSUE for anything requiring component
changes; do not make those changes.

### 5. Fortify (Opus, worktree-isolated)

Read [`6-fortify.md`](6-fortify.md). Launch one Opus agent:

```
Task tool, model: opus, subagent_type: general-purpose, isolation: worktree
```

Fortify runs in a worktree so the orchestrator can inspect the diff before
merging. If the fix is clean, merge. If the diff looks wrong or touches too
many files, the orchestrator can reject it and report UNSTABLE.

Input: All file paths (components + integration), ingest output, all verify
results, assembly output, and any ESCALATED component flags. Opus reads all
files directly.

Output: ONE architectural weakness with fix applied, OR CLEAN confirmation
with evidence. "Unstable" means: the fix applied could not be verified against
all call sites — flag in Fortify result, not a separate report.

### 6. Summary

Compose the forge report in main context using data collected during the
pipeline. The orchestrator has all the data it needs — component statuses,
verify verdicts, assembly status, fortify result — without ever having held
source code. See Output section in SKILL.md.
