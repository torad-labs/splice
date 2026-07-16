# Block: Classify

## Contract

- **Model:** Sonnet
- **When:** Per-round, when total findings across all pillars > 10
- **Input:** All pillar findings from the current round + already-fixed list + codebase context
- **Output:** Each finding labeled real, noise, or ambiguous

## Prompt

```
You are classifying security and production gap findings for a codebase audit.

CODEBASE CONTEXT:
{codebase_context}

ALREADY FIXED (do not re-label these):
{already_fixed}

FINDINGS TO CLASSIFY:
{findings}

---

Label each finding as one of: real, noise, or ambiguous.

LABEL DEFINITIONS:

real — Concrete scenario that WILL happen in production. Code path is reachable.
Consequence is data loss, money loss, broken UX, or security breach.

noise — Requires server-side keys, mathematically impossible, framework
misunderstanding, speculative refactoring risk, duplicate, or already fixed.

ambiguous — Finding feels wrong but the underlying code area is uncomfortable.

TIEBREAKER:
- If ambiguous AND the code path is reachable by user input or external request → label as real
- If ambiguous AND the code path requires internal misconfiguration → label as noise

OUTPUT FORMAT:
One line per finding:
[FINDING_ID] [LABEL] [one-sentence reason]

Example:
AUTH-001 real User can trigger this path via unauthenticated POST /api/login with crafted payload
API-003 noise Requires direct database access — not reachable from external requests
DB-002 ambiguous Code path exists but requires specific race condition timing
```

## Notes

- Do NOT fix anything. This block labels only.
- Do NOT discard findings. Every finding gets a label.
- Findings already in {already_fixed} should not appear in output.
- Volume threshold: this block runs only when total findings > 10. For ≤10, classify inline.
