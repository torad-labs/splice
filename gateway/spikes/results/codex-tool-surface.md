# How codex exposes tools, and why claudex takes ~2x the round-trips (2026-07-31)

**Verdict: two real architectural differences. One is a knob (landed). The other is not portable
to a proxy without splice becoming a tool executor.**

Operator observation: "GPT uses a significantly higher amount of tools than kimi and grok do. I
wonder if it has anything to do with how OpenAI handle tools in general." Also: "tools are sent in
one object to the backend on the Codex codebase which we don't do."

Both halves check out.

## The measurement

Same daemon, same log, all three heads:

| head | turns | ends in a tool call | mean output tokens/turn |
|---|---|---|---|
| claudex | 24,080 | 97.4% | **386** |
| claude-grok | 1,601 | 96.2% | 663 |
| claude-kimi | 1,030 | 89.3% | 786 |

The tool-call *rate* is the same. claudex just does about **half the work per round-trip**, so it
needs roughly twice as many trips — and every trip re-sends the whole context (~177k tokens on a
long session). This is the dominant cost driver for the codex head.

## Difference 1: one tool call per turn (a knob, landed)

`parallelToolCallsFor` sent `parallel_tool_calls = false` on **every** responses-lite turn,
hardcoded. codex-rs does not hardcode it — it sends
`turn_context.model_info.supports_parallel_tool_calls` (`session/turn.rs:1290`,
`compact_remote_request.rs:66`), a per-model configurable value.

So the parity claim was stronger than the reality: codex treats it as a per-model knob, splice
treated it as a constant.

It is now the `parallel_tool_calls` key in the codex provider's `quirks` table (add it INSIDE the
existing inline `quirks = { ... }` — a separate `[providers.codex.quirks]` section header clashes
with the inline table and fails to parse), **defaulting to false** — today's exact behaviour —
using the nullable-overlay idiom so absent TOML can never stomp a provider default. It applies to
responses-lite turns only; on non-lite turns (grok) the wire value still comes from the client's
`tool_choice`, so setting the key there is an accepted no-op.

The default did NOT change, deliberately. The recorded pathology (gpt-5.6 "spraying 30-50 parallel
Task calls") came from **omitting the field**, which left the backend default parallel ON. That is
not the same as sending an explicit `true`, and the explicit-`true` case has never been tested. The
knob exists so that can be measured on one head without a rebuild or a code change. Two guardrails
land with it (review of #71 round 2): a client's explicit `disable_parallel_tool_use = true` beats
the knob (the gateway must not override a request the client asked to serialize), and toolless
turns stay `false` (nothing to parallelize, and explicit-true-without-tools is itself an untested
combination — probe it before assuming it is accepted).

## Difference 2: codex collapses the whole tool surface into ONE `exec` tool

From the installed codex 0.145.0 binary, and matching a captured `CLIENT->SERVER`
`response.create` frame:

```
Run JavaScript code to orchestrate/compose tool calls
- Evaluates the provided JavaScript code in a fresh V8 isolate as an async module.
- All nested tools are available on the global `tools` object, for example
  `await tools.exec_command(...)`. Tool names are exposed as normalized JavaScript
  identifiers, for example `await tools.mcp__ologs__get_profile(...)`.
- `yield_control()`: yields the accumulated output to the model immediately while the
  script keeps running. Defaults to 10000 ms.
Some deferred nested tools may be omitted from this description. They are still
available on the global `tools` object and listed in `ALL_TOOLS`.
```

The captured frame carries this as a single `{"type":"additional_tools","role":"developer",
"tools":[...]}` input item — which splice already matches structurally (`ResponsesLite.liteInput`).
The difference is not the envelope, it is the **contents**: codex puts one `custom` tool named
`exec` inside it, and the model composes many tool invocations in a single response by writing
JavaScript. splice puts N individual function tools inside it, and the model calls one per turn.

That is the mechanism behind the 386-vs-663 gap, and it is independent of `parallel_tool_calls`:
codex does not need parallel tool calls because `exec` already batches.

**A second consequence — deferral means different things.** codex defers only the tool's
*description bytes*; the tool stays callable via the global `tools` object and discoverable in
`ALL_TOOLS`. splice's deferral removes the *capability*: a deferred tool cannot be called until a
`tool_search` round surfaces it. Live evidence that this bites: 95% of claudex turns carry 52
deferred tools, and there have been **27 `tool search round` events in the entire log**. Those 52
tools are effectively invisible rather than merely undescribed.

## Why splice cannot simply adopt `exec`

codex owns tool execution: it runs the V8 isolate in-process and dispatches `await tools.X(...)` to
its own handlers. splice is a **proxy** — Claude Code executes the tools, and the wire contract back
to it is Anthropic `tool_use` blocks.

Adopting `exec` would mean splice hosting a JavaScript engine, and suspending the script at every
`await tools.X(...)` to emit a `tool_use` block, wait for Claude Code to return a `tool_result`, and
resume. That is a coroutine boundary across the proxy, plus a JS runtime, plus a changed streaming
contract. Technically possible; it is a project, not a fix, and it is an operator decision — noted
here rather than attempted.

## Scope of this evidence, stated honestly

The `exec` tool description is verbatim from the shipped binary (`strings`) and its presence in a
real client frame is confirmed. What is NOT established: that `exec` is the *only* tool codex sends.
The captured frames are 51,052B and 21,859B and were **display-capped during capture**, so they do
not parse as JSON; the only tool names extractable from the visible portion are `exec`, `wait` and
`request_user_input`. Proving the full tool list would need a re-capture without the cap. The
round-trip measurement above does not depend on that, since it is drawn from daemon telemetry.
