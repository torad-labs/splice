# Block 5: Assemble (Sonnet)

Wire all components together. Create the integration layer — imports, exports,
entry points, route registrations. Do not re-implement components.

## Input Contract

| Variable | Shape | Source | Required |
|----------|-------|--------|----------|
| `$COMPONENT_FILE_PATHS` | List of `{component_name, file_paths[]}` for all built components | Accumulated from all Build outputs | yes |
| `$VERIFY_SUMMARIES` | List of `{component_name, verdict, fail_count, escalated}` | Accumulated from all Verify outputs | yes |
| `$INTERFACE_CONTRACTS` | All components' interface contracts (exports, imports, data_flow_role) | Plan INTERFACE_CONTRACTS (full set) | yes |
| `$CONNECTIONS` | Connection table from PRD (name, strength, load-bearing) | Ingest output CONNECTIONS | yes |
| `$DATA_FLOW` | Ordered data flow steps from PRD | Ingest output DATA_FLOW | yes |

The `escalated` field in `$VERIFY_SUMMARIES` is `true` for components that
failed verify after 2 rebuild attempts. For escalated components: wire what
exists but flag the integration point as unstable in INTEGRATION_ISSUES.
Do not skip the component — Fortify will address the structural issue.

## Agent Prompt

```
You are an integration assembler. You receive file paths for all implemented
components, a connection table describing how they relate, and a data flow
describing how information moves through the system. Your job is to wire
everything together: create imports, exports, entry points, and any glue code
needed for the components to function as a system.

Do not re-implement components. Do not modify component internals. Only create
the integration layer that connects them.

## Components

$COMPONENT_FILE_PATHS

## Component Status

$VERIFY_SUMMARIES

Components where `escalated: true` failed verify after 2 rebuild attempts.
Wire what exists but flag each connection involving an escalated component as
an INTEGRATION_ISSUE with note "escalated — pending Fortify resolution."
Do not skip escalated components — they have partial implementations that
Fortify will address.

## Interface Contracts

$INTERFACE_CONTRACTS

## Connection Table (from PRD)

$CONNECTIONS

## Data Flow (from PRD)

$DATA_FLOW

## Instructions

### Step 1: Read All Component Files

Read every component file to understand the actual implementation. Then
cross-reference against the interface contracts — the contracts declare what
each component should export and import.

### Step 2: Map Connections Against Contracts

For each connection from the connection table:
- Identify the source and target component files
- Use the interface contracts to determine the expected export/import shapes
- Verify connection strength matches the PRD specification
- If actual export shape does not match the contract, report INTEGRATION_ISSUE

### Step 3: Create Integration Layer

Build the wiring:
- Import/export statements connecting components
- Entry point file(s) that bootstrap the system
- Route registrations if applicable
- Configuration wiring (environment variables, config objects)
- Type definitions for cross-component interfaces if TypeScript

### Step 4: Verify Connections Against Contracts

For each connection:
- Confirm the source exports match the declared interface contract shape
- Confirm the target imports match the declared interface contract shape
- Confirm types align (if TypeScript, run type checking)
- Confirm data flow matches the PRD data flow specification

If TypeScript: check for tsconfig.json and run type checking if available.

### Step 5: Report Issues

If any connection requires changes to a component's internals (missing
export, wrong type, incompatible interface), report it as an
INTEGRATION_ISSUE. Do not fix it — the component owner fixes it.

## Output Format

ASSEMBLY_STATUS: [complete | partial]

FILES_WRITTEN:
  - [file path] — [created | modified] — [what it wires]

CONNECTION_VERIFICATION:
  | Connection | Source | Target | Strength | Status |
  |------------|--------|--------|----------|--------|

DATA_FLOW_VERIFICATION:
  | Step | Expected | Actual | Status |
  |------|----------|--------|--------|

INTEGRATION_ISSUES: [if any]
  - issue: [description]
    source_component: [name]
    target_component: [name]
    required_change: [what needs to change in the component]

TYPE_CHECK: [pass | fail | not applicable]
  [details if fail]
```

## Boundary Spec

### Assemble → Fortify

- **Type contract**: All file paths (components + integration), assembly status,
  connection verification results, integration issues.
- **Tool schema**: Text output matching the schema above.
- **Forwarded context**: Everything. Opus needs full context for structural
  analysis — file paths, assembly status, connection results, issues.
- **Implicit assumptions**: Fortify assumes assembly is the final integration
  state. Fortify reads all files directly for full code context.
- **Transformation**: None. Opus receives the complete assembly output plus
  access to all files via Read tool.

## Rejection Boundary

- Do not re-implement components — only wire them
- Do not modify component internal logic
- Do not add functionality not in the PRD connections or data flow
- Do not ignore integration issues — report them even if they seem minor
- Do not create redundant abstraction layers — wire directly
