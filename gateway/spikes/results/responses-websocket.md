# Can splice use the Responses WebSocket + previous_response_id chaining? (2026-07-31)

**Verdict: GO — all three facts. The ChatGPT codex backend accepts splice's own OAuth on the v2
WebSocket, streams the same event vocabulary the SSE translator already consumes, and honors
`previous_response_id` chaining with `store: false`.**

One live probe (scratchpad `ws_probe.py`, Python `websockets`), no codex CLI involved — splice's
own credentials from `~/.codex/auth.json`.

## The three facts

| # | question | observed |
|---|---|---|
| 1 | does the handshake succeed? | **101** on `wss://chatgpt.com/backend-api/codex/responses` with `Authorization: Bearer` + `ChatGPT-Account-ID` + `OpenAI-Beta: responses_websockets=2026-02-06` + `originator: codex_cli_rs` |
| 2 | does a `response.create` stream? | full turn: `response.created → … → response.output_text.delta → response.completed`, `usage` present |
| 3 | does chaining work? | **turn 2 sent ONLY the new user message + `previous_response_id`** (no history) and the model answered correctly in context; `store: false` throughout |

Payload frames are plain JSON — one event per text message, `{"type":"response.create", …}` out,
the standard `response.*` events back. WS-only extras observed: `codex.rate_limits`,
`codex.response.metadata`, `responsesapi.websocket_timing` — all fall into the reducer's
`else -> Unit`, so the existing `ResponsesStreamTranslator` consumes a WS round unchanged.

Contract sources: codex-rs `client.rs` (`RESPONSES_WEBSOCKETS_V2_BETA_HEADER_VALUE =
"responses_websockets=2026-02-06"`; `ResponseCreateWsRequest` = the HTTP request + `type` +
`previous_response_id` + optional `generate:false` prewarm; attestation header is **optional** —
`include_attestation` gate, provider may be absent) and the captured 0.145 frames.

## What the leverage actually is — stated precisely

- **Billed input tokens do NOT drop.** Turn 2's usage counted the full logical context (44 tokens
  = turn 1's context + the new message): the server reconstructs and processes the whole context
  either way.
- **What drops is the client's reconstruction of history** — and with it the entire class of
  mid-array prefix drift this repo spent two review rounds fixing. The server-held context cannot
  disagree with itself, so the prompt cache should sit at its ceiling instead of being hostage to
  byte-perfect client rebuilds.
- **Wire upload collapses** from ~480KB per tool round to ~1KB (the delta), removing the
  re-serialization and upload latency from every one of the ~95.6% of turns that are tool
  round-trips.

## Caveats, honestly

- **Chaining is presumed per-connection.** codex's own comment ties `previous_response_id` reuse
  to the cached connection; cross-connection chaining was not probed. Design consequence:
  reconnect ⇒ full resend (which is exactly today's behavior, so the floor is status quo).
- **Tiny-context probe.** 25/44 input tokens is below the caching threshold (`cached=0` proves
  nothing about cache economics either way). The cache-ceiling claim is an inference from the
  mechanism, to be verified from live telemetry after the transport lands (ledger WS-5).
- One model (`gpt-5.6-sol`), toolless turns, one connection, one account. Tool-round chaining
  (function_call_output as the delta) is exercised by the transport's tests and WS-5, not by this
  probe.
- Auth tokens were read from `~/.codex/auth.json` and never logged.

## Consequence

The `previous_response_id` section of `prompt-cache-drain.md` ("not portable — separate project")
is now the **ws-transport campaign** (`dev/campaigns/ws-transport.toml`): WsUpstream (JDK
`java.net.http.WebSocket`, no new dependencies) + a bail-closed delta classifier + default-off
`quirks.websocket`, SSE fallback at every stage.
