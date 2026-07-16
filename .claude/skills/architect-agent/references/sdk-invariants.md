# AI SDK v6 Invariants

Hard constraints. Violating any one is a build failure.

**I-1: The agent is a `ToolLoopAgent`.**
Not a custom class. Not a function wrapping generateText in a loop.
Custom `Agent` interface implementations are valid only when ToolLoopAgent
genuinely cannot express the behavior (e.g., DurableAgent for resumable workflows).

**I-2: Capabilities are `tool()` objects with `inputSchema`.**
Not `parameters` (renamed in v6). Schema via `valibotSchema()` or `zodSchema()` or `jsonSchema()`.

**I-3: Inference uses `generateText()` or `streamText()` exclusively.**
`generateObject()` and `streamObject()` are removed from v6.

**I-4: Provider selection uses gateway strings or `LanguageModel` instances.**
Gateway: `"anthropic/claude-sonnet-4.5"`. Direct: `anthropic("claude-sonnet-4-5-20250929")`.
Raw provider SDKs (`@anthropic-ai/sdk`, `openai`) are never imported.

**I-5: Configuration aggregates on the agent.**
Constructor (defaults), `prepareCall` (per-invocation), `prepareStep` (per-step).
Never scattered across `.generate()` call sites.

**I-6: Structured output uses `Output` with schemas.**
`Output.object()`, `Output.array()`, `Output.choice()`, `Output.text()`, `Output.json()`.
Never string parsing. Never regex on LLM output.

**I-7: The SDK manages tool loops.**
`ToolLoopAgent` with `stopWhen`. No manual while loops. No `maxSteps` (removed).
`stopWhen: stepCountIs(N)` or `stopWhen: hasToolCall("done")` or composite arrays.

**I-8: Tools are stateless.**
Inputs in, outputs out. No shared mutable state. No globals.
No files as inter-block communication (use `prepareStep` message modification).

**I-9: Provider normalization lives in middleware.**
`wrapLanguageModel` with `LanguageModelV3Middleware`.
Provider differences never handled via if/else in agent logic.

**I-10: Subagent tool handlers propagate `abortSignal`.**
Every tool `execute` that calls `subAgent.generate()` or `subAgent.stream()`
must forward `abortSignal` from its execution context.

## Lifecycle Callbacks

Constructor-level (agent-wide) and method-level (per-call). Both fire when defined.

```
experimental_onStart        → Generation begins, before any LLM calls
experimental_onStepStart    → Before each step (LLM call)
experimental_onToolCallStart → Before tool execute runs
experimental_onToolCallFinish → After tool execute completes/errors
onStepFinish                → After each step finishes
onFinish                    → All steps done, response complete
```

## TelemetryIntegration Interface

```ts
import { type TelemetryIntegration, bindTelemetryIntegration } from "ai";

class MyTelemetry implements TelemetryIntegration {
  async onStart(event) {}
  async onStepStart(event) {}
  async onToolCallStart(event) {}
  async onToolCallFinish(event) {}
  async onStepFinish(event) {}
  async onFinish(event) {}
}

export const myTelemetry = () => bindTelemetryIntegration(new MyTelemetry());
```

Pass via `experimental_telemetry.integrations` array. Errors caught internally.
Multiple integrations compose — all receive the same lifecycle events.

## Testing

```ts
import { MockLanguageModelV1 } from "ai/test";
import { simulateReadableStream } from "ai";

const mockModel = new MockLanguageModelV1({
  doGenerate: async () => ({
    text: "mocked response",
    usage: { inputTokens: 10, outputTokens: 20 },
    finishReason: "stop",
    rawCall: { rawPrompt: null, rawSettings: {} },
  }),
});
```

Use `MockLanguageModelV1` for unit tests. Use real models for integration/eval tests.
`simulateReadableStream` for streaming test scenarios.

## DevTools

```ts
import { devToolsIntegration } from "@ai-sdk/devtools";

experimental_telemetry: {
  isEnabled: true,
  integrations: [devToolsIntegration()],
}
```

Run viewer: `npx @ai-sdk/devtools` → `http://localhost:4983`
Captures: input, output, tool calls, token usage, timing, raw provider data.
Stores in `.devtools/generations.json`. Auto-adds to `.gitignore`.
