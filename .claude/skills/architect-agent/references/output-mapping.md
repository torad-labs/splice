# Concern Type → Output Variant Mapping

Deterministic. Applied in the SCAFFOLD block before LLM generation.

| Concern Type | Condition | Output Variant | Schema Shape |
|-------------|-----------|---------------|-------------|
| validator | always | `Output.object()` | `{ pass: boolean, violations: { field, message }[] }` |
| policy | always | `Output.choice()` | `{ options: string[] }` from policy paths |
| query | writes array type | `Output.array()` | `{ element: entitySchema }` |
| query | writes single type | `Output.object()` | entity schema from outputFields |
| transformer | always | `Output.object()` | target shape from outputFields |
| command | always | `Output.text()` | confirmation string |
| domain | outputFields > 1 | `Output.object()` | schema from outputFields |
| domain | outputFields = 1 string | `Output.text()` | plain text |
| adapter_in | always | `Output.object()` | normalized internal schema |
| adapter_out | always | `Output.text()` | send confirmation |
| event | always | `Output.text()` | fire-and-forget ack |

## Implementation

```ts
function resolveOutputStrategy(concern: Concern): OutputStrategy {
  switch (concern.type) {
    case "validator": return "Output.object";
    case "policy": return "Output.choice";
    case "query":
      const writesArray = concern.writes.some(t => t.endsWith("[]"));
      return writesArray ? "Output.array" : "Output.object";
    case "transformer": return "Output.object";
    case "command": return "Output.text";
    case "adapter_in": return "Output.object";
    case "adapter_out": return "Output.text";
    case "event": return "Output.text";
    case "domain":
      return concern.writes.length > 1 ? "Output.object" : "Output.text";
    default: return "Output.object";
  }
}
```

This function runs in the SCAFFOLD tool's execute handler BEFORE the LLM call.
Pre-resolved strategies reduce the LLM's decision space.

## Custom Output — When Built-in Variants Don't Fit

The `Output` interface can be implemented directly for cases where
`Output.object/array/choice/text/json` are insufficient.

### When to Use Custom Output

| Scenario | Why Built-in Fails | Custom Output Does |
|----------|-------------------|-------------------|
| Streaming partial validation | `Output.object()` can't validate incomplete JSON | Validates incrementally, emits partial results |
| Multi-format response | Tool returns JSON + markdown + binary sections | Parses each format section separately |
| Domain-specific syntax | Output contains SQL, regex, Valibot schema code | Extracts and validates the domain syntax |
| Fallback chain | Model sometimes returns JSON, sometimes prose | Chains parsers: try JSON → try XML → raw text |
| Composite output | Returns structured data PLUS natural language summary | Splits and validates each part independently |
| Validated enum with metadata | `Output.choice()` returns string, but you need the choice + metadata | Custom validates choice AND extracts associated data |

### Implementation Pattern

```ts
import type { Output } from "ai";

const domainSyntaxOutput: Output<ParsedDomain> = {
  type: "custom",
  responseFormat: { type: "text" },  // Ask model for plain text
  parseOutput: async ({ text }) => {
    const parsed = parseDomainSyntax(text);
    if (!parsed) {
      return { success: false, error: "Failed to parse domain syntax" };
    }
    return { success: true, value: parsed };
  },
};

// Usage
const { output } = await generateText({
  model,
  output: domainSyntaxOutput,
  prompt: "Generate a Valibot schema for user registration...",
});
```

### Scaffold Detection Rules

The SCAFFOLD block should recommend `custom` output when:

1. The concern's output involves **code generation** (outputFields include a `code` or `schema` type)
2. The concern is a **transformer** whose output format varies by input (conditional parsing)
3. The concern returns **mixed content** (structured data + prose in the same response)
4. The concern's validation requires **external state** (e.g., checking against a schema registry)

When `outputStrategy: "custom"` is set, the `customOutputCode` field MUST contain
the full implementation, and `customOutputReason` MUST explain why built-in is insufficient.
