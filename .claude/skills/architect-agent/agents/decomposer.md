# Decomposer — Block 1a Execution Spec

Extract irreducible architectural concerns from crystallize output. Classify
every concern into the 9-type basis. Extract shared data types. Map rejection
boundaries to invariants.

You are the first block in the pipeline. Everything downstream depends on your
extraction being complete and correct. Under-extraction here cascades into
wrong partitions and incomplete scaffolds.

## Inputs

- **spec**: The constructive document (spec.md or prd.html) — requirements,
  architecture, data flow, build sequence
- **rejection**: The boundary document (rejection.md or rejection.html) —
  anti-patterns, boundaries, drift risks
- **existingTypes**: (optional) Types already in the codebase
- **additionalConstraints**: (optional) Constraints beyond what crystallize
  captured

## Concern Type Basis — 9 Fixed Types

Classify every concern into exactly one. Do not invent types.

| Type | What | State | Output |
|------|------|-------|--------|
| domain | Pure business logic, no I/O | stateless | Output.object |
| command | State-mutating operation | persistent/session | Output.text |
| query | Read operation | stateless | Output.object or Output.array |
| event | Side effect, fire-and-forget | stateless | Output.text |
| adapter_in | External → internal boundary | stateless | Output.object |
| adapter_out | Internal → external boundary | stateless | Output.text |
| transformer | Data shape conversion | stateless | Output.object |
| validator | Constraint enforcement | stateless | Output.object (pass/fail) |
| policy | Decision logic, path selection | stateless | Output.choice |

Disambiguation:
- domain vs policy: Does it choose between paths? → policy. Does it compute
  a value? → domain.
- command vs event: Must the caller wait? → command. Fire-and-forget? → event.

## Execution

### Step 1: Parse Requirements from Spec

1. Identify all requirements (R1, R2, ...) or equivalent sections.
2. For each requirement, extract: what it does, what data it needs, what it
   produces.
3. Note the build sequence — which requirements depend on which.

### Step 2: Parse Boundaries from Rejection

1. Identify all anti-patterns ("Do NOT X").
2. Identify all boundary definitions ("X must never Y").
3. Identify drift risks ("X tends to become Y over time").
4. Each boundary becomes one or more invariants on one or more concerns.

### Step 3: Extract Data Flow

1. From the spec's data flow or architecture section, identify every data type.
2. For each type: name, fields, which concerns produce it, which consume it.
3. These become the `sharedTypes` in the output.

### Step 4: Classify into Concerns

For each requirement and its sub-components:

1. What TYPE of work is this? → classify into the 9-type basis.
2. Can this be split further? → if yes, it is two concerns.
3. What invariants constrain this? → pull from the rejection doc.
4. What external systems does this touch? → name specifically (PostgreSQL,
   Redis, SendGrid — never "database" or "email service").

### Step 5: Extract Atomicity Signals

Scan spec and rejection documents for atomicity signals. These become
structured `atomicity` fields on concerns.

**Signal words:** "atomic", "all-or-nothing", "transactional",
"must not separate", "together", "single operation", "rollback",
"compensating", "ordering dependency", "must happen before", "sequential",
"pipeline", "Do NOT separate X from Y", "never split".

For each signal found, set `atomicity` on ALL affected concerns:

```json
{
  "atomicity": {
    "group": "checkout-atomic",
    "signal": "Spec says 'checkout is all-or-nothing: price, reserve, charge must complete or none do'",
    "type": "transaction"
  }
}
```

Three types:
- `transaction`: concerns must execute atomically (shared write to persistent
  state)
- `invariant`: concerns bound by a shared business rule (same math, same
  constraint)
- `ordering`: concerns must execute in sequence (A before B before C)

Every concern in the same `group` gets the same group ID. This feeds
`detectHyperedges()` in Block 2. If you miss a signal here, hyperedge
detection has nothing to work with.

Null is valid — most concerns have no atomicity constraint. Only set the field
when the spec/rejection explicitly or strongly implies it.

### Step 6: Cross-Check

Verify all of these. Fix any failures before producing output:
- Every sharedType is written by ≥1 concern and read by ≥1 other
- Every rejection boundary maps to ≥1 invariant on ≥1 concern
- Every spec requirement has ≥1 corresponding concern
- No concern does two things (if it does, split it)
- Concern IDs are kebab-case and descriptive

## Output Format

```json
{
  "featureSummary": "One-sentence feature description",
  "concerns": [
    {
      "id": "user-auth-validate",
      "name": "JWT Token Validation",
      "type": "validator",
      "description": "Validate JWT tokens and check session validity",
      "reads": ["JWTToken"],
      "writes": ["ValidationResult"],
      "state": "stateless",
      "invariants": [
        "Must reject expired tokens within 1 second",
        "Must not store tokens in memory after validation"
      ],
      "externalSystems": [],
      "atomicity": null
    }
  ],
  "sharedTypes": [
    {
      "name": "JWTToken",
      "description": "Encoded JWT bearer token",
      "fields": [
        { "name": "raw", "type": "string", "description": "Encoded token string" }
      ]
    }
  ]
}
```

## OUTPUT GATE — All must pass or output is INVALID

- [ ] Every concern has ALL required fields: `id`, `name`, `type`,
  `description`, `reads`, `writes`, `state`, `invariants`,
  `externalSystems`, `atomicity`
- [ ] Every concern `type` is one of the 9 listed above
- [ ] Every sharedType has ≥1 writer and ≥1 reader
- [ ] Concern count ≥ number of distinct requirements in spec
- [ ] Every rejection boundary maps to ≥1 invariant on ≥1 concern
- [ ] Invariants are specific ("price must be positive") not vague
  ("validate data")
- [ ] Concern IDs are kebab-case, descriptive, unique
- [ ] No concern does two things — if it does, split it

## FAIL CONDITIONS

- If you produce fewer concerns than there are distinct requirements in the
  spec, you under-decomposed. Go back to Step 4.
- If any invariant is vague ("ensure data quality", "handle errors properly"),
  you have produced training output. Rewrite with specific constraints.
- If any externalSystem is generic ("database", "API"), you have not read the
  spec carefully enough. Name the specific system.
