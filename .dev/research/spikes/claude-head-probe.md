# claude-splice head — live probe receipt

Campaign `claude-head`, item CH-11. 2026-08-15.

**Status: HALF PROVEN. The unauthenticated half is done and reproducible; the authenticated half is
the operator's to run** (it needs a credential splice deliberately never holds, and it spends
subscription quota). Read that split literally — this receipt does not claim the head has served a
real turn.

## What was proven, autonomously, with no credential and no quota

A real `Daemon` booted from a temporary topology (`auth = { kind = "client" }`, base URL
`https://api.anthropic.com`) and served a turn on a loopback head, forwarding a deliberately
invalid bearer.

    ./gradlew :app:test --tests live.ClaudeHeadLiveProbeTest   # with SPLICE_LIVE_PROBE=1

Through the head:

    event: error
    data: {"type":"error","error":{"type":"authentication_error","message":"Invalid bearer token"}}

Directly against the vendor, same body shape, same invalid bearer
(`PROBE` holds a deliberately non-credential string — the probe never uses a real one):

    curl -sS https://api.anthropic.com/v1/messages \
      -H "Authorization: Bearer $PROBE" \
      -H "anthropic-version: 2023-06-01" -H "content-type: application/json" \
      -d '{"model":"claude-fable-5","max_tokens":16,"stream":true,
           "messages":[{"role":"user","content":"probe"}]}'

    HTTP_STATUS=401
    {"type":"error","error":{"type":"authentication_error","message":"Invalid bearer token"},
     "request_id":"req_011Ce5Gj8V34yzBLiSGs93Tz"}

What that establishes:

1. **The endpoint is real and was reached.** Anthropic returned a `request_id`, which only its own
   servers mint. Host, TLS and path resolve.
2. **The request SHAPE is accepted.** 401, not 400 — the vendor parsed the body this dialect builds
   and got as far as authenticating it. A malformed request would have been rejected earlier, and
   the probe asserts the absence of `invalid_request_error` precisely to catch that.
3. **Splice injected no credential of its own.** The invalid bearer the caller sent is the one that
   was judged; had the head added anything, the outcome would differ.
4. **The failure surfaces honestly to the client**, as `authentication_error` with the vendor's own
   message and NO splice-invented sign-in hint — deliberate: on a client-auth head the client's
   native `/login` is the remedy, and a `claude-splice login` hint would point at a command that
   does nothing.

What it does NOT establish: that an authenticated turn succeeds, that streaming/thinking blocks
render, that prompt caching is credited, or that rate-limit headers land in telemetry.

## The remaining half — operator, once

Needs the operator's own Claude login, spends a little subscription quota, and cannot be delegated:
splice never reads, stores or refreshes an Anthropic credential, so the only way this turn happens
is a real client authenticating natively.

1. Add the `[providers.anthropic]` + `[heads.claude-splice]` blocks from `config/splice.example.toml`
   to `~/.config/splice/splice.toml`, then restart the daemon.
2. `splice install` (or the usual wrapper install) so `claude-splice` is on PATH, then run `claude-splice`.
   Because this is a client-auth head, the launcher leaves your credentials, keychain and `/login`
   alone — if it is not signed in, `/login` inside the session works normally.
3. Send one prompt, then one that uses a tool (a round trip).

Record here afterwards: model served, whether `/login` behaved, `cache_creation_input_tokens` /
`cache_read_input_tokens` from `/api/usage` (prompt caching is the economic reason the head
preserves `cache_control`), whether thinking blocks rendered, and whether
`anthropic-ratelimit-*` headers reached the statusline.

If any of that misbehaves, the wiring to suspect first is the forwarding allowlist, which HD-24
moved out of `HeadServer.kt` into `gateway/gateway/src/main/kotlin/splice/gateway/head/ClientAuth.kt`
(`FORWARDED_CLIENT_HEADERS` and `forwardedClientHeaders`) — it is the one place a header the vendor
needs could be missing. One defect there is already fixed: until HD-5 (2026-09-20) the allowlist was
read with `headers[name]`, which keeps only the FIRST field line, so repeated `anthropic-beta` lines
lost every flag but one. It now rejoins them per RFC 9110 5.3.

