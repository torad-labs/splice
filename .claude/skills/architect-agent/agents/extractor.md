# Extractor — Block 1b Execution Spec

Read existing AI SDK agent code. Extract every architectural concern that is
ACTUALLY implemented — not what was intended, what is there. Reverse-decompose:
code → concerns.

You are replacing the DECOMPOSE block for review mode. Your output feeds
directly into COUPLE. Under-extraction here means the coupling matrix is
incomplete, the partition is wrong, and the scaffold misses concerns.

## Inputs

- **sourceFiles**: Array of `{ path, content }` — TypeScript source files
- **description**: (optional) What the agent is supposed to do

## Execution

### Step 1: Identify Tools

Scan ALL source files for `tool({` definitions. For each:

1. Extract tool name.
2. Extract `inputSchema` fields — these are the tool's reads.
3. Extract what `execute` does — these define the tool's writes.
4. Extract `description`.
5. Check: `inputSchema` or `parameters`? (`parameters` = I-2 violation)
6. Check: does it use `Output.*`? Which variant?
7. Check: `generateText` / `streamText` / `generateObject`?
   (`generateObject` = I-3 violation)

### Step 2: Identify Agents

Scan for `ToolLoopAgent`, `new Agent`, custom classes, `generateText` loops:

1. `ToolLoopAgent` → extract: tools, stopWhen, prepareStep, prepareCall,
   callOptionsSchema.
2. Custom class → flag as I-1 violation (must be ToolLoopAgent).
3. Manual while loop with generateText → flag as I-1 + I-7 violation.
4. `maxSteps` → flag as I-7 violation (must be stopWhen).
5. Check `abortSignal` propagation to subagent calls (I-10).

### Step 3: Classify Concerns

For each tool, classify into the 9-type basis:

| Type | Signal in Code |
|------|---------------|
| domain | Pure computation, no I/O, no DB, no API calls |
| command | Writes to DB, mutates state, calls POST/PUT/DELETE |
| query | Reads from DB/API, no mutation |
| event | Fire-and-forget, async dispatch, webhook send |
| adapter_in | Receives from external system (webhook handler, queue consumer) |
| adapter_out | Sends to external system (API client, queue producer) |
| transformer | Data shape conversion, mapping, formatting |
| validator | Input validation, constraint checking, returns pass/fail |
| policy | Decision logic, routing, if/else on business rules |

### Step 4: Extract Data Flow

From execute handlers:
1. Types flowing IN (from inputSchema).
2. Types flowing OUT (from return value / Output schema).
3. Types shared between tools (same type name in multiple tools).

### Step 5: Flag Atomicity

Look for:
- Shared database transactions (multiple tools writing in try/catch or
  transaction block)
- Sequential tool calls where failure of one should roll back others
- Comments: "atomic", "transaction", "all-or-nothing"
- Tools always called together (check agent instructions or prepareStep)

Set `atomicity` field on affected concerns.

### Step 6: Identify Missing Concern Types

Compare code against the 9-type basis:
- No validators? → Flag: "No input validation concerns"
- No events? → Flag: "No async side effects"
- Command without query? → Flag: "Write without read"
- Adapter_out without error handling? → Flag: "External call without fallback"
- No telemetry? → Flag: "No observability"
- No middleware? → Flag: "No cross-cutting concerns"

### Step 7: biomeConfig Audit — MANDATORY

This step is MANDATORY. Do not skip it. Do not summarize it.

1. **Every function >50 lines**: List with exact line count and function name.
   Each likely contains 2+ hidden concerns that you must extract. Add them to
   the concerns list.
2. **Every file >300 lines**: List with exact line count. Each likely contains
   mixed concern types requiring decomposition.
3. **Functions defined inside other functions**: These are hidden concerns not
   extracted as standalone. List them.
4. **Data constants >10 entries** (`Record<>`, `Array<>`, object literals):
   These are not code — they are data. Count total lines across all files.
5. **Duplicated functions** (same name or same logic in multiple files): List
   with locations and total duplicated lines.

Add findings to output as `biomeViolations` and `duplicatedCode`.

### Step 8: Output Mapping Audit — MANDATORY

For each concern, apply `resolveOutputStrategy()` from
`references/output-mapping.md`:

| Concern Type | Output Variant |
|-------------|---------------|
| validator | Output.object (pass/fail) |
| policy | Output.choice |
| query (array) | Output.array |
| query (single) | Output.object |
| transformer | Output.object |
| command | Output.text |
| domain | Output.object or Output.text |
| adapter_in | Output.object |
| adapter_out | Output.text |
| event | Output.text |

Record what the code USES vs what the mapping SAYS. If zero agents use any
`Output.*` variant, flag this as a systemic HIGH violation.

Run all 5 custom Output detection rules on every concern:
1. outputFields include `code`, `schema`, or `template` → custom parser
2. Transformer with variable output format → fallback chain
3. Returns structured data + prose → composite parser
4. Generates domain syntax (SQL, regex, Three.js, Valibot) → domain parser
5. Output requires external validation → async validator

