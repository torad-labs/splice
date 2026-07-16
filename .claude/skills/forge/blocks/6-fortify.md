# Block 6: Fortify (Opus)

Read all built files. Classify into harden pillars. Identify ONE architectural
weakness and fix it, or confirm CLEAN. This is the constructive inverse of
harden's leverage phase — applied at construction time.

## Input Contract

| Variable | Shape | Source | Required |
|----------|-------|--------|----------|
| `$ALL_FILE_PATHS` | List of all file paths (components + integration layer) | Accumulated from Build + Assemble outputs | yes |
| `$INGEST_OUTPUT` | Full structured extraction from Ingest | Block 1 output (same as forwarded to Plan) | yes |
| `$ALL_VERIFY_RESULTS` | List of `{component_name, verdict, fail_count, findings[]}` | Accumulated from all Verify outputs (full detail, not summaries) | yes |
| `$ASSEMBLY_OUTPUT` | Full Assemble output (status, connection verification, integration issues) | Block 5 output | yes |

## Agent Prompt

```
You receive the complete output of a forge pipeline: all component files,
integration files, the original product specification, and all verify results.
Your job is to hold everything in tension and find the ONE structural weakness
that harden would catch — then fix it preemptively.

You are not looking for bugs. You are looking for the architectural pattern
that would generate the most bugs over time. The same cognitive shape as
harden's leverage phase, but constructive — you fix what you find.

## All Built Files

$ALL_FILE_PATHS

Read every file using the Read tool.

## Original Product (Ingest Output)

$INGEST_OUTPUT

## Verify Results (All Components)

$ALL_VERIFY_RESULTS

## Assembly Result

$ASSEMBLY_OUTPUT

## Instructions

### Step 1: Read Everything

Read all built files. Read the ingest output. Read the verify results. Hold
all of it in working memory before analyzing.

### Step 2: Classify into Harden Pillars

Group all implemented code into harden pillars:
- Payment/Checkout
- Access/Auth
- Data Integrity
- Content Delivery
- Email/External
- (other pillars as appropriate for this codebase)

### Step 3: Check Pillar-Specific Concerns

For each pillar, check its harden concerns (from references/harden-concerns.md):

| Pillar | Check For |
|--------|-----------|
| Payment/Checkout | Race conditions, data loss, money loss, idempotency |
| Access/Auth | Auth bypass, token exposure, timing issues |
| Data Integrity | Transaction boundaries, orphaned records, upsert races |
| Content Delivery | Routing edge cases, content-type, CORS, sanitization |
| Email/External | Deliverability, data leaks, error handling |

### Step 4: Find the Pattern

Look across all components and pillars for:
- What structural choice forced the most constraint-satisfaction complexity?
- Is there a shared assumption that, if wrong, breaks multiple components?
- Do the verify FAIL patterns (if any) share a root cause?
- Is there a pattern in how constraints were satisfied that's fragile?

### Step 5: Act

**If a weakness exists:**
- Identify it precisely
- Fix it — modify the actual files
- Explain what harden would have found and why this fix prevents it

**If no weakness exists:**
- Confirm CLEAN with evidence
- State which pillars were checked and why no structural weakness was found

Limit: ONE weakness maximum. If multiple candidates exist, choose the one
that prevents the largest class of future bugs.

## Output Format

### If weakness found:

FORTIFY_RESULT: WEAKNESS_FOUND

WEAKNESS: [precise description of the structural pattern]
PILLAR: [which pillar it primarily affects]
HARDEN_WOULD_FIND: [what harden's Haiku agents would flag]
ROOT_CAUSE: [the architectural choice that created this]

FIX_APPLIED:
  - file: [path]
    change: [description of what was changed]

EVIDENCE: [which components, connections, or verify results trace to this weakness]

REMAINING_RISK: [what this fix does NOT address, if anything]

### If clean:

FORTIFY_RESULT: CLEAN

PILLARS_CHECKED: [list with component counts per pillar]
EVIDENCE: [why no structural weakness exists — not "I didn't find anything"
but "the architecture handles X because of Y"]

STRONGEST_AREA: [which aspect of the implementation is most robust]
WATCH_AREA: [which area is closest to having a structural weakness and
should be monitored as the codebase grows]
```

## Boundary Spec

### Fortify → Summary (orchestrator)

- **Type contract**: FORTIFY_RESULT (WEAKNESS_FOUND | CLEAN), plus either
  the weakness description and fix details, or the clean evidence.
- **Tool schema**: Text output matching one of the two output formats above.
- **Forwarded context**: Result type, weakness/fix summary or clean evidence.
  The detailed reasoning stays in the Opus output.
- **Implicit assumptions**: Orchestrator trusts Opus's structural judgment.
  If Opus changed files that Verify already passed, the Opus fix takes priority.
- **Transformation**: Lossy summarization (orchestrator extracts result type
  and key details for the forge report).

## Rejection Boundary

- Report ONE weakness maximum — not a list
- Do not re-verify individual constraints — that was Verify's job
- Do not undo component implementations — fix the structural pattern
- Do not add features or functionality — only fix the weakness
- If CLEAN, do not manufacture a weakness to seem thorough
