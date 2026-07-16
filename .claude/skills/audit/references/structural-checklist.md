# Structural Checklist

Source of truth for Phase 2 diagnosis. Every item is checked against Phase 1
measurements. Derived from Anthropic's official documentation:

- [Agent Skills Best Practices](https://platform.claude.com/docs/en/docs/agents-and-tools/agent-skills/best-practices)
- [Agent Skills Overview](https://platform.claude.com/docs/en/docs/agents-and-tools/agent-skills/overview)
- [Claude Code Skills](https://code.claude.com/docs/en/skills)
- [Engineering blog: Equipping agents for the real world](https://claude.com/blog/equipping-agents-for-the-real-world-with-agent-skills)
- [The Complete Guide to Building Skills for Claude](https://resources.anthropic.com/hubfs/The-Complete-Guide-to-Building-Skill-for-Claude.pdf)

## Evaluation Modes

<evaluation-modes>
Items are tagged by how the diagnose block should evaluate them:

- **Untagged** — mechanical. Check against Phase 1 measurements directly.
  The measurement IS the verdict. No interpretation.

- **`<judgment>`** — requires parallel hypothesis evaluation in thinking space
  before committing to PASS or FAIL. Each tag contains two hypotheses, a test,
  and examples. Evaluate evidence for BOTH hypotheses. Commit only after both
  have been tested against the specific skill being audited.
</evaluation-modes>

## Contents

- [1. Progressive Disclosure](#1-progressive-disclosure)
- [2. Description Quality](#2-description-quality)
- [3. Frontmatter](#3-frontmatter)
- [4. Body Quality](#4-body-quality)
- [5. Reference Hygiene](#5-reference-hygiene)
- [6. Scripts and Examples](#6-scripts-and-examples)
- [7. Structural Coherence](#7-structural-coherence)
- [8. Block Architecture Conformance](#8-block-architecture-conformance)
- [9. Eval Coverage](#9-eval-coverage)
- [10. Claude 4 Compliance](#10-claude-4-compliance)

## 1. Progressive Disclosure

### 1.1 SKILL.md Size

- **Standard**: SKILL.md body (excluding frontmatter) under 500 lines.
- **Soft target**: 1500-2000 words for the body. Hard ceiling: 5000 words.
- **Measurement**: Phase 1 "Total lines" and "Total words" metrics.
- **Rationale**: SKILL.md loads into context on every trigger. Bloated skills
  waste tokens on every invocation.

### 1.2 Code Block Ratio

- **Standard**: Code blocks should be less than 30% of SKILL.md body lines.
- **Measurement**: Phase 1 "Code blocks as % of body".
- **Rationale**: High code ratio means implementation detail is displacing
  instructional content. Code examples longer than 10 lines usually belong
  in references/.

### 1.3 Token Cost Justification

<judgment id="1.3">

- **Standard**: Every paragraph and code block in SKILL.md should justify its
  token cost. Claude is already very smart — only add context it does not
  already have.

**Hypothesis PASS**: The content provides project-specific context, domain
patterns, or constraints Claude cannot infer from the codebase alone.

**Hypothesis FAIL**: The content explains well-known concepts (HTTP, JSON,
common libraries, file formats) that Claude already knows.

**Test**: Remove the paragraph mentally. Would Claude produce the same output
without it? If yes — wasted tokens (FAIL). If no — necessary context (PASS).

**Example PASS**: "Stripe webhooks must be verified using the raw body, not
parsed JSON" — project-specific constraint.
**Example FAIL**: "Stripe is a payment processing platform that handles credit
card transactions" — well-known, Claude already knows this.

</judgment>

- **Source**: Anthropic best practices — "Default assumption: Claude is
  already very smart."

### 1.4 Content Level Assignment

Three disclosure levels:

| Level | Container | When Loaded | Budget |
|-------|-----------|-------------|--------|
| 1 | Frontmatter description | Always (skill matching) | ~100 tokens |
| 2 | SKILL.md body | On skill trigger | < 5,000 tokens |
| 3 | references/, scripts/, examples/ | On demand via Read | Unlimited |

- **Standard**: Content lives at the LOWEST level where it remains discoverable.

<judgment id="1.4">

**Hypothesis PASS**: Content at each level serves that level's function.
SKILL.md has orientation content (what, when, where to find detail). References
have execution content (how, full protocols, templates).

**Hypothesis FAIL**: SKILL.md contains execution-level detail (complete
functions, full protocols, config templates) that Claude only needs while
executing, not when deciding to invoke.

**Test**: Would Claude need this content to understand WHAT the skill does and
WHEN to use it? If yes — stays in SKILL.md. If only needed during EXECUTION —
belongs in references/.

**Example PASS**: Summary table of blocks with model and purpose (orientation).
**Example FAIL**: 40-line config template with all fields documented (execution).

</judgment>

- **Violation signal**: A code block in SKILL.md that is a complete helper
  function, config template, or reference implementation (not a brief example
  illustrating a concept).

### 1.5 Reference Depth

- **Standard**: References are one level deep from SKILL.md. No
  `references/sub/deep/file.md` nesting.
- **Measurement**: Phase 1 "Nesting depth".
- **Rationale**: Claude may partially read deeply nested files (using
  `head -100` instead of full reads). One level deep ensures complete reads.
- **Source**: Anthropic best practices — "Keep references one level deep
  from SKILL.md."

## 2. Description Quality

### 2.1 Voice

- **Standard**: Third person. "This skill should be used when..."
- **Violation**: Second person ("Use this skill when...") or imperative
  ("Audit the skill when...").
- **Measurement**: Phase 1 "VOICE" field.

### 2.2 Trigger Phrases

- **Standard**: 3 or more quoted trigger phrases that match what users
  actually type.
- **Measurement**: Phase 1 "TRIGGER COUNT".
- **Quality check**: Each phrase should be concrete ("create playwright tests")
  not generic ("work with tests").

### 2.3 What AND When

- **Standard**: Description says WHAT the skill does, WHEN to use it, and
  key capabilities or specific tasks it handles.
- **Violation**: Description only states what ("Audits skill structure") without
  when triggers, or only states triggers without what it produces.

### 2.4 Description Length

- **Standard**: Maximum 1024 characters. No XML tags.
- **Measurement**: Phase 1 description character count.
- **Rationale**: Hard limit from the Agent Skills specification. XML tags in
  descriptions can interfere with system prompt injection.
- **Source**: Anthropic Skills overview — "Maximum 1024 characters, non-empty,
  no XML tags."

### 2.5 Non-Overlap

- **Standard**: Trigger phrases do not overlap with other skills in the same
  project. If overlap exists, descriptions include negative triggers for
  disambiguation ("Do NOT use for X — use Y skill instead").
- **Check method**: Compare triggers against other skill descriptions in
  `.claude/skills/*/SKILL.md`.

## 3. Frontmatter

### 3.1 Required Fields

- **Standard**: `name` and `description` always present.
- **Measurement**: Phase 1 frontmatter inventory.

### 3.2 Name Convention

- **Standard**: Lowercase letters, numbers, and hyphens only. Max 64 characters.
  No XML tags. Cannot contain reserved words "anthropic" or "claude".
  Descriptive of the action (gerund form preferred: "processing-pdfs"
  over "pdf-processor"). Avoid vague names: "helper", "utils", "tools".
- **Measurement**: Phase 1 `name` field value.
- **Source**: Anthropic Skills overview — hard validation rules.

### 3.3 Argument Hint

- **Standard**: Present if the skill accepts arguments. Uses bracket notation
  with a descriptive label: `"[scope: what to test]"`.
- **Measurement**: Phase 1 `argument-hint` field.

### 3.4 Invocation Control

- **Standard**: `user-invocable` and `disable-model-invocation` set
  intentionally, not left at defaults without consideration.
- **Check**: If the skill has side effects (deploys, sends messages),
  `disable-model-invocation: true` is recommended.
- **Structural implication**: If `disable-model-invocation: true`, the
  description is NOT loaded into Claude's context. Trigger phrases serve only
  the user's autocomplete, not Claude's skill matching. If `user-invocable:
  false`, the skill is hidden from the `/` menu but the Skill tool can still
  access it — `argument-hint` serves no purpose.

### 3.5 Folder Naming

- **Standard**: Skill folder uses kebab-case only. No spaces, underscores,
  or capital letters. Example: `notion-project-setup`, not
  `Notion_Project_Setup`.
- **Measurement**: Phase 1 folder name check.
- **Source**: Complete Guide to Building Skills for Claude.

### 3.6 Name Matches Folder

- **Standard**: The `name` field in frontmatter matches the skill's directory
  name.
- **Measurement**: Phase 1 name-folder comparison.
- **Rationale**: If `name` is omitted, Claude Code uses the directory name.
  An explicit `name` that differs creates ambiguity.
- **Source**: Complete Guide to Building Skills for Claude.

### 3.7 No Prohibited Files

- **Standard**: The skill directory does not contain `README.md`. All
  documentation belongs in `SKILL.md` or `references/`.
- **Measurement**: Phase 1 file listing.
- **Rationale**: `README.md` risks being read as skill content, wasting tokens
  on human-facing documentation that duplicates `SKILL.md`.
- **Source**: Complete Guide to Building Skills for Claude.

### 3.8 Additional Frontmatter Fields

- **Standard**: If present, optional frontmatter fields (`allowed-tools`,
  `model`, `agent`, `hooks`, `compatibility`) are structurally valid.
- **Check**: `agent` requires `context: fork` to also be set. `compatibility`
  must be under 500 characters. `allowed-tools` lists valid tool names.
- **Measurement**: Phase 1 frontmatter field inventory.
- **Source**: Claude Code Skills documentation — frontmatter reference.

## 4. Body Quality

### 4.1 Instruction Voice

- **Standard**: Imperative/infinitive form. "Validate the input." not
  "You should validate the input."
- **Measurement**: Phase 1 "Voice Consistency" — dominant form and mixed usage.

### 4.2 No Second Person

- **Standard**: SKILL.md body does not use "you", "your", etc.
- **Measurement**: Phase 1 "Second Person Scan" count.
- **Exception**: "you" inside code blocks or quoted user speech is fine.

### 4.3 Consistent Terminology

- **Standard**: Same concept uses the same term throughout. Not "test file"
  in one section and "spec file" in another without establishing equivalence.
- **Check method**: Manual review of Phase 1 section headers and key terms.

### 4.4 Tables for Structured Data

- **Standard**: When presenting 3+ items with 2+ attributes each, use a table.
  Not bullet lists or prose.
- **Measurement**: Phase 1 "Table count" vs content structure.

### 4.5 No Time-Sensitive Information

- **Standard**: No date-conditional instructions ("If before August 2025,
  use the old API"). Use "Current method" / "Old patterns" sections instead.
- **Check**: Scan for date references, "before/after [date]" patterns,
  version-specific conditionals that will become stale.
- **Source**: Anthropic best practices — "Don't include information that
  will become outdated."

### 4.6 No Over-Explanation

<judgment id="4.6">

- **Standard**: Do not explain concepts Claude already knows. Every paragraph
  should justify its token cost.

**Hypothesis PASS**: The paragraph provides project-specific context that
changes how Claude should behave. Without it, Claude would make incorrect
assumptions about this project's patterns.

**Hypothesis FAIL**: The paragraph explains a general concept (HTTP, JSON,
common libraries) without connecting it to a project-specific constraint.
Claude already knows the general concept.

**Test**: Same as 1.3 — remove mentally, check if output changes. Additionally:
does the paragraph connect the concept to a project-specific constraint? If it
explains "what X is" without "how X works differently here" — FAIL.

**Example PASS**: "D1 returns rows as arrays of objects, not a cursor — always
destructure the results array" — general concept + project-specific behavior.
**Example FAIL**: "D1 is Cloudflare's serverless SQL database based on SQLite" —
Claude already knows this.

</judgment>

- **Source**: Anthropic best practices — "Default assumption: Claude is
  already very smart."

### 4.7 Appropriate Degrees of Freedom

<judgment id="4.7">

- **Standard**: Match specificity to task fragility. High freedom for
  context-dependent decisions, low freedom for fragile/error-prone operations.

**Hypothesis PASS**: Rigid instructions protect fragile operations. Flexible
instructions leave room for context-dependent decisions. The specificity
matches the consequence of getting it wrong.

**Hypothesis FAIL**: Rigid instructions constrain cosmetic or subjective
decisions. Vague instructions leave fragile operations under-specified.

**Test**: What happens if Claude makes the wrong choice at this point? If the
consequence is severe (data loss, broken UX, security breach) — instruction
should be rigid. If recoverable or cosmetic — should be flexible.

**Example PASS**: "ALWAYS run migrations in a transaction" — rigid, fragile.
**Example PASS**: "Format code reviews for readability" — flexible, subjective.
**Example FAIL**: "ALWAYS use exactly 4 spaces in code reviews" — rigid, cosmetic.
**Example FAIL**: "Deploy as appropriate" — vague, fragile.

</judgment>

- **Source**: Anthropic best practices — "Set appropriate degrees of freedom."

### 4.8 No Multiple Options Without Default

- **Standard**: Do not present multiple tools/libraries/approaches without
  recommending one as default. "You can use pypdf, or pdfplumber, or
  PyMuPDF..." forces Claude to choose without context.
- **Fix**: Provide a default with an escape hatch for specific scenarios.
- **Source**: Anthropic best practices — "Avoid offering too many options."

## 5. Reference Hygiene

### 5.1 No Broken Pointers

- **Standard**: Every link in SKILL.md points to a file that exists on disk.
- **Measurement**: Phase 1 "BROKEN POINTERS" list.

### 5.2 No Orphan Files

- **Standard**: Every file in the skill directory is referenced from SKILL.md
  (directly or transitively via another referenced file).
- **Measurement**: Phase 1 "ORPHAN FILES" list.

### 5.3 No Content Duplication

<judgment id="5.3">

- **Standard**: The same content at the same granularity does not appear in
  both SKILL.md and a reference file. One location is the source; the other
  has a pointer.

**Hypothesis PASS (progressive disclosure)**: The SKILL.md version is a
compressed summary (fewer columns, less detail, shorter) that previews
expanded content in references/. It serves orientation/discovery. The reference
version serves execution/implementation. Different function at each level.

**Hypothesis FAIL (duplication)**: The content is identical in granularity and
function at both levels. Same paragraphs, same tables, same detail. No
compression, no function difference.

**Test** (3 questions):
1. Does the SKILL.md version serve a DIFFERENT function than the reference?
2. Is the SKILL.md version compressed (fewer columns, less detail)?
3. Would a typical change to reference content require changing SKILL.md?
If 1=yes and 2=yes and 3=no — PASS. Otherwise — FAIL.

**Example PASS**: 3-column summary table in SKILL.md (Block, Model, Purpose)
with 6-column dispatch table in references/ (adds Inputs, Reads, Gate).
**Example PASS**: One-sentence description in SKILL.md body with full protocol
in references/.
**Example FAIL**: Same 3-paragraph explanation in both files.
**Example FAIL**: Identical table copy-pasted into both files.

</judgment>

- **Check method**: Phase 2 content comparison with progressive disclosure
  awareness.

### 5.4 Long References Have TOC

- **Standard**: Reference files (`references/`) over 100 lines should have a
  table of contents or clear section headers navigable by search. Phase files
  (`phases/`) are exempt — they are subagent prompt templates dispatched as
  payloads, not documents the orchestrator navigates by heading.
- **Measurement**: Phase 1 "Content Distribution" table — lines per file.
  Apply only to files in `references/`, not `phases/`.
- **Source**: Anthropic best practices — "For reference files longer than
  100 lines, include a table of contents at the top."

### 5.5 Reference File Naming

- **Standard**: File names describe content, not sequence. "stripe-checkout.md"
  not "ref-03.md". "phase-find.md" not "step1.md".
- **Measurement**: Phase 1 directory listing.

### 5.6 Reference Pointer Quality

- **Standard**: When SKILL.md references a file, the pointer includes a brief
  description of the file's content. Not just the filename.
- **Good**: `[references/orchestration.md](references/orchestration.md) — dispatch table, gates, output contract`
- **Bad**: `[references/orchestration.md](references/orchestration.md)`
- **Rationale**: Claude uses pointer descriptions to decide whether to read a
  file. Bare pointers force speculative reads. Described pointers enable
  informed loading decisions.
- **Source**: Complete Guide to Building Skills for Claude.

## 6. Scripts and Examples

### 6.1 Deterministic Operations

- **Standard**: Operations that should produce identical results every time
  (validation, formatting, file generation from template) belong in scripts/,
  not as inline instructions.
- **Check**: Are there instructions in SKILL.md that describe a mechanical
  process Claude will re-derive each time?

### 6.2 Example Quality

- **Standard**: Examples in examples/ are complete, runnable, and demonstrate
  the skill's output — not fragments or pseudocode.
- **Check**: If examples/ exists, each file should be self-contained.

### 6.3 Script Quality

- **Standard**: Scripts solve problems rather than punt to Claude. Error
  handling is explicit and helpful. No "voodoo constants" — all magic numbers
  have comments justifying their value.
- **Check**: Scripts include error handling, documented constants, and clear
  output messages.
- **Source**: Anthropic best practices — "Solve, don't punt."

### 6.4 No Windows-Style Paths

- **Standard**: All file paths use forward slashes, even on Windows.
  `reference/guide.md` not `reference\guide.md`.
- **Measurement**: Scan all files for backslash path separators.
- **Source**: Anthropic best practices — "Unix-style paths work across
  all platforms."

### 6.5 Package Dependencies Listed

- **Standard**: If the skill references external packages or libraries,
  list required packages explicitly with install instructions.
- **Check**: Scan for library imports or tool usage without corresponding
  install instructions.
- **Source**: Anthropic best practices — "Don't assume packages are available."

### 6.6 Execute vs Read Intent

- **Standard**: When referencing scripts, make clear whether Claude should
  execute the script or read it as reference.
- **Violation**: Ambiguous references like "see analyze_form.py" (should it
  run it or read it?).
- **Fix**: "Run `analyze_form.py` to extract fields" (execute) vs
  "See `analyze_form.py` for the extraction algorithm" (read).
- **Source**: Anthropic best practices and engineering blog.

### 6.7 Feedback Loops for Critical Tasks

- **Standard**: Quality-critical tasks include validation/verification steps
  with a run-fix-repeat pattern.
- **Check**: If the skill includes operations that can fail silently
  (file generation, data transformation), is there a validation step?
- **Source**: Anthropic best practices — "Implement feedback loops."

### 6.8 Delta Model Routing

For each operation described as an instruction in the SKILL.md process section:

- **Standard**: Operations that produce identical output on the same input
  (grep, file count, version detection, template generation) belong in
  `scripts/`, not as LLM instructions. Operations requiring reading and
  interpreting source code or prose belong in LLM phases. Multiple distinct
  judgment types (classify vs score vs synthesize) warrant separate phases
  with explicit model assignments.

- **Check method**: For each "instruction block" in the process section:
  1. Same input → same output always? → script candidate
  2. Requires reading and interpreting source/prose? → LLM phase candidate
  3. Two or more distinct judgment types present? → phase separation candidate

- **Violation signal**: 3+ sequential process steps all described as LLM
  instructions with no script counterpart — indicates no delta analysis was
  performed. The reading/damping conjugacy: scripts define valid LLM input;
  LLM phases determine which script findings are valid. The interface between
  them (temp file format) is the architecture.

- **Source**: Derived from delta analysis — the gap between training pull
  (use LLM for everything) and evidence (deterministic operations are
  zero-cost, zero-hallucination, cacheable).

## 7. Structural Coherence

### 7.1 Single Responsibility

<judgment id="7.1">

- **Standard**: The skill does one thing. If the description needs "and" to
  explain what it does, consider splitting.

**Hypothesis PASS**: The skill's activities are stages in ONE workflow with a
single output. The "and" connects pipeline stages, not independent activities.
Removing any stage would break the output.

**Hypothesis FAIL**: The skill performs independent activities that could be
invoked separately. The "and" connects things that don't need each other.
Each could produce a useful output alone.

**Test**: Remove one of the activities. Does the remaining skill still produce
a useful output? If yes — independent activities, consider splitting (FAIL).
If no — pipeline stages, single responsibility (PASS).

**Example PASS**: "Inspects AND diagnoses AND identifies leverage" — three
stages of one audit pipeline. Removing diagnose breaks leverage.
**Example FAIL**: "Deploys AND monitors AND sends alerts" — three independent
activities. Monitoring works without deploying.

</judgment>

- **Check**: Count distinct activities in the process section.

### 7.2 Process Completeness

- **Standard**: The process section covers the full workflow from input to
  output. No implicit steps.
- **Check**: Can Claude execute this skill from step 1 through the final
  step without needing to infer missing steps?

### 7.3 Clear Output Contract

- **Standard**: The skill specifies what it produces (output format, file
  locations, report structure).
- **Check**: Is there an explicit output format or template?

### 7.4 Fork Intent Match

- **Standard**: If the skill uses `context: fork`, the SKILL.md content must
  contain an explicit task (not just guidelines). A forked subagent receives
  the skill content as its prompt — guidelines without a task produce no
  meaningful output.
- **Check**: If `context: fork` is set, does the body contain actionable
  steps (not just reference content)?
- **Source**: Claude Code docs — "`context: fork` only makes sense for skills
  with explicit instructions."

### 7.5 Concrete Examples

- **Standard**: Examples are concrete with specific input/output pairs, not
  abstract descriptions. "Generate commit messages following these examples:
  [input] → [output]" is better than "write good commit messages."
- **Check**: If the skill includes examples, are they input/output pairs
  or just descriptions?
- **Source**: Anthropic best practices — "Examples help Claude understand the
  desired style and level of detail more clearly than descriptions alone."

### 7.6 MCP Tool References

- **Standard**: If the skill references MCP tools, use fully qualified names
  in `ServerName:tool_name` format.
- **Violation**: Bare tool names like "use the bigquery_schema tool" instead
  of "use the BigQuery:bigquery_schema tool."
- **Check**: Scan for MCP tool references without server prefix.
- **Source**: Anthropic best practices — "Without the server prefix, Claude
  may fail to locate the tool."

### 7.7 Multi-Model Pipeline Appropriateness

<judgment id="7.7">

- **Standard**: A skill with 3 or more sequential LLM-dependent operations
  that have distinct precision requirements (e.g., filter/classify → score →
  synthesize) should use a multi-model phase architecture with explicit model
  assignments, rather than a single undifferentiated LLM instruction block.

**Hypothesis PASS**: Either (a) the skill has 3+ sequential LLM operations
AND has separate `phases/` files with model assignments, OR (b) the skill
has fewer than 3 sequential LLM operations and a single-model approach is
justified.

**Hypothesis FAIL**: The skill has 3+ sequential LLM operations with
distinct precision requirements (one step filters, another scores, another
synthesizes) but all are described as one undifferentiated instruction to
"Claude" with no model routing. This wastes Opus on classification and
blocks Haiku from cheap filtering.

**Test**:
1. Count sequential LLM-dependent steps in the process section.
2. Do the steps have different precision requirements? (filtering ≠ scoring ≠ synthesis)
3. Do `phases/` files exist with explicit model assignments?
If step count ≥ 3 AND steps differ in precision AND no phases/ → FAIL.

**Example PASS**: Skill with phases/scan.md (Haiku), phases/classify.md
(Sonnet), phases/leverage.md (Opus) — distinct operations, explicit routing.
**Example FAIL**: Skill with a 5-step process all described as "analyze X,
then Y, then Z" with no model routing, no phase files, all delegated to
whatever model the orchestrator runs on.

</judgment>

- **Source**: Multi-model pipeline pattern — model should match operation
  precision. Haiku for classification/filtering, Sonnet for scoring/analysis,
  Opus for synthesis/architectural judgment.

## 8. Block Architecture Conformance

> **Conditional section** — applies only when auditing agent-skills. Detected
> by presence of `tools.ts` or a `blocks/` directory containing `.ts` files.
> If neither is found, mark all items N/A.

### 8.1 Factory Pattern

- **Standard**: Blocks export `create*Block()` factory functions, not
  singleton `const xxxBlock = tool({...})` exports.
- **Exception**: Pure function blocks (no LanguageModel) may be singletons.
- **Check**: Scan `blocks/*.ts` for `export const.*= tool\(` without a
  corresponding factory function.

### 8.2 BlockOutput\<T\>

- **Standard**: All block outputs use the discriminated union:
  `{ output: T; error: null; telemetry } | { output: null; error: string; telemetry }`
- **Check**: `types.ts` defines `BlockOutput<T>`. Block return types reference
  it or match its shape.

### 8.3 BlockTelemetry

- **Standard**: `durationMs` is required on every block output. Token and cost
  fields are optional.
- **Check**: Every block's return path includes `telemetry: { durationMs: ... }`.

### 8.4 BlockRegistry

- **Standard**: Registry is typed as `Record<string, ReturnType<typeof tool>>`
  or equivalent. Not a custom named interface with hardcoded block types.
- **Check**: `tools.ts` or `types.ts` defines `BlockRegistry` matching the
  canonical type.

### 8.5 Provider Agnosticism

- **Standard**: No direct provider imports (`@anthropic-ai/sdk`, `openai`,
  `google-generative-ai`) inside blocks. `LanguageModel` closed over via
  factory, not imported from a specific provider.
- **Check**: Scan `blocks/*.ts` for direct provider SDK imports.

### 8.6 Dotted Naming

- **Standard**: Registry keys follow `domain.blockName` convention.
  Example: `"content.plan"`, `"content.write"`, `"content.validate"`.
- **Check**: Scan `tools.ts` for registry key strings. Keys should contain
  a dot separator.

### 8.7 Context Through Closure

- **Standard**: Shared context (`AgentContext`, `LanguageModel`) flows through
  factory closure, not through Zod parameter schemas.
- **Violation**: `context: AgentContextSchema` or `model: LanguageModelSchema`
  in a block's `z.object()` parameters.
- **Check**: Scan `blocks/*.ts` for `AgentContext` or `LanguageModel` in
  `z.object()` parameter definitions.

### 8.8 Failure Through Output Types

- **Standard**: Blocks report errors via the `error` field in `BlockOutput<T>`,
  not by throwing unstructured exceptions. Resilience patterns live in the
  orchestrator, not in blocks.
- **Check**: Scan `blocks/*.ts` for `throw new Error` outside of truly
  exceptional scenarios (programmer errors, assertion failures).

### 8.9 Tool Call Fallback Telemetry

- **Standard**: Blocks that use structured tool calls (via Vercel AI SDK
  `tool()` or similar) to extract model output emit a `tool_call_fallback`
  warning when the model produces prose instead of calling the expected tool.
  The fallback path itself is legitimate — the warning exists so silent
  degradation is observable, not to prevent it.
- **Check**: Scan `blocks/*.ts` for tool call extraction patterns. Where a
  block defines an inner `tool()` and checks whether the model called it, a
  missing call should log:
  `ctx.logger.warn("tool_call_fallback", { block, tool, textLength })`.

### 8.10 Provider Capability Normalization

- **Standard**: Blocks do not hardcode provider-specific values: no inline
  `MODEL_PRICING` tables indexed by modelId strings, no hardcoded
  `maxOutputTokens` calibrated for one provider, no `temperature` values
  that assume specific provider semantics. Provider capabilities are
  normalized in the adapter layer before reaching blocks.
- **Check**: Scan `blocks/*.ts` for pricing lookups by modelId string,
  hardcoded `maxOutputTokens` values, and `model.modelId` string usage
  beyond telemetry recording. These are provider leak indicators.

## 9. Eval Coverage

> **Conditional section** — applies when the skill produces verifiable output.
> Signals: presence of `evals/evals.json`, OR skill description contains
> "verify", "check", "audit", "test", "validate", "generate", "detect".
> If neither signal is present, mark all items N/A.

### 9.1 Main Path Coverage

- **Standard**: At least one eval testing the happy path — the skill works
  as intended on a representative, well-formed input.
- **Measurement**: Phase 1 directory listing for `evals/` + evals.json prompt
  content.

### 9.2 Gate / Negative Coverage

- **Standard**: At least one eval testing the short-circuit or negative case.
  If the skill has a gate ("no SDK found → skip", "no files → report empty"),
  there should be an eval that fires that gate and verifies short-circuit behavior.
- **Check**: Scan expected_output fields for "no X detected", "nothing found",
  "short-circuit", or equivalent gate-firing expectations.

### 9.3 Fixture Quality

- **Standard**: Evals that reference a file path in the prompt must have a
  corresponding fixture in `evals/fixtures/`. Prompts pointing at imaginary
  paths with no fixture are untestable.
- **Check**: For each eval prompt containing a file path or directory reference,
  verify that path exists on disk relative to the skill directory.

### 9.4 Expected Output Specificity

- **Standard**: `expected_output` fields are concrete — they name specific
  fields, values, dimension statuses, or behaviors that can be verified.
  Generic expectations ("a good report", "correct output") are not verifiable.
- **Check**: Each expected_output should contain at least one falsifiable claim.
  "Dimension 1 = missing" is falsifiable. "The report looks complete" is not.

### 9.5 Edge Case / Nuance Coverage

- **Standard**: At least one eval covering a nuance, false-positive scenario,
  or ambiguity that a naive implementation would get wrong.
- **Check**: Does the eval set include a case that tests the skill's judgment,
  not just its happy path? E.g., a valid pattern that looks like an anti-pattern,
  a partial migration, a provider-specific edge case.

## 10. Claude 4 Compliance

> **Applies to all skills.** Claude 4 (Sonnet 4.6, Opus 4.6) requires more
> explicit direction than Claude 3.x. Instructions that worked by implication
> or soft suggestion in Claude 3 will be ignored or weakly executed in Claude 4.
> Full reference: [`best-practices-claude4.md`](best-practices-claude4.md)

### 10.1 Imperative Phrasing

- **Standard**: >90% of instructions in the body are verb-first commands.
  Not descriptions of what the skill does or what Claude might do.
- **Violation signals**: Phrases beginning with "helps", "allows", "enables",
  "can", "may", "might", "could", "considers", "explores". Sentences starting
  with "You might want to...", "Feel free to...", "You could...".
- **Measurement**: Phase 1 voice consistency scan — flag any instruction block
  that describes capability instead of directing action.
- **Source**: Anthropic migration guide — "Claude 4+ models require more
  explicit direction in prompts."
- **Source**: [best-practices-claude4.md §1](best-practices-claude4.md)

### 10.2 No Soft Qualifiers

- **Standard**: No hedging language in directive statements.
  "Run validation." not "Consider running validation if needed."
- **Violation signals**: "if needed", "as appropriate", "when relevant",
  "you might want", "feel free", "consider", "optionally".
- **Exception**: Conditional instructions are fine when the condition is
  precise: "If no SKILL.md is found, report immediately and stop."
- **Check**: Scan body for soft qualifier phrases. Each is a potential
  Claude 4 compliance failure.
- **Source**: [best-practices-claude4.md §1](best-practices-claude4.md)

### 10.3 Critical Instructions First

- **Standard**: The core action (what Claude must do) appears in the
  first 100 words of the body. Context, background, and rationale follow.
- **Violation**: First 200+ words are context/background with the actual
  directive buried after a heading.
- **Rationale**: Claude 4 is sensitive to ordering — later instructions
  carry less weight, especially when the model has already formed an approach.
- **Check**: Read only the first paragraph. Does it tell Claude what to DO,
  or what the skill IS?
- **Source**: [best-practices-claude4.md §3](best-practices-claude4.md)

### 10.4 XML Section Separation

- **Standard**: Skills with bodies >300 words and 3+ distinct instruction
  types (what to do, constraints, output format) wrap each section in named
  XML tags: `<instructions>`, `<constraints>`, `<output_format>`, `<examples>`.
- **Violation**: One flat block mixing directives, constraints, format
  requirements, and examples with no structural separation.
- **Exception**: Short, single-purpose bodies (<300 words with 1-2 instruction
  types) do not require XML tags.
- **Source**: Anthropic prompting docs — XML tags prevent Claude from mixing
  up instructions with examples or context.
- **Source**: [best-practices-claude4.md §2](best-practices-claude4.md)

### 10.5 Positive Framing

- **Standard**: Desired behavior is stated positively. Negative constraints
  sharpen the boundary but are not the primary directive.
- **Violation**: "Don't ask for clarification before starting" with no
  positive equivalent.
- **Fix**: "Start immediately with available information. Ask questions only
  after producing the first output."
- **Check**: For each negative instruction ("don't", "never", "avoid"),
  verify there is a corresponding positive statement of the desired behavior.
- **Source**: [best-practices-claude4.md §4](best-practices-claude4.md)

### 10.6 Rationale on Non-Obvious Instructions

<judgment id="10.6">

- **Standard**: Instructions that conflict with model defaults or feel
  arbitrary are paired with a one-sentence rationale.

**Hypothesis PASS**: The instruction is either (a) self-evidently necessary
(no rationale needed: "Read the file before editing it") or (b) non-obvious
but paired with a rationale explaining why it matters.

**Hypothesis FAIL**: A non-obvious instruction with no rationale — "Run all
three phases before reporting" with no explanation. Claude 4 may treat this
as optional scaffolding and skip phases when they feel redundant.

**Test**: Would a capable engineer, seeing this instruction for the first
time, understand why it exists without additional context? If no — rationale
is needed.

**Example PASS**: "Run all three phases before reporting. (Haiku misses what
Opus catches — the pipeline exists because sequential models see different gaps.)"
**Example FAIL**: "Run all three phases before reporting." — bare imperative
with no rationale for why skipping phases is wrong.

</judgment>
- **Source**: [best-practices-claude4.md §5](best-practices-claude4.md)

### 10.7 Description Routing Quality

- **Standard**: The description functions as a routing signal, not a
  marketing summary. Contains 3+ specific trigger phrases matching what
  users actually type, plus disambiguation against adjacent skills.
- **Violation**: Generic trigger phrases that overlap with adjacent skills.
  No negative disambiguation ("NOT for X — use Y skill instead").
- **Claude 4 note**: Claude 4 matches descriptions more literally than
  Claude 3. Vague descriptions that relied on fuzzy matching will misroute.
- **Check**: For each trigger phrase in the description, ask: would a user
  type exactly this? Is it distinct from all other skill descriptions?
  See section 2 for full description quality checklist.
- **Source**: [best-practices-claude4.md §6](best-practices-claude4.md)

### 10.8 No Description XML Tags

- **Standard**: The `description` frontmatter field contains no XML tags.
- **Violation**: `<tags>`, `<triggers>`, or any XML markup in the description.
- **Rationale**: XML tags in the description field interfere with system
  prompt injection — the description is injected raw into Claude's context.
- **Source**: Anthropic Skills spec — "Maximum 1024 characters, no XML tags."
- **Source**: [best-practices-claude4.md §2](best-practices-claude4.md)
