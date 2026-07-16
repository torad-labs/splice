# Custom Output Interface Patterns

Read this when the scaffold specifies `outputStrategy: "custom"` for a tool,
or when you need to understand when and how to implement the `Output` interface.

## What Custom Output IS

The SDK provides five built-in Output variants: `Output.object()`, `Output.array()`,
`Output.choice()`, `Output.text()`, `Output.json()`. These cover most cases.

Custom Output means **implementing the `Output` interface directly**. You control:
- What `responseFormat` the model sees (text, JSON, etc.)
- How `parseOutput` interprets the model's response
- What type the tool returns

This is NOT `Output.object({ schema })`. That's a built-in variant with a custom schema.
Custom Output is a completely different parsing pipeline.

## When Built-In Fails

| Scenario | Why Built-In Can't Handle It | Custom Output Does |
|----------|-----------------------------|--------------------|
| Code generation | Tool produces Valibot schemas, SQL, regex — domain syntax, not JSON | Custom parser validates the domain syntax |
| Streaming validation | `Output.object()` can't validate partial JSON mid-stream | Custom validates incrementally, emits partials |
| Multi-format response | Tool returns JSON header + markdown body + code block | Custom splits by format markers, parses each |
| Fallback chain | Model sometimes returns JSON, sometimes prose | Custom tries JSON → XML → structured text → raw |
| Composite output | Structured data + natural language summary in one response | Custom splits and validates each part |
| Validated enum + metadata | `Output.choice()` returns string only, need choice + data | Custom validates choice AND extracts associated payload |

## The Interface

```ts
import type { Output } from "ai";

interface Output<T> {
  type: "custom";
  responseFormat: { type: "text" } | { type: "json" };
  parseOutput: (params: { text: string }) => Promise<
    | { success: true; value: T }
    | { success: false; error: string }
  >;
}
```

Three fields. `type` is always `"custom"`. `responseFormat` tells the model how to
respond. `parseOutput` turns the model's text into your typed value or an error.

## Pattern 1: Domain Syntax Parser

For tools that produce code, schemas, SQL, regex — anything with domain-specific syntax.

```ts
const valibotSchemaOutput: Output<ParsedValibotSchema> = {
  type: "custom",
  responseFormat: { type: "text" },
  parseOutput: async ({ text }) => {
    // Extract code block if wrapped in fences
    const code = text.match(/```(?:ts|typescript)?\n([\s\S]*?)\n```/)?.[1] ?? text;

    try {
      const parsed = parseValibotExpression(code);
      return { success: true, value: parsed };
    } catch (error) {
      return {
        success: false,
        error: `Invalid Valibot syntax: ${error instanceof Error ? error.message : String(error)}`,
      };
    }
  },
};
```

Use `responseFormat: { type: "text" }` — plain text lets the model write code freely
without JSON escaping mangling the syntax.

## Pattern 2: Composite Output (structured + prose)

For tools that return both structured data and a natural language summary.

```ts
const compositeOutput: Output<{ data: AnalysisResult; summary: string }> = {
  type: "custom",
  responseFormat: { type: "text" },
  parseOutput: async ({ text }) => {
    const jsonMatch = text.match(/```json\n([\s\S]*?)\n```/);
    const summaryMatch = text.match(/## Summary\n([\s\S]*?)$/);

    if (!jsonMatch) return { success: false, error: "No JSON block found" };

    try {
      const data = JSON.parse(jsonMatch[1]);
      const summary = summaryMatch?.[1]?.trim() ?? "No summary provided";
      return { success: true, value: { data, summary } };
    } catch {
      return { success: false, error: "Invalid JSON in data block" };
    }
  },
};
```

## Pattern 3: Fallback Chain

For tools where the model might respond in different formats depending on complexity.

```ts
const resilientOutput: Output<ParsedResponse> = {
  type: "custom",
  responseFormat: { type: "text" },
  parseOutput: async ({ text }) => {
    // Try JSON first
    try {
      const parsed = JSON.parse(text);
      return { success: true, value: parsed };
    } catch { /* not JSON */ }

    // Try structured text extraction
    const structured = parseStructuredText(text);
    if (structured) return { success: true, value: structured };

    // Last resort: wrap raw text
    return { success: true, value: { raw: text, parsed: false } };
  },
};
```

## Pattern 4: Validated Enum with Payload

When `Output.choice()` isn't enough because you need data alongside the choice.

```ts
type Decision = { action: "approve" | "reject" | "escalate"; reason: string; confidence: number };

const decisionOutput: Output<Decision> = {
  type: "custom",
  responseFormat: { type: "json" },
  parseOutput: async ({ text }) => {
    try {
      const parsed = JSON.parse(text);
      const validActions = ["approve", "reject", "escalate"];
      if (!validActions.includes(parsed.action)) {
        return { success: false, error: `Invalid action: ${parsed.action}` };
      }
      if (typeof parsed.confidence !== "number" || parsed.confidence < 0 || parsed.confidence > 1) {
        return { success: false, error: "Confidence must be 0-1" };
      }
      return { success: true, value: parsed as Decision };
    } catch {
      return { success: false, error: "Response is not valid JSON" };
    }
  },
};
```

## How the Scaffold Detects Custom Output Need

The SCAFFOLD block (via `agents/scaffolder.md` Step 1b) auto-detects:

1. **outputFields include `code`, `schema`, or `template` type** → domain syntax parser
2. **Concern is a transformer with variable output format** → fallback chain
3. **Tool returns structured data + prose** → composite parser
4. **Concern generates implementation code** → code block extraction + validation
5. **Output needs enum + associated data** → validated enum with payload

When detected, the scaffold sets `outputStrategy: "custom"` and produces
the full implementation in `customOutputCode` with rationale in `customOutputReason`.
