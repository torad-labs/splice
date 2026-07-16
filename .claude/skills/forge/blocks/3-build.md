# Block 3: Build (Sonnet × N)

Implement one component per invocation. Read and write files. Self-check
against acceptance criteria. Report observations.

## Input Contract

| Variable | Shape | Source | Required |
|----------|-------|--------|----------|
| `$BUILD_ITEM` | One BuildItem object: name, type, files, dependencies, pillar, description, acceptance_criteria | Plan output, one item extracted | yes |
| `$INTERFACE_CONTRACT` | This component's exports (name, shape, consumers), imports (name, shape, provider), data_flow_role | Plan INTERFACE_CONTRACTS, filtered to this component | yes |
| `$COMPONENT_CONSTRAINTS` | List of constraint IDs + verify check text for this component | Plan CONSTRAINT_MATRIX, filtered to this component | yes |
| `$COMPONENT_REJECTIONS` | List of rejection boundaries mapped to this component | Plan REJECTION_MAP, filtered to this component | yes |
| `$IMMUNE_MEMORY` | List of default → evidence-based pairs for this component | Plan IMMUNE_MEMORY_ACTIVE, filtered to this component | yes (may be empty) |
| `$PRIOR_OBSERVATIONS` | List of `{component, observation}` from prior Build outputs | Orchestrator state, accumulated across builds | no (empty for first build) |
| `$VERIFY_FAILURES` | List of FAIL findings with file paths and constraint IDs | Verify output from previous attempt (retry only) | no (only on retry) |

## Agent Prompt

```
You are a component builder. You receive one BuildItem from the build plan.
Your job is to implement this component by creating or modifying the specified
files, then verify your implementation against the acceptance criteria.

Implement only the assigned component. Do not modify files belonging to other
components. Do not implement functionality beyond what the BuildItem describes.

## BuildItem

$BUILD_ITEM

## Interface Contract

This component's boundary with the rest of the system. Exports must match
the declared shapes — other components depend on them. Imports may be assumed
to exist with the declared shapes when the dependency is listed as complete.

$INTERFACE_CONTRACT

## Active Constraints

These harden construction constraints apply to this component. Your
implementation must satisfy each one during construction, not after.

$COMPONENT_CONSTRAINTS

## Rejection Boundaries

These patterns must NOT appear in your implementation:

$COMPONENT_REJECTIONS

## Immune Memory (Active)

These are evidence-based overrides of common defaults. Follow the evidence-based
approach, not the default:

$IMMUNE_MEMORY

## Prior Build Observations

Observations reported by previous Build invocations that may affect this
component. Empty if this is the first component in the build sequence.

$PRIOR_OBSERVATIONS

## Verify Failures (Retry Only)

If this is a retry after verify failure, the specific failures to fix are
listed below. Address each failure. Do not re-implement the entire component —
fix only what Verify flagged.

$VERIFY_FAILURES

## Instructions

### Step 1: Read Context

Read the files listed in the BuildItem:
- For "modify" type: Read the existing file(s) to understand current code
- For "new" type: Read adjacent files to understand patterns, imports, and
  conventions used in the codebase
- Read package.json / tsconfig.json if present for configuration context

### Step 2: Implement

Create or modify files as specified. Follow existing codebase patterns for:
- Import style (ESM vs CJS, relative vs absolute paths)
- Naming conventions (camelCase, kebab-case, file naming)
- Error handling patterns already in use
- Type patterns (if TypeScript)

For each active constraint:
- Implement the constraint directly in the code
- Do not defer constraint satisfaction to a later step

### Step 3: Self-Check

After implementation, check each acceptance criterion:

For each criterion:
  - PASS: state the evidence (file, line, or code snippet)
  - FAIL: state what's missing and why

If any criterion is FAIL and you can fix it without exceeding scope, fix it
and re-check. If a FAIL requires changes to another component, report it as
an observation.

### Step 4: Report

## Output Format

COMPONENT: [name]
STATUS: [complete | partial]

FILES_WRITTEN:
  - [file path] — [created | modified] — [brief description of change]

SELF_CHECK:
  | Criterion | Result | Evidence |
  |-----------|--------|----------|

OBSERVATIONS: [anything discovered during implementation that affects other
components or the overall architecture — these feed into the next build
invocation or into the assembly phase]

INTERFACE_COMPLIANCE:
  | Export | Declared Shape | Actual Shape | Match |
  |--------|---------------|--------------|-------|

CONSTRAINT_SATISFACTION:
  | Constraint | Satisfied | How |
  |------------|-----------|-----|
```

## Boundary Spec

### Build → Verify

- **Type contract**: File paths from FILES_WRITTEN, plus the component's
  acceptance criteria and constraint list.
- **Tool schema**: COMPONENT name, FILES_WRITTEN list, SELF_CHECK table,
  CONSTRAINT_SATISFACTION table.
- **Forwarded context**: File paths and status. The actual implementation code
  stays in files — Verify reads them directly.
- **Implicit assumptions**: Verify assumes FILES_WRITTEN paths are accurate
  and the files exist. Verify assumes SELF_CHECK is honest but re-verifies
  independently.
- **Transformation**: Reshaping (implementation details → file list + status
  summary). The code itself is not forwarded — Verify reads files.

### Verify → Build (re-invoke on failure)

- **Type contract**: List of FAIL findings with file paths and constraint IDs.
- **Forwarded context**: Only the failures. PASS findings, verify reasoning,
  and evidence for passes are not forwarded.
- **Implicit assumptions**: Build assumes the failures are accurate and
  actionable. Build does not re-verify passes.
- **Transformation**: Lossy (full verification → only failures).

## Rejection Boundary

- Implement only the assigned component
- Do not modify files not listed in the BuildItem
- Do not add features, helpers, or abstractions beyond what the component requires
- Do not ignore active constraints — each must be satisfied in code
- Do not defer error handling to "a later step"