## The other half, run — CH-13, 2026-09-20

**Status: PROVEN.** The authenticated half ran on the operator's real Max login, through the
`claude-splice` head on `:3104`, against `https://api.anthropic.com`. Session `87a6fd6e`,
client Claude Code v2.1.278, daemon `156943f7`. Two prompts: one plain, one tool round-trip.

Six turns reached the vendor, every one `outcome: ok`
(`~/.claude-codex/state/claude-splice-perf.jsonl`, also served by
`GET /api/perf/turns?head=claude-splice`):

| # | 06:xx | model | in | out | `cached_tokens` | `cache_write_tokens` |
|---|-------|-------|----|-----|-----------------|----------------------|
| 1 | 11:58 | `claude-haiku-4-5` | 8 | 1 | 0 | 0 |
| 2 | 14:11 | `claude-haiku-4-5` | 895 | 9 | 0 | 0 |
| 3 | 14:15 | `claude-fable-5` | 134,237 | 22 | 0 | **134,235** |
| 4 | 16:19 | `claude-fable-5` | 134,355 | 112 | **134,235** | 118 |
| 5 | 16:21 | `claude-fable-5` | 135,415 | 5 | **134,353** | 1,060 |
| 6 | 16:26 | `claude-fable-5` | 135,922 | 126 | **135,413** | 5 |

**Model served.** `claude-fable-5`, the head's `pinned_model`, for every conversational turn;
`claude-haiku-4-5` for Claude Code's own small-model calls (startup probe, titling). Both are
slots this head declares in `[heads.claude-splice].models`, so the model list resolved end to end.
The client's own banner read `Fable 5 with xhigh effort · Claude Max`.

**`/login`.** Never invoked, and never needed — which is the behaviour under test. The launcher
materialized the selected credential from `~/.claude-codex/state/claude-logins/` into the head's
own home `~/.claude-claude-splice/.credentials.json`, and the session came up authenticated: no
sign-in prompt, no `authentication_error`, and no splice-invented sign-in hint. `auth = { kind =
"client" }` held — splice injected no credential of its own, exactly as the unauthenticated half
established. What is NOT claimed here: that an interactive `/login` from a signed-OUT state
completes. That path was not exercised.

**Prompt caching is credited.** Turn 3 wrote the 134,235-token prefix; turns 4-6 read it back
(134,235 → 134,353 → 135,413) while writing only the delta. So `cache_control` survives the
passthrough in both directions: the dialect reads the vendor's counters at
`PassthroughUsage.kt:39` (`cache_read_input_tokens` → `cached_tokens`) and `:49`
(`cache_creation_input_tokens` → `cache_write_tokens`). The client agreed: its statusline cache
indicator read `⚡ 99%`. Note for a future reader — the cache counters are on
`/api/perf/turns`, not `/api/usage`; `/api/usage` carries the quota half below.

**Thinking blocks render.** With `show_reasoning = "text"`, turn 3 streamed
`∴ The user just wants me to respond with "OK".` above the answer, and the client closed both
turns with its own timer (`✻ Cogitated for 4s`, `✻ Baked for 5s`). No `[reasoning summary]`
block appeared, as the operator lock requires.

**Tool round-trip.** `Read(README.md · lines 1-30)` → `⎿ Read 30 lines` → `● splice`. Turn 4 is
the `tool_use` request (out 112), turn 5 the answer after the `tool_result` (out 5). Two round
trips in one prompt, both `ok`.

**`anthropic-ratelimit-*` reaches the statusline.** `claude-splice-quota.json` moved with the
turns — `five_hour` 60.0 % → 65.0 %, `seven_day` 14.0 % → 15.0 %, `updated_at` advancing
1789902718301 → 1789902983420 — and the client rendered it live:
`5h █████░░░ 65%→Sun 09:30   7d █░░░░░░░ 15%`. `GET /api/usage` reported the same head at
`five_hour.used_pct 65`, `seven_day.used_pct 15`. The headers are parsed per turn in
`SseRoundPost.kt:54` and served from memory by `PendingRateLimit`.

`FORWARDED_CLIENT_HEADERS` needed no attention: nothing misbehaved.