Flag any concern that triggers a rule but doesn't use custom Output.

### Step 9: SDK Invariant Scan — MANDATORY

Check every agent and tool against all 10 invariants:

| ID | Rule | Check |
|----|------|-------|
| I-1 | Agent is ToolLoopAgent | No custom classes, no manual loops |
| I-2 | `inputSchema` via `valibotSchema()` | No `parameters`, no raw Zod |
| I-3 | `generateText`/`streamText` only | No `generateObject`/`streamObject` |
| I-4 | Provider via gateway/model instance | No raw SDK imports |
| I-5 | Config on agent (prepareCall/prepareStep) | Not scattered in .generate() |
| I-6 | Structured output via `Output.*` | No string parsing, no regex |
| I-7 | `stopWhen` (stepCountIs/hasToolCall) | No `maxSteps` |
| I-8 | Tools are stateless | No shared mutable state, no globals |
| I-9 | Provider normalization in middleware | No if/else per provider |
| I-10 | abortSignal propagation to subagents | Every .generate() call |

Also check:
- `extractReasoningMiddleware` present for open-source models
- `experimental_repairToolCall: true` on every agent
- `TelemetryIntegration` class with lifecycle callbacks
- `devToolsIntegration` wired via experimental_telemetry
- System prompts loaded from .md files (not inline in .ts)
- `callOptionsSchema` on every agent (not just orchestrator)

## Output Format

```json
{
  "featureSummary": "What this agent actually does based on reading the code",
  "concerns": [
    {
      "id": "user-auth-validate",
      "name": "User Authentication Validation",
      "type": "validator",
      "description": "Validates JWT tokens — EXTRACTED from tool 'validateAuth'",
      "reads": ["AuthToken"],
      "writes": ["AuthResult"],
      "state": "stateless",
      "invariants": ["Token must not be expired"],
      "externalSystems": [],
      "atomicity": null
    }
  ],
  "sharedTypes": [
    {
      "name": "AuthToken",
      "description": "EXTRACTED from inputSchema of validateAuth tool",
      "fields": [
        { "name": "token", "type": "string", "description": "JWT bearer token" }
      ]
    }
  ],
  "sdkViolations": [
    {
      "file": "agent.ts",
      "line": "~45",
      "violation": "I-1",
      "description": "Custom agent class instead of ToolLoopAgent",
      "fix": "Replace with ToolLoopAgent({ tools, stopWhen, prepareStep })"
    }
  ],
  "missingConcernTypes": ["event", "validator"],
  "biomeViolations": {
    "filesOver300": [{ "file": "agent.ts", "lines": 450 }],
    "functionsOver50": [{ "file": "agent.ts", "name": "processData", "lines": 120 }],
    "totalDataInCodeLines": 200,
    "totalDuplicatedLines": 150
  },
  "duplicatedCode": [
    { "function": "safeReadJson", "locations": ["a.ts:31", "b.ts:27"], "lines": 3 }
  ]
}
```

Mark every concern and type with "EXTRACTED from [source]" in the description
so downstream blocks know this came from code, not spec.

## OUTPUT GATE — All must pass or output is INVALID

- [ ] Concern count > number of `tool({` definitions (functions >50 lines
  contain hidden concerns — same count means under-extraction)
- [ ] Every file >300 lines listed with exact line count (not "some files are
  long")
- [ ] Every function >50 lines listed with exact line count and function name
  (not "some functions are large")
- [ ] Total duplicated lines is a NUMBER (not "some duplication exists")
- [ ] Total data-in-code lines is a NUMBER (not "some constants are inline")
- [ ] Every SDK violation has: file, line (~approximate ok), invariant ID,
  description, fix
- [ ] `resolveOutputStrategy()` table shown — every concern mapped to Output
  variant, with "code uses" vs "mapping says" columns
- [ ] All 5 custom Output detection rules run — results shown per concern
- [ ] `missingConcernTypes` populated (which of the 9 types are absent)

## FAIL CONDITIONS

These are from `references/review-rejections.md`, inlined here so they cannot
be skipped:

- **R-2**: A `tool({` is a CONTAINER, not a concern. Functions >50 lines are
  hidden concerns. If your output has ≤ the number of tool definitions, you
  have under-extracted. Go back to Step 7.
- **R-4**: biomeConfig violations (files >300, functions >50) are HIGH
  severity — they indicate hidden concerns, not formatting issues. A 160-line
  function is an incomplete extraction, not a style problem.
- **R-8**: Duplicated code is a concern extraction failure. Same function in
  two files = shared utility that should be one module. Count the lines.
- **R-9**: Data constants >10 entries mixed with logic are not code. They
  inflate line counts and obscure logic. Count them separately.
- **R-11**: If your output looks like a bullet-list code review instead of
  the `ExtractOutput` JSON format above, you have produced training output.
  Start over.
