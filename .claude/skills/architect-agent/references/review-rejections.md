# Review Mode — Rejection Framework

Hard boundaries for EXTRACT → SCAFFOLD → VALIDATE when reviewing existing
code. Violations of these rules mean the review has reverted to training
patterns.

**NOTE:** The relevant rules are ALSO inlined into each block's agent .md
file as FAIL CONDITIONS. This file is the canonical reference. The inlined
copies ensure rules cannot be skipped by not loading this file.

---

## R-1: Do NOT equate partition correctness with architecture correctness

The partition passing means concerns are grouped correctly. It says NOTHING
about:
- Whether all concerns were extracted (monolithic functions hide concerns)
- Whether SDK features are used (Output.*, TelemetryIntegration,
  callOptionsSchema)
- Whether code is readable (biomeConfig compliance)
- Whether code is duplicated
- Whether data is separated from logic

If your review says "the partition is correct" and then lists fewer than 15
findings, you short-circuited. Go back to EXTRACT.

## R-2: Do NOT count only `tool({` definitions as concerns

A `tool({` is a CONTAINER for a concern, not the concern itself. EXTRACT must
also decompose:

- **Functions >50 lines** — each likely contains 2+ hidden concerns
- **Files >300 lines** — each likely contains mixed concern types
- **Functions defined inside other functions** — hidden concerns
- **Switch statements >5 cases on different data** — each branch may be a
  concern
- **For-loops doing different work per step** — pipeline steps hiding as
  iterations

If your EXTRACT output has ≤ the number of `tool({` definitions, you
under-extracted.

## R-3: Do NOT produce a bullet-list code review

The SCAFFOLD section MUST contain the full JSON format from
`scaffold-format.md`:

- `inputSchemaCode` with literal Valibot for every tool
- `outputSchemaCode` for every tool
- `outputStrategy` from the deterministic mapping for every concern
- `prepareStepCode` / `prepareCallCode` / `callOptionsSchemaCode` for every
  agent
- `telemetryIntegrationCode` for the shared telemetry class

If your output contains "should add callOptionsSchema" without showing the
actual `valibotSchema(v.object({...}))` code, you reverted to training.
Training produces recommendations. The skill produces code.

## R-4: Do NOT treat biomeConfig as aspirational

biomeConfig thresholds (`maxLinesPerFile: 300`, `maxLinesPerFunction: 50`,
`maxCognitiveComplexity: 15`) are not style preferences. They are
architectural indicators:

- A function exceeding 50 lines contains **hidden concerns that EXTRACT
  missed**
- A file exceeding 300 lines contains **mixed concern types that need
  decomposition**
- Cognitive complexity >15 means **the function is doing multiple jobs**

biomeConfig violations are **HIGH severity** in VALIDATE. They indicate
incomplete extraction, not messy formatting. List every file and every
function that exceeds thresholds with exact line counts.

## R-5: Do NOT classify Valibot as optional

The scaffolder generates Valibot exclusively. `inputSchemaCode` is literal
Valibot. `callOptionsSchemaCode` is literal Valibot wrapped in
`valibotSchema()`.

Zod usage in reviewed code is an **I-2 violation**, not a "convention
preference" or "optional migration." The scaffold output MUST show the
Valibot replacement for every schema. If your scaffold still references
`z.object()`, you haven't applied the skill.

## R-6: Do NOT skip the Output mapping

Before generating the scaffold, apply `resolveOutputStrategy()` from
`output-mapping.md` to **every** concern:

| Concern Type | Output Variant |
|-------------|---------------|
| validator | Output.object (pass/fail schema) |
| policy | Output.choice |
| query (array) | Output.array |
| query (single) | Output.object |
| transformer | Output.object |
| command | Output.text |
| domain | Output.object or Output.text |
| adapter_in | Output.object |
| adapter_out | Output.text |
| event | Output.text |

