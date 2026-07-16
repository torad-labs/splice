# Coupler — Block 2 Execution Spec

Score domain coupling between all significant pairs of architectural concerns.
Your scores directly determine which concerns get grouped into the same agent.

Score too high → everything merges into a monolith. Score too low → everything
fragments into tiny agents. Be precise — the difference between 0.3 and 0.5
can flip a partition boundary.

## Inputs

- **pairs**: Array of concern pairs, each with:
  - `pair`: "concernId_a:concernId_b"
  - `a_invariants`: Business rules for concern A
  - `b_invariants`: Business rules for concern B
  - `a_description`: What concern A does
  - `b_description`: What concern B does

## Scoring Scale — Use This Exactly

| Score | Meaning | Example |
|-------|---------|---------|
| 0.0 | Completely unrelated domains | Auth validation ↔ Email template rendering |
| 0.1-0.2 | Same system, different domains | User registration ↔ Order processing |
| 0.3-0.4 | Share some domain concepts | Session management ↔ Rate limiting (both reference user identity) |
| 0.5-0.6 | Moderate overlap | JWT validation ↔ OAuth token exchange (both handle auth tokens) |
| 0.7-0.8 | Deeply intertwined business logic | Order pricing ↔ Discount calculation (share pricing invariants) |
| 0.9-1.0 | Same business rule set | Should probably be the same concern |

## Execution

### Step 1: Read Each Pair

For each pair:
1. Read both descriptions — understand what each concern does.
2. Read both invariant sets — these are the business rules.

### Step 2: Score Domain Coupling

For each pair:
1. Check invariant overlap: Do any invariants reference the same business
   concept?
2. Check description overlap: Do the descriptions share domain vocabulary?
3. Check causal coupling: Does one concern's output semantically constrain
   the other?
4. Assign a score with a one-sentence reason.

### Step 3: Calibrate

After scoring all pairs:
1. Check: are >80% of pairs below 0.3? (this is the expected distribution)
2. Check: are only 10-20% above 0.5?
3. If everything scores high → you are over-coupling. Recalibrate.
4. If nothing scores above 0.2 → you are under-coupling. Look for shared
   domain concepts you missed.

State the calibration check in your output.

### Step 4: Detect Hyperedges

Identify multi-way constraints binding 3+ concerns that can NEVER be split
across agents. Four types:

| Type | Signal | Severity |
|------|--------|----------|
| shared_transaction | 3+ concerns sharing an atomic DB transaction | Highest — cutting = data corruption |
| shared_invariant | Business rule referencing 3+ concerns | High — cutting = invariant violation |
| shared_system | 3+ concerns touching the same external system | Medium — cutting = connection proliferation |
| shared_type_cluster | 3+ concerns sharing a complex type with >5 fields | Low — cutting = type duplication |

For each hyperedge, assign an ID (format: `he-{number}-{slug}`), list member
concerns, and state the rationale.

### Step 5: Verify Hyperedge Consistency

For each hyperedge with members A, B, C:
- Check: is pairwise score A↔B ≥ 0.5?
- Check: is pairwise score A↔C ≥ 0.5?
- Check: is pairwise score B↔C ≥ 0.5?

If any pairwise score contradicts the hyperedge (< 0.5 between members),
re-examine the pair. Either the score is wrong or the hyperedge is wrong.

If concerns share an `atomicity.group` field from the decomposer, their
pairwise scores MUST be ≥ 0.5. The atomicity field is the strongest signal.

## Output Format

```json
{
  "scores": [
    {
      "pair": "auth-validate:session-refresh",
      "score": 0.6,
      "reason": "Both enforce session validity invariants and reference JWT token lifecycle"
    }
  ],
  "calibration": {
    "totalPairs": 45,
    "below03": 36,
    "above05": 5,
    "percentBelow03": 80,
    "status": "within expected distribution"
  },
  "hyperedges": [
    {
      "id": "he-01-checkout",
      "type": "shared_transaction",
      "concerns": ["price-calc", "inventory-reserve", "payment-charge"],
      "rationale": "All three share an atomic checkout transaction — splitting corrupts order state"
    }
  ]
}
```

## OUTPUT GATE — All must pass or output is INVALID

- [ ] Scores table exists with numeric values (0.0–1.0) for all significant
  pairs
- [ ] ≥15 scored pairs for codebases with >10 concerns
- [ ] Every score has a one-sentence reason
- [ ] Calibration check stated: total pairs, % below 0.3, % above 0.5
- [ ] Hyperedges listed with: ID, type, member concern IDs, rationale
- [ ] No pairwise score < 0.5 between members of the same hyperedge
- [ ] Atomicity groups from Block 1 reflected in scores (≥ 0.5 for group
  members)

## FAIL CONDITIONS

- If all pairs score above 0.5 → you are over-coupling. Everything would
  merge into one agent. Recalibrate against the scoring scale.
- If zero pairs score above 0.3 → you are under-coupling. You missed shared
  domain concepts. Re-read the invariants.
- If your output contains no hyperedges for a codebase with >5 concerns,
  verify: are there really no multi-way constraints? This is rare. Check
  atomicity fields from Block 1.
- If you produce scores without reasons → training output. Every score must
  be justified.

## MANDATORY RULES

- Score invariants, not names. Two concerns with similar names but different
  rules → low coupling.
- Score business semantics, not data flow. Data flow is handled by the
  deterministic DataCoupling function.
- When in doubt, score lower. Over-coupling produces monoliths.
  Under-coupling is fixable.
- You do NOT create hyperedges from scratch — you detect them from the
  evidence (atomicity fields, shared invariants, shared systems). If the
  evidence isn't there, the hyperedge doesn't exist.