If your scaffold doesn't show this mapping applied to every concern, you
skipped a mandatory step. If zero agents in the existing code use any
`Output.*` variant, that is a **HIGH** violation — not a footnote.

## R-7: Do NOT skip custom Output detection

Run all 5 detection rules from `scaffolder.md` Step 1b on every concern:

1. outputFields include `code`, `schema`, or `template` type → custom parser
2. Transformer with variable output format → fallback chain
3. Returns structured data + prose → composite parser
4. Generates domain-specific syntax (SQL, regex, Three.js, Valibot) → domain
   parser
5. Output requires external validation → async validator

If a concern triggers any rule and the code doesn't use custom Output, flag
`missing_custom_output`. If your review doesn't mention custom Output
detection at all, you skipped a mandatory step.

## R-8: Do NOT ignore duplicated code

Scan for:
- **Same function name in multiple files** (e.g., `safeReadJson` in 2 files)
- **Same inline utility defined multiple times** (e.g., `lerp` defined twice)
- **Same validation logic in two places** (e.g., domain validation in both
  tool and Output)
- **Same data constant in multiple files**

Each duplication is a concern extraction failure — shared logic that should
be a single module-level utility. Count total duplicated lines. >50 lines =
MEDIUM. >200 lines = HIGH.

## R-9: Do NOT treat data constants as code

Large data constants (>10 entries in a `Record<>`, `Array<>`, or object
literal) mixed with logic functions are not code — they are configuration:

- Inflate file line counts past biomeConfig limits
- Obscure the actual logic they're mixed with
- Cannot be reused without importing the entire file
- Cannot be edited by non-engineers (product, content)

The scaffold should specify these as separate data files (`.json`) loaded at
runtime. Count total lines of data-in-code. >100 lines = MEDIUM.

## R-10: Do NOT downgrade missing middleware/telemetry

For agents using open-source models:
- Missing `extractReasoningMiddleware` is **HIGH** — `<think>` blocks corrupt
  tool call JSON parsing. Production failure mode.
- Missing `experimental_repairToolCall` is **HIGH** — malformed tool calls =
  hard failures.
- Missing `TelemetryIntegration` is **HIGH** — zero observability into
  tool-level lifecycle.
- Missing `devToolsIntegration` is **MEDIUM** — no development-time
  observability.

Training wants to call these "recommended improvements." They are not. For
open-source model agents, they are load-bearing infrastructure.

## R-11: Do NOT let training produce the output format

Training has seen millions of code reviews. They follow this pattern:
1. List issues as bullets
2. Rate severity
3. Suggest fixes in prose
4. Say "overall the code is reasonable"

The architect-agent skill produces a DIFFERENT output:
1. EXTRACT: typed JSON with concerns, sharedTypes, sdkViolations
2. COUPLE: coupling matrix with numeric H scores
3. PARTITION: agent tree with concern assignments
4. SCAFFOLD: full JSON with literal code for every field
5. VALIDATE: deterministic checks → coherence score → pass/fail

If your output looks like a code review with bullet points, you have produced
training output, not skill output.

## Self-Check — Run Before Finalizing

Answer every question. If ANY answer is "no", the review is incomplete:

1. Did EXTRACT find more concerns than there are `tool({` definitions?
2. Does the biomeConfig section list EVERY file >300 lines and EVERY function
   >50 lines with exact counts?
3. Does the SCAFFOLD contain literal Valibot code (not Zod) for every schema?
4. Was `resolveOutputStrategy()` applied to every concern?
5. Were all 5 custom Output detection rules run?
6. Is total duplicated code counted (a number)?
7. Is total data-in-code counted (a number)?
8. Does every agent in the scaffold have callOptionsSchemaCode,
   prepareCallCode, and telemetryIntegrationCode?
9. Is the coherence score computed with biomeConfig violations counted as
   HIGH?
10. Does the remediation plan show the target file structure with line counts
    ≤300 per file?
