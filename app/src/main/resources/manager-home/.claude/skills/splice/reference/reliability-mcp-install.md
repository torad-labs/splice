Last read: 2026-11-09 against splice 0.4.0 (feat/v0.4.0)

# Reliability, MCP, code mode, install and troubleshooting

Read this file for: why a turn failed and what splice already retried, how MCP servers reach sessions, code
mode, how splice is built and where its state lives, install, upgrade and rollback, and the symptom table at
the end. Other files own the rest: `toml-reference.md` (TOML syntax of every field), `cli.md` (every verb),
`accounts-sessions-console.md` (logins, pages), `operations.md` (the safe way to edit and restart).

`(unverified)` marks a claim I could not confirm in code or a current doc. Where a doc and the code disagree,
the code wins and the line says so.

## 1. Retries and errors

### 1.1 The two rules

| rule | meaning |
|---|---|
| Retry is total | A known error gets its own plan. Every other error gets a bounded generic retry and an honest message. No failure ends a turn without one of the two. |
| Idle is a probe | Silence from the model is a question, never a verdict. splice acts only on a dead path, by re-anchoring (resuming the stream). It never reaps a model that is thinking in silence. |

Source: repo `CLAUDE.md` "Project laws"; `core/src/main/kotlin/splice/core/config/Knob.kt` (the note above
`UPSTREAM_TIMEOUT_MS` on the retired retry knobs, and the `STREAM_IDLE_MS` entry); `core/.../failure/FailureCause.kt`
lines 1-26 and 161 (`Provider.Unrecognized`); `integrations/upstream/.../retry/RetryEngine.kt` lines 46-64, 151-161
and 237-243.

Only a few verdicts are never retried, because the same bytes get the same answer: a credential rejected again
after a refresh, a context overflow (Claude Code compacts instead), a content-policy refusal, a refusal the
vendor module names, a spent plan window (the reset is the wait), an `encrypted_content` decrypt error.
Source: `RetryEngine.kt` lines 163-190 (`Rows.provider`, `Rows.verdict`) and 246-263 (`Logins`);
`integrations/upstream/.../retry/limits/RetryLimits.kt` lines 80-89.

### 1.2 The layers, in order

| layer | trigger | bound |
|---|---|---|
| Attempt backoff | any failed attempt (connection failure, any HTTP status) | RetryEngine decides from what failed and where: a server error before the provider's stream began is re-sent after 200, 400 and 800 ms. No knob sets it: `upstreamRetries` and `retryBackoff*` were retired in 0.4.0 |
| Credential refresh | a vendor auth failure | one refresh and one free re-send per turn. Rejected again: give up. A pooled head moves the turn to another login |
| Stream re-issue | stream torn after a 2xx, before the client saw a byte | 2 re-issues per turn |
| Body amendment | a 400 that a dialect knows how to repair | one amended re-send per turn |
| Re-anchor | stream cut or stalled after content began | splice re-sends from what it delivered. ChatGPT, Muse and other Responses heads: up to 5 times unless a tool call had started. Other heads only where measured to continue (Kimi, or `reanchor_prefill = true`) |
| Client retry | Claude Code receives `overloaded_error` | Claude Code re-sends. splice launches every client with `CLAUDE_CODE_RETRY_WATCHDOG=1` (persistent retry), so a rate-limit hold longer than the default retry budget does not end the session. It also plants `CLAUDE_CODE_NONSTREAMING_TIMEOUT_RETRIES=2`, because the client's limit for re-sending a non-streaming request whose own timer fired is unlimited when that is unset. That limit does not bound a cut by Bun's built-in fetch timeout at about 360 s, which the client does not count and which re-sent one request 228 times on Nov 8-9, so splice plants `API_FORCE_IDLE_TIMEOUT=0` too: the client then passes `timeout: false` to fetch and the head's own deadline answers a non-streaming request first |

Source: `Knob.kt` (the note above `UPSTREAM_TIMEOUT_MS`); `RetryEngine.kt` 46-64 and 150-271;
`integrations/upstream/.../retry/RetryBudgets.kt` 46-66; `features/launch/.../recipe/LaunchService.kt` 370-403;
`CHANGELOG.md` 160-163.

### 1.3 Known errors and their plans

| error | plan |
|---|---|
| bare 429 without a reset or quota window | move to an available pooled login before content; otherwise retry after 2, 4, 8, 16, 30, 30 and 30 s within the turn deadline, then end retryably; the two-minute budget is shared across logins |
| named exhausted window without a reset, or billing-cycle limit | move to an available pooled login before content; otherwise end promptly with the provider's words, without an indefinite hold |
| 429 with a stated reset | use the bounded reset/warm-login wait policy, moving to another eligible login where appropriate |
| 429 that names a spent plan window and reset (5-hour or 7-day) | the account retains that window's reset; the turn follows the bounded wait or pooled handoff policy |
| bare 429 after retries run out | the account is held 20 s without inventing a reset. The shared hold never exceeds 120 s |
| grok spending-limit 403, any 402 | treated as a 429 by every layer |
| every account of a head exhausted | refused at admission, zero upstream calls, with the earliest reset when known |
| a path that gives no answer: a name that does not resolve (DNS), a refused connection, a failed TLS handshake, a reset, close or timeout before any reply | waited out at 1, 2, 4, 8, 16, 30, 30 and 30 s (about two minutes in all, enough to outlast a local model server's restart), then the turn ends |
| 503, 529 or a code that spells an overload | waits of 2, 4, 8, 15 and 30 s (about a minute). A stated `Retry-After` sets the least a wait may be, so one of 15 s or less is waited out; a longer one follows the stated-reset rows above. A local model that is still loading is then asked every 60 s, up to 5 minutes in all. Then the turn ends |
| 500, 502, 504 | while the provider's stream has not begun, re-sent three times, after 200, 400 and 800 ms. Once it has begun, continued from what arrived where the head can continue (1.2) |
| 403 | another pooled login takes the turn before content, if one is free; otherwise it ends. It is not re-sent on the same login |
| 400 the provider rejected, 404 that names a model, 413 | end at once. A 400 over reasoning the provider cannot use is first re-sent once without it (1.2) |
| a status the provider's reader does not name: 408, 409, 422, a 404 that names no model, an unusual 5xx | sent once more at once, then the turn ends |

Source: `integrations/upstream/.../retry/limits/RetryLimits.kt` 26-89; `RetryBudgets.kt` 17-47 and 68-69;
`integrations/upstream/.../retry/RateLimitCooldown.kt` 284, 295; `integrations/upstream/.../retry/PlanHold.kt`;
`RetryEngine.kt` 152-206 (`Rows`) and 217-244 (`Streams`); which status becomes which cause is read in
`integrations/dialects/anthropic/.../failure/AnthropicReplies.kt` 69-143 and
`integrations/upstream/.../failure/OpenAiReplies.kt` 62-119, and which transport failure is an unreachable path in
`integrations/upstream/.../transport/TransportFaults.kt` 43-52; `CHANGELOG.md` 646-660 and 1770.

### 1.4 The idle probe: four timers

| knob | default | what a breach does |
|---|---|---|
| `firstByteTimeoutMs` | 90000 | before Claude Code has seen any content: silence past it asks the path whether it is alive |
| `streamIdleMs` | 90000 | after content began: the same question |
| `stallReanchorMs` | 20000, armed only on heads measured to continue from a prefill | after content began: cancel the round and re-send from what was delivered. `0` turns it off for a head |
| `upstreamTimeoutMs` | 1800000 (30 min), restart to change | the whole-turn wall: no model, tool or reasoning event for this long ends the turn. Keepalive pings do not renew it |

How the probe works: a round past a tier is asked whether its socket still answers. A path that answers is held and polled on. Only a path that has gone quiet too is reaped, and
the re-anchor machinery then resumes it. A turn ended by the wall reads `[splice] <head> made no progress for 30
minutes.` (the figure is the turn's budget) and then `Claude Code sends this turn again.`, and the console says "Given
up after no progress". A turn that never got to start reads `<head> could not start this turn within 30 minutes.`, and
one held to the session's own time limit reads `<head> made no progress before this session's time limit.`
Do not lower `streamIdleMs` to cure a slow model. It buys only how long the person waits before splice starts
healing. A head that needs a longer wait names it in its own overrides, with a reason.

Source: `Knob.kt` (the `UPSTREAM_TIMEOUT_MS`, `FIRST_BYTE_TIMEOUT_MS`, `STREAM_IDLE_MS` and `STALL_REANCHOR_MS` entries);
`integrations/upstream/.../retry/Watchdog.kt` 1-9, 58-70, 179-199; `app/src/main/resources/console/kit.js` 237-259.

### 1.5 Error classes and what the person sees

Claude Code sees one of seven Anthropic error types. splice chooses the type, the HTTP status and the
`x-should-retry` header from the cause and from how far the turn had got (`ClientWire`), and writes the line from the
cause alone (`SpliceLines`). A failure that reaches the transcript reads `[splice] <what happened>. <what happens
next>.`: one or two sentences of at most 240 characters. The first names the head by its launch command (`<head>`
below, for example `claudex`). The second is `Claude Code sends this turn again.` where the answer is one Claude
Code re-sends, and otherwise says what the person does. A provider's own short words follow a colon on a refusal, a
rejected request, a spent account or a failure splice does not recognize, and nowhere else. Its raw JSON never
reaches a line. Times read in the machine's zone, as `Nov 10, 1:05 AM CST`. Once model content has reached the client
the type is the cause's own and Claude Code sends nothing again, so the second sentence is the person's. When the
identical request already ended in an answer Claude Code re-sends, the next such ending is told as a stop: the first
sentence adds `after Claude Code sent this turn again, so this turn stopped`, and the type is `invalid_request_error`,
so the loop ends.

| situation | wire type before content | Requests word | the line the person reads, and what happens next |
|---|---|---|---|
| provider overloaded: 503, 529 or a code that spells an overload | `overloaded_error` | Provider error. Overloaded when the provider sent it inside the stream | `<head> stayed overloaded.` A local model that is still loading reads `<head> is still loading its model.` Then `Claude Code sends this turn again.` The line comes after splice's own waits (1.2, 1.3) |
| provider server error: 500, 502, 504 | `overloaded_error`. After content, `api_error` | Provider error | `<head> kept failing with a server error.` A 504 reads `<head> timed out before answering.` After content they read `<head> kept failing mid-answer with a server error.` and `<head> timed out mid-answer.` A 401 whose body names a key this login never sent is the provider's own fault: `<head>'s provider rejected a key this login never sent, a fault on its side.` Then `Claude Code sends this turn again.`, or `Send the message again.` after content |
| no answer from the provider: the name does not resolve, the connection is refused, the TLS handshake fails, or it closes, resets or times out before any reply | `overloaded_error` | Network error | `<head> could not reach its provider because the address did not resolve.` / `<head> could not open a secure connection to its provider.` / `<head>'s provider refused the connection.` / `<head>'s provider closed the connection before answering.` / `<head> stopped reading the request while splice was sending it.` A model server on this machine reads `<head>'s model server is not running.` or `<head>'s model server closed the connection before answering.` Then `Claude Code sends this turn again.` The line comes after the waits of 1.3 |
| stream cut after content began | after content, `overloaded_error`. An event too large to read: `api_error` | Cut off. Stream error for the too-large event | `<head>'s answer kept getting cut off.` with ` inside a tool call` added when one was open. The too-large event reads `<head> sent a stream event larger than splice accepts.` Then `Send the message again.` splice re-anchors first (1.2). If a tool call had started the turn ends: the person re-sends |
| silence: no first byte in time, or quiet mid-answer, past the idle probe (1.4) | `overloaded_error` | Given up after no progress | `<head> did not start answering in time.` Once content showed, `<head> went silent mid-answer and did not recover.` Then `Claude Code sends this turn again.`, or `Send the message again.` after content |
| the whole-turn wall spent (`upstreamTimeoutMs`, 1.4) | `overloaded_error` | Given up after no progress | `<head> made no progress for 30 minutes.`, `<head> could not start this turn within 30 minutes.` when it never began, or `<head> made no progress before this session's time limit.` when a hold ran to the session's own time limit. Then `Claude Code sends this turn again.` |
| a limit with a stated reset (a spent plan window) that the session can wait out | none: the turn waits | none while it waits | While it waits the turn shows `<head> reached its 5-hour limit. This turn continues in 12 minutes.`, or past an hour `<head> reached its 5-hour limit. This turn waits until Nov 10, 1:05 AM CST and continues then.` Where a probe may find the limit lifted sooner: `<head> reached its 5-hour limit, which resets Nov 10, 1:05 AM CST. This turn waits and continues then, or sooner if the limit lifts.` A limit that names no window says `<head> is at its usage limit` in place of the window. Sessions page: At limit |
| the same limit, when the session's own time limit cannot hold the wait | HTTP 429 `rate_limit_error`, no `x-should-retry` | Plan limit, with the window's lock and reset time. Rate limited when the provider named no plan window | `<head> reached its 5-hour limit, which resets Nov 10, 1:05 AM CST. Claude Code retries at that time.` Claude Code sleeps until the time and sends the turn again. On a head with another login: `Every <head> login is at its usage limit, the first until Nov 10, 1:05 AM CST. Claude Code checks again in 30 seconds and continues on the first login that frees.` |
| a held limit that ended without the turn going on | `invalid_request_error`, `x-should-retry: false`. A held overload ends as `overloaded_error` | Plan limit, or Rate limited | After 7 days: `<head> is still at its usage limit after 7 days, so this turn stopped.` A limit that did not lift at three of the times it stated: `<head>'s usage limit did not lift at the times it stated, so this turn stopped. Its next stated reset is Nov 10, 1:05 AM CST.` A reset more than 7 days away: `<head> reached its weekly limit, which resets Nov 20, 1:05 AM CST. That is longer than a turn can wait, so send the message again then.` |
| burst 429: no reset and no window named | `overloaded_error`. HTTP 429 `rate_limit_error` at admission or without streaming | Rate limited | Between waits, only for a wait of 10 s or more: `<head> is limiting requests for a moment. This turn tries again in 16 seconds.` When the waits (1.3) are spent: `<head> is still limiting requests.` and `Claude Code sends this turn again.` A 429 the head relays as the provider sent it reads Rate limited too |
| a window named spent with no reset, or a billing-cycle limit | `invalid_request_error`, `x-should-retry: false` | Rate limited | `<head> reached its weekly limit and did not say when it resets: <the provider's words>.` Another pooled login takes the turn first (1.3) |
| spent account: any 402, or a 429 naming insufficient quota | held as a limit is; handed over as HTTP 429 `rate_limit_error` | Rate limited | While held: `<head>'s xAI account cannot pay for this turn: <the provider's words>. Add credit with xAI and this turn continues by itself.` A subscription reads `<head>'s xAI subscription is not active. Renew it with xAI and this turn continues by itself.` Handed to Claude Code, the first sentence stays and the second is `Claude Code sends this turn again.` A head with no provider to name reads `<head>'s account cannot pay for this turn` |
| splice holds no credential | HTTP 403 `permission_error`, `x-should-retry: false` | Signed out. A turn refused at admission for it reads Credentials refused | `<head> has no login yet.` `Run <the head's login command>, then send the message again.` (`Sign <head> in, then send the message again.` where the head has no login command.) Nothing to retry. The person signs in (6.3) |
| the provider rejected a login splice holds, after a refresh | HTTP 403 `permission_error`, `x-should-retry: false` | Provider error. Credentials refused when it arrived inside the stream | `<head>'s login expired and could not be renewed.` `Run <the head's login command>, then send the message again.` splice renewed it once first (2 s and 4 s apart while the token endpoint could not answer), then moved the turn to another login if one was free. The person re-logs in (6.3) |
| the provider rejected the login Claude Code forwarded | HTTP 401 `authentication_error`, `x-should-retry: false` | as the row above | `<provider or head> did not accept this session's login.` `Run /login, then send the message again.` splice never renews a login it only forwards |
| the provider refuses this request: a 403, a rejected 400, a refusal, a content filter, a model it does not serve | HTTP 400 `invalid_request_error`, `x-should-retry: false` | Provider error. Invalid request when the verdict arrived inside the stream | `<head>'s account may not use this model: <the provider's words>.` / `<head> rejected the request as sent: <the provider's words>.` / `<head> could not use the earlier reasoning in this conversation: <the provider's words>.` / `The model on <head> declined to answer: <the provider's words>.` / `<head> declined this request under its usage policy: <the provider's words>.` / `<head>'s content filter stopped this answer: <the provider's words>.` / `<head> does not serve <model>.` A model server on this machine adds `Load it in the model server, then send the message again.` A login its provider blocked until its owner accepts new terms reads `<head>'s login <label> is blocked until its owner accepts updated terms.` `Accept them in <where> with that account, then send the message again.` Claude Code sends none of them again |
| context overflow | HTTP 400 `invalid_request_error`, `x-should-retry: false` | Provider error. A request the size preflight refused reads off its tag, for example Compaction preflight compactable | `This prompt is too long for <head>.` and, where the window is known, `Its model holds 262,144 tokens.` That wording makes Claude Code compact. A compaction that is itself too long reads `This conversation is too long for <head> to summarize`, a first message, which cannot be compacted, `This first message is too long for <head>`, and an answer that filled the window `The answer filled <head>'s context window before it finished, so the prompt is too long to continue` |
| a failure no table names | a 4xx the provider answered: HTTP 400 `invalid_request_error`. Any other: `overloaded_error` | Provider error | `<head> refused this turn in a way splice does not recognize: <the provider's words>.` for the 4xx, not re-sent. `<head> failed in a way splice does not recognize: <the provider's words>.` and `Claude Code sends this turn again.` for the rest. splice sent it once more first (1.3) |
| the model finished with nothing, or its tool call broke off | `overloaded_error`. A tool call that broke off: `api_error` after content | read off the tag (unverified) | `<head> finished without an answer.` or `<head> finished thinking without an answer.` splice asks again once first. A broken tool call reads `<head>'s answer broke off inside a tool call.` or `<head>'s answer grew larger than splice can hold.` Then `Claude Code sends this turn again.` |
| over a daily budget set to block | HTTP 400 `invalid_request_error`, `x-should-retry: false` | Over budget | `<head> reached its daily budget of $2.00, which resets Oct 27, 12:00 AM CDT.` `Send the message again after that time.` Raise the budget or wait |
| head queue full | HTTP 529 `overloaded_error`, reason phrase `Gateway At Capacity` | Too many at once | `<head> is already running 12 turns, its limit.` `Claude Code sends this turn again.` The figure is the head's `maxInflight` (12 by default). `maxQueued` (512) turns wait before this |
| splice refuses the request before sending it | HTTP 400 `invalid_request_error`, `x-should-retry: false`. A body over the limit: HTTP 413 | Invalid request or Refused (unverified which) | `This request is 40 MB, more than the 32 MB splice accepts.` / `This request reached splice incomplete.` / `splice could not parse this request.` / `<head> does not serve <model>.` / `<head> cannot carry the output format this request asks for, so the turn was not sent.` The last has one wording for each control a head's provider cannot carry (structured output, tool choice, top_k, stop sequences, service tier, cache-control breakpoints, strict tool calling, a request field it does not recognize, and the rest). `This request needs more memory than splice can give one request.` is final. `splice is short of memory right now.` (HTTP 529) and `This request took too long to reach splice.` (HTTP 408) are re-sent |
| daemon restarting | no answer: the request waits for the next daemon | none | the next daemon answers it (1.7) |
| a turn running when the daemon restarts, or when its head stops | `overloaded_error` | splice restarted | `splice restarted during this turn.` or `<head> is shutting down.` Then `Claude Code sends this turn again.` Stopped from the console: `This turn was stopped from the splice console.`, `invalid_request_error`, not re-sent |
| a compaction, or a held non-stream turn, whose answer never finished | `overloaded_error` | read off the tag (unverified) | `The summary this request was waiting on did not finish on <head>.` For a held turn that is no compaction, `The answer this request was waiting on did not finish on <head>.` Then `Claude Code sends this turn again.` |
| cause splice cannot name | `overloaded_error`. An exception that escaped the turn's own handling: `api_error` | Internal error | `This turn failed because of a fault in splice.` Then `Claude Code sends this turn again.` Read `splice logs` |
| code mode failure | `invalid_request_error`, except a runtime that did not start, which is re-sent | read off the tag (unverified) | `<head>'s code-mode runtime did not start.` / `<head>'s code-mode run lost its state, so this turn stopped.` / `<head> sent something splice cannot translate, so this turn stopped.` State that cannot be saved or read says why: `<head>'s code-mode history reached its storage limit, so this turn stopped.`, or the history is invalid, the state directory is not safely accessible, storage I/O failed, the state disk is full, or memory was unavailable. 3.3 |
| local 401: the session's splice key is wrong, or the head serves its logins only to sessions splice started | HTTP 401 `authentication_error` | read off the tag (unverified) | `This session's splice key is not valid.` `Start the session again from splice.` Or `<head> serves the logins it holds only to sessions splice started, and this session was started another way.` `Start this session again with the head's own command, such as <head> --resume.` Or, for splice's own key sent where the login belongs, `This session sent splice's own key where your own login belongs.` `Unset <the variables> in the shell that launched it, then start the session again.` In every case relaunch the session |

The Requests word is read off the outcome tag (`kit.js` `OUTCOME_WORD`), not off the line. `error:upstream-failed` (the
retry loop gave up on an answer the provider sent) reads Provider error. `error:rate-limited` and `error:rate-limit-relayed`
read Rate limited, `error:plan-limit` Plan limit, `error:conn-reset` Network error, `error:turn-cap` and a stalled
stream Given up after no progress, a cut stream Cut off, `error:upstream-frame-too-large` Stream error,
`error:at-capacity` Too many at once, `error:budget-blocked` Over budget, `error:auth-missing` Signed out,
`error:restarted` splice restarted, `error:unexpected` Internal error and `empty_model` Empty answer. A turn that ended
on a type inside the stream is tagged `failure:<type>` and reads Overloaded, Rate limited, Invalid request,
Credentials refused, No access, Not found or Provider error. A tag with no word is read off its name.

`error:all-accounts-exhausted` still has a word, No quota, but I found no Kotlin code that writes it. A head whose logins
are all spent selects the login that resets first, and its turn is held or handed over as the rows above say
(unverified: `LocalRefusal.refuseNoLogin` says selection fails only for an unusable credential or an owner action
being probed). The row 1.3 gives for "every account of a head exhausted" may be stale for the same reason.

Source: `core/.../failure/ClientWire.kt` 18-104 (`WireAnswer`, `answer`), 177-237 (`Kept`) and 243-305 (`OwnTypes`);
`core/.../failure/policy/FailureFamilies.kt` 12-72; `core/.../failure/FailureCause.kt` 15-266; `core/.../turn/TurnOutcome.kt`
17-25; `core/.../failure/SpliceLine.kt` 6-14 and `SpliceLines.kt` 65-163 (`ending`, `waiting`, `fit`); the sentences in
`ProviderSentences.kt` 11-171, `PathSentences.kt` 11-68, `SpliceSentences.kt` 11-272, `HoldSentences.kt` 21-169,
`AccountSentences.kt` 9-20 and `CodeModeStorageSentences.kt` 5-31, all under `core/.../failure/`; the time and size
wording in `Spoken.kt` 30-104; which waits are told in `features/turns/.../hold/TurnWaitsAtHead.kt` 36-39 and
`RetryBudgets.kt` 13-15; `features/turns/.../admission/AdmissionResponses.kt` 77-117 and `AdmissionGate.kt` 79-86;
`HeadBudgetTest.kt` 83-118 (the budget line, status and tag); `features/turns/.../turn/TurnKnownEnd.kt` 83-107 and
`features/turns/.../pipeline/TurnPipeline.kt` 47-70 (which tag an ending writes); `core/.../perf/OutcomeTag.kt`;
`app/src/main/resources/console/kit.js` 191-262 and `requests.js` 63-77 (the words); `docs/specs/v0.4.0.md` line 584;
`CHANGELOG.md` 1208-1210.

### 1.6 The knobs, and where they live in splice.toml

A knob sits in `[defaults]` (every head), in `[heads.<key>.overrides]` (one head), or in a `[daemon]` key spelled
with underscores. Values are quoted strings, for example `maxInflight = "4"`. One daemon serves every head and
shares one config service, so a limit that belongs to one upstream account (`maxInflight`, `maxQueued`, the timers)
belongs under that head's `[heads.<key>.overrides]`. The field syntax is in `toml-reference.md`.

| key | default | change takes effect |
|---|---|---|
| `firstByteTimeoutMs`, `streamIdleMs`, `stallReanchorMs` | 90000, 90000, 20000 | next turn (hot) |
| `upstreamTimeoutMs` | 1800000 | restart |
| `maxInflight`, `maxQueued` | 12, 512 (0 = unlimited) | next admission (hot) |
| `progressLine` | true | next heartbeat (hot) |
| `maxRequestBytes`, `requestReadTimeoutMs`, `ingressReadIdleMs` | 33554432, 30000, 30000 | hot |

`upstreamRetries`, `retryBackoffBaseMs`, `retryBackoffCapMs`, `retryBackoffJitterPct` and the four `fold*` knobs
were retired in 0.4.0. splice refuses to start while splice.toml, an environment variable (`CLAUDEX_UPSTREAM_RETRIES`,
`SPLICE_RETRY_BACKOFF_*`, `CLAUDEX_FOLD_*`) or a saved setting still sets one, lists every place at once, and
`splice restart` on a terminal offers to remove the lines and clear the saved settings; a variable is unset where it is
set.

"Hot" means a `PATCH /api/config` (the console's Settings page) or the state `config.json` reaches the running
daemon. An edit of `splice.toml` is read only at boot, so any key changed there needs `splice restart`, whatever
its marking. The exceptions are `context_window` edits, `[daemon] mcp_share`, `update_channel`, `auto_update`
and the `[compaction]` tables, which the daemon re-reads from the file.

The example file's comment at `splice.example.toml` line 867 shows `maxInflight = "0"`. That is stale. The code
default is 12. Reasoning effort is restart-only on purpose: it is part of the prompt-cache key.

Source: `Knob.kt` (the header comment on which knobs are live, and the `MAX_INFLIGHT` through `STALL_REANCHOR_MS` entries);
`app/src/main/resources/splice.example.toml` lines 3-7, 831-841, 865-940; `AGENTS.md` lines 103-127; `CHANGELOG.md` 261-266.

### 1.7 What a restart cuts, and how sessions recover

- There is one daemon JVM. `splice restart` cuts the turns in flight on every head and every session on the
  machine, the manager's own included.
- `splice restart` first waits for running compactions, up to 900 s, unless `--now`. Then the daemon ends every
  ordinary turn still running at once, each with the line "splice restarted during this turn.", and the next
  daemon takes over.
- systemd holds splice's ports (`splice-control.socket`, `splice-head-<key>.socket`, laid by `splice restart`), so a
  request that arrives during the restart waits in the kernel queue and the next daemon answers it. A head on its
  own port, with no such socket, closes the connection instead, and Claude Code retries on a new one.
- Claude Code does not retry a turn that was cut after its text began, so a restart can cost the person that message: the line asks them to send it again. A buffered turn's 529 is one Claude Code re-sends itself.
- Run `splice restart` in the background, never in the foreground. Never run `splice logs --follow`.
- A finished compaction whose client hung up is kept on disk for two hours, so the retry gets the same bytes.
- Code mode scripts in flight are lost and never rerun (3.3).
- MCP: with the hub, session tools survive a daemon restart. Hosted in the daemon (no hub), a restart is a gap of
  about 10 s, and Claude Code 2.1.296 gave up on an MCP server after about 15 to 17 s without it, so a session
  may need `/mcp` to reconnect. The 10 s figure is from `docs/specs/mcp-hub.md` proof (c); I did not re-measure.
- A restart run from the manager's pane starts the daemon with the manager's environment, and an API key exported
  elsewhere is not in it. Prefer the console's Restart button or the person's own terminal.
- A session that stays stuck after a restart is a splice defect to report with the `splice logs` lines. Do not
  work around it.

Source: `features/lifecycle/.../restart/RestartCommand.kt` header; `features/lifecycle/.../restart/RestartAfterCompactions.kt`
header; `features/lifecycle/.../start/DaemonLaunch.kt` line 157; `README.md` lines 374, 1018-1022; `CHANGELOG.md` 164-168,
1208-1210; `docs/specs/mcp-hub.md` lines 61-65, 230-235.

## 2. MCP: the host and the hub

### 2.1 What they are

| part | what it does |
|---|---|
| MCP host | In the daemon. Starts each shareable stdio MCP server once and serves it to every session over Streamable HTTP on loopback, at `/mcp/<name>` on the control port. Servers start on first use and stop when idle. |
| MCP hub | Linux x86-64 with `systemd --user`. A small native front (`splice-mcp-front`, holds `127.0.0.1:3095`) and one native holder (`splice-mcp-holder`) per server, in `app-mcp.slice`. The front outlives the daemon, so an upgrade, restart or crash of splice drops no session's tools. |
| launch file | Each launch hands Claude Code one owner-only `--mcp-config` file, `<state>/mcp-hub/launch-<pid>.json`, naming each server under its own name, so tool names do not change. |

The daemon decides once at boot whether the hub runs. Three checks, in order: Linux x86-64; both binaries
beside `splice.jar` and executable; `systemctl --user is-system-running` answers `running` or `degraded` within
2 s. The first failure is the logged reason, published on `/health` as `mcpHub` and `mcpHubOffReason`. Off means
the host runs in the daemon, the supported fallback. A launch offers hub addresses only after a front has run
here (`<state>/mcp-hub/front.json` exists) and while it answers on 3095. Otherwise the session prints `this
session starts its own MCP servers` and Claude Code runs its own.

`docs/specs/mcp-hub.md` is the approved design plus desk proofs on Claude Code 2.1.296. Read it for the why.
Where it says "will", trust the code and `CHANGELOG.md` 160-186. The hub port 3095 is a constant in the code.

Source: `README.md` 809-832; `CHANGELOG.md` 232-258; `integrations/mcp/.../hub/McpHubGate.kt` 23-52;
`app/.../McpHubWiring.kt` 100-111; `app/.../ControlLaunchWiring.kt` 43; `features/diagnostics/.../DoctorClientVersion.kt` 28-62;
`integrations/claude-code/.../mcp/McpHubLaunch.kt` 40.

### 2.2 How a tool server is attached, and how to add one

splice.toml has no table that declares an MCP server (the only MCP keys in the topology are in 2.3). Servers
come from Claude Code's own configuration, and splice hosts what it finds. So "add an MCP server" is a Claude
Code change, not a splice change.

| where a server is declared | what splice does with it |
|---|---|
| user scope, `mcpServers` in `~/.claude.json` | hosted. Each sharing head's own `.claude.json` copy is rewritten to the hosted URL. The person's file is never edited |
| project override inside `.claude.json`, repo `.mcp.json`, plugin `.mcp.json` or inline `plugin.json` | censused on `/api/mcp` (`sources`), not rewritten. With the hub, the launch file routes them through it. A repo server pending or rejected in Claude Code is not hosted |
| `http`, `sse`, `ws` servers and claude.ai connectors | left to Claude Code, untouched |

A server stays one copy per session (reason on `/api/mcp`) when its launch line names a working directory, a
relative path, a location flag such as `--root`, an existing absolute directory or `${VAR}`; when it asks its
client for roots; or when it keeps state per process (Desktop Commander). A hosted server runs in the home
directory, so one that reads its working directory without naming it belongs in `mcp_hosting_exclude`.

To add a server, the person runs Claude Code's own command (`claude mcp add`; its `--help` lists the scopes).
Scope `user` reaches every session. Then the server appears under Settings > MCP once a session starts.
Never read `~/.claude.json` into the conversation: server entries often carry API keys in `env`.

Source: `app/.../McpHostingWiring.kt` 53-94; `README.md` 815-832, 675; `core/.../topology/Topology.kt` 140-148;
`app/.../control/mount/McpMount.kt` 32-77; `docs/specs/mcp-hub.md` lines 22-48.

### 2.3 The keys that govern hosting

| key | default | note |
|---|---|---|
| `[daemon] mcp_hosting` | true (absent = on) | `false`: every session starts its own servers. Boot-read: needs `splice restart` (unverified: not listed among the live keys) |
| `[daemon] mcp_hosting_exclude = ["name"]` | none | keep these servers per session. Boot-read (unverified, as above) |
| `[daemon] mcp_share` | true (absent = on) | `true`: one shared copy per server. `false`: one per session. Read live, applies to new connections. `splice setup` asks once and writes it |
| `[defaults] mcpIdleTimeoutMs` | 1800000 | a shared server with no open stream stops after this. Hot |
| `[defaults] mcpMaxServers` | 32 | most servers hosted at once. Past it the longest-idle goes; if all are busy: "MCP host at capacity". Hot |
| `[defaults] mcpRequestTimeoutMs`, `mcpInitializeTimeoutMs` | 1800000, 60000 | hot |
| `[defaults] mcpSlice` | `app-mcp.slice` | the systemd slice servers spawn into. Restart |

Source: `core/.../topology/Topology.kt` 140-148; `Knob.kt` (the shared MCP hosting entries and `MCP_SLICE`); `app/.../McpHostingWiring.kt` 36-48;
`app/src/main/resources/splice.example.toml` lines 3-7, 29-42, 951-955; `README.md` 818-822, 847-852.

### 2.4 The hub's files

| file | what it is |
|---|---|
| `~/.local/share/splice/splice-mcp-front`, `splice-mcp-holder` | the two programs, beside `splice.jar`. `install.sh` and `splice upgrade` swap them in with the jar. A running front reloads (`systemctl --user reload splice-mcp-front.service`), never restarts |
| `~/.config/systemd/user/app-mcp.slice`, `splice-mcp-front.socket`, `splice-mcp-front.service` | the units. `splice install --all` writes each only where no unit of that name exists. It enables and starts nothing |
| `~/.config/splice/mcp-front.env` | where the hub keeps state and the control port. `splice init` writes it (Linux) |
| `<state>/mcp-hub/` | `front.json`, `access-key` (the MCP-only bearer, derived from the management key), `launch-<pid>.json`, `units-written` (SHA-256 of the units splice wrote), `plugin-flag.json`, one `<key>.json` and `.sock` per holder |
| `<state>/mcp-listings/`, `<state>/mcp-roots-bound` | the last tool list each server gave, so a session lists tools without starting the server; the servers that asked for roots |

`splice setup` with sharing on enables and starts `splice-mcp-front.socket`, but only over units splice itself
wrote. Otherwise setup prints the command. Until the socket is on, each session starts its own servers.

Source: `README.md` 659-661, 675-688, 743; `CHANGELOG.md` 154-156, 243-258; `app/.../InstallWiring.kt` 28-111;
`install.sh` 504-510.

### 2.5 See an MCP server's state

1. `splice doctor`. The `MCP hub` row reads one of: on; installed but its front never ran here (fix:
   `systemctl --user enable --now splice-mcp-front.socket`); front not answering on 127.0.0.1:3095 (fix:
   `systemctl --user status splice-mcp-front.socket splice-mcp-front.service`); off with the reason.
2. Settings > MCP in the console: the sharing choice, what sharing saves in memory, and each server's copies,
   reason and sessions using it. These are the keyed routes `GET /api/mcp` and `GET /api/mcp/memory`. You have no
   key, but you can open and read that page on the person's console: `splice console goto settings`, then
   `snapshot`, tap "MCP servers" in the rail, and `read`.
3. `splice logs --tail 300` and read the `[mcp-hub]` and `[mcp-host]` lines.

### 2.6 Recipes

Keep one server per session:

1. Copy `splice.toml` to a dated backup beside it.
2. Under `[daemon]`, add or extend `mcp_hosting_exclude = ["<server name>"]`.
3. `splice check-config`. Show the diff.
4. With a yes, the person runs `splice restart`. New sessions start their own copy.
5. Verify: Settings > MCP shows the server with its own copy for each new session, and `splice doctor` has no new finding.

## 3. Code mode

### 3.1 What it is

For a ChatGPT-shaped head, the model writes a short JavaScript program that batches Claude Code tool calls,
instead of one call per round trip. The script runs inside splice, in a bundled GraalJS runner (no Node, no
Codex install). Claude Code's permission-checked handlers still run every real operation. The session shows the
model's reasoning summaries and each tool call, never the program. An eligible turn declares one tool, `exec`.

### 3.2 How it is turned on

On by default for providers with `auth.kind = "chatgpt-oauth"` and `dialect = "openai-responses"`, including
custom head names. Nothing else can enable it: `code_mode = true` on any other shape is a config finding and
boot refuses with `code_mode is only supported with ...`. Eligible models: every model the Codex backend marks
`tool_mode = "code_mode_only"`, plus the ids in `code_mode_models`.

```toml
[providers.codex.quirks]
code_mode = false          # off for every head on this provider
# code_mode_models = ["codex-auto-review"]
```

The setting is read only at boot and is shared by every head on the provider. Finish code-mode work, edit,
`splice check-config`, then the person runs `splice restart`. A head restart alone does not reload TOML.

### 3.3 Limits

| limit | value |
|---|---|
| host JVMs per head `code_mode_workers` | 6, four session engines per host |
| host heap `code_mode_heap` | 512 MiB (a session's guest heap 384 MiB) |
| head-wide admission reservation `code_mode_memory_mb` | 24576 MiB; not an OS memory limit. The default admits 21 sessions |
| tool calls per script cell | at most 32, sent in batches of at most 8 |
| one tool result | 64 KiB, cut with `[truncated N chars]` |
| output ceiling | 1,048,576 characters |
| guest access | no host or file access |
| idle engine | evicted after 1 minute. A conversation's journal expires 24 h after last use |

A script that fails shows the model its evidence (results so far, unresolved calls, the reason). Over capacity, admission returns an `exec` result asking the model to retry after a running script finishes. A restart or a dead host fails only its own cells and splice
never reruns lost source. `keepCodeModeWork = "false"` keeps the journal in memory only, so a restart ends running
scripts. A script splice cannot match to the conversation falls back to the client's own history, with one
`[code-mode]` log line.

Source: `README.md` 911-959; `core/.../topology/QuirksConfig.kt` 70-90; `core/.../topology/Topology.kt` 222-247;
`app/src/main/resources/splice.example.toml` 145-169; `Knob.kt` (the `KEEP_CODE_MODE_WORK` entry).

## 4. Architecture brief

### 4.1 One turn, in plain words

1. The person starts a head's command, for example `claudex`. It is a symlink to the launch shim `splice-launch`.
2. The shim checks the daemon's version, cold-starts the daemon if down, asks it for a launch recipe over
   loopback, and execs the real `claude` with the recipe's environment. That environment carries
   `ANTHROPIC_BASE_URL` (the head's port), a derived turn key, `CLAUDE_CONFIG_DIR=~/.claude-<head>`,
   `API_TIMEOUT_MS`, `API_FORCE_IDLE_TIMEOUT=0`, `CLAUDE_CODE_RETRY_WATCHDOG=1`, `CLAUDE_CODE_NONSTREAMING_TIMEOUT_RETRIES=2` and the model window variables.
3. Claude Code posts `POST /v1/messages` to the head's loopback port. The head checks the key, admits the turn
   (`maxInflight`, `maxQueued`) and applies the standing prompt layers.
4. The provider module builds the request in the provider's dialect: `anthropic-passthrough`,
   `openai-chat` or `openai-responses`, with the auth kind's credential.
5. The upstream client sends it under the retry rules of section 1.
6. The stream is translated back into Anthropic SSE events. Only one module may end a stream cleanly, and a
   failed or cut stream is never shown as a clean answer.
7. Perf rows, the trace and usage are written to the state dir.

Source: `app/src/main/dist/bin/splice-launch` header; `README.md` 998-1016; `features/launch/.../recipe/LaunchService.kt`
339-392 (`buildEnv`); `features/turns/.../head/HeadServer.kt` 1-18; `AGENTS.md` lines 35-43 (honest failures).

### 4.2 The processes

| process | what it is |
|---|---|
| daemon (`spliced`, `java -jar splice.jar`) | one JVM. Every head runs inside it, each on its own loopback port. It also serves the control plane and the console |
| control plane | one Ktor server on `127.0.0.1:<control_port>`, default 3096. `GET /health` is open. Every `/api/*` route needs the management key. It refuses a `Host` other than `127.0.0.1`, `localhost`, `[::1]`. The console pages are served at `/` |
| head | a loopback listener per `[heads.<key>]`, serving `/v1/messages`, `/v1/models`, `/health` |
| launch shim `splice-launch` | POSIX `sh` and `curl`, no Node. Version-locked to the jar |
| head wrappers | `~/.local/bin/<command>` symlinks to the shim. The command's name selects the head. `splice` itself is one |
| hub front, holders | section 2 |

The control plane is `app/src/main/kotlin/splice/app/control/ControlServer.kt`. No Kotlin route serves `/mgmt`: the
live surface is `/api/*` on the control plane, and a head serves only `/health`, `/v1/models`, `/wire`, `/v1/messages`
and `/v1/messages/count_tokens`. AGENTS.md says the same (Management API and Control plane sections).
Source: `ControlServer.kt` 1-8, 211-229, 305-336; `features/turns/.../head/HeadEngine.kt` 171-204; `AGENTS.md` lines 85-127, 137-176.

### 4.3 The modules

- `core/`: the shared kernel (config, topology, auth, turn, model, usage). No framework.
- `integrations/`: adapters. `upstream` (transport and retry), `dialects/*`, `providers/*`, `oauth`, `topology`
  (the loader), `mcp` (host), `mcp-hub` (native front and holder), `codemode`, `claude-code`, `tmux`, `terminal`.
- `features/`: capabilities (`turns`, `sessions`, `models`, `heads`, `accounts`, `usage`, `lifecycle`,
  `diagnostics`, `launch`, `configuration`). A slice reaches another only through its public ports.
- `app/`: composition. The daemon, the control plane, the `splice` CLI, the fat jar, the launch shim and
  `splice.example.toml`.
- `quality/`: architecture laws. The module graph is law (`gradle/module-law.txt`).

Source: `README.md` 1054-1081; `gradle/module-law.txt`; `settings.gradle.kts`.

### 4.4 Where state lives

| what | where |
|---|---|
| config dir (the manager's `manager/` is inside it) | the folder of `splice.toml`: `SPLICE_CONFIG`, else `$XDG_CONFIG_HOME/splice`, else `~/.config/splice`. Holds `splice.toml`, `keys.toml`, `key-ledger.json`, `auth/`, `prompts/`, `mcp-front.env`, `manager/` |
| state dir | `SPLICE_STATE_DIR` (or the older `CLAUDEX_STATE_DIR`), else `[daemon] state_dir`, else `~/.splice/state`. An install older than 0.4 keeps `~/.claude-codex/state` and splice adopts it in place. Holds `mgmt-key`, `config.json`, `trace/`, perf and usage files, `teams.json`, `mcp-hub/`, `config-backups/` |
| logs dir | the sibling `logs` of the state dir: `~/.splice/logs`, or `~/.claude-codex/logs` when adopted. `daemon.log`, `daemon-boot.log` |
| install dir | `~/.local/share/splice` (`SPLICE_SHARE_DIR`): `splice.jar`, `splice-launch`, `releases/`, `managed-install`, hub programs |
| bin dir | `~/.local/bin` (`SPLICE_BIN_DIR`): `splice` and one symlink per head command |
| Claude Code config per head | `~/.claude-<head>/` |

The manager reads nothing in the state dir by hand: it holds `mgmt-key` and `console-open.html`, which carries the management key. Use `splice logs`, `splice status` and `splice doctor` instead. The paths here are for explanation.

The example file's `state_dir` comment (lines 15-17) now says the same default, `~/.splice/state` (`StatePaths.kt` 202); logs sit in the folder beside it. Stale docs on this: `README.md` says other keys are read only at boot, but the code re-reads `mcp_share`, `update_channel`, `auto_update` and the `[compaction]` tables live.

On this machine the state dir is `~/.claude-codex/state`, so the logs are in `~/.claude-codex/logs`. Run
`splice doctor`: it reports which root is live and warns about a root it could not read.

Source: `core/.../config/StatePaths.kt` 45-55, 153-258; `core/.../topology/Topology.kt` 130;
`app/src/main/resources/splice.example.toml` 13-17, 1041-1045; `README.md` 562-574, 732-740.

## 5. Install, upgrade, rollback

### 5.1 What `install.sh` does, in order

1. Checks the platform (Linux, macOS, WSL2) and dependencies. Java 21+ is the one hard requirement. Everything
   else is reported with its fix and never aborts the install.
2. Gets the jar: `SPLICE_JAR=/path`, else builds from a checkout, else downloads the release from splice.torad.ai (`SPLICE_VERSION=vX.Y.Z`
   pins one; `SPLICE_RELEASE_BASE_URL` points elsewhere). Checks it against `sha256sums.txt`, and the build
   attestation when `gh` is signed in. On Linux x86-64 it also gets the hub pair the same way.
3. Refuses a launcher whose version does not match the jar.
4. Copies the old jar and shim to temporary backups, then replaces them atomically.
5. Runs `java -jar splice.jar init`, then `install --all`. If either fails, it rolls back to the old pair.
6. Reloads a running hub front.
7. Checks that `~/.local/bin/splice` exists, writes `managed-install`, keeps `releases/<version>/`, sets
   `current` and `previous`, and deletes its temporary backups.
8. Runs `splice doctor`. Findings are next steps, not failures. Then: `splice setup`.

A daemon that is already running keeps the old build until `splice restart`. The installer does not restart it.
Env: `SPLICE_SHARE_DIR`, `SPLICE_BIN_DIR`, `SPLICE_JAR`, `SPLICE_SHIM`, `SPLICE_VERSION`, `SPLICE_RELEASE_BASE_URL`.

Source: `install.sh` lines 7-16, 93-147, 273-352, 397-417, 488-521, 569-592.

### 5.2 `init`, `install --all`, `setup`, `doctor`

| verb | does |
|---|---|
| `splice init` | writes the starter `splice.toml` only if absent, on Linux writes `mcp-front.env`, and places the manager's folder (`CLAUDE.md`, `.claude/settings.json`, the skill) in `manager/` beside `splice.toml`. A file the person edited is kept as they left it. The daemon does the same at every start, because auto-update swaps the jar and never reruns init |
| `splice install [<head>\|--all]` | links the wrapper symlinks in `~/.local/bin`, and on Linux writes the hub units where none exist. Never prunes a link |
| `splice setup` | guided, needs a terminal: first provider, which commands, plain `claude` or `claude-splice`, MCP sharing. Writes nothing until confirmed |
| `splice doctor [--live] [--json [--with-logs] [--out FILE]]` | seven sections: prerequisites, installation, configuration, daemon, auth, accounts, runtime. Every failing check prints its fix. `--live` sends one tiny tool call per local-runtime row and nothing else. `--json` is a redacted, shareable report |
| `splice check-config` | the boot findings pass, no daemon. Exit 0 clean, exit 3 on any finding, all listed |

Source: `app/.../cli/CommandParser.kt` 77-201; `app/.../InstallWiring.kt` 92-111; `app/.../manager/ManagerHome.kt` 1-10; `features/diagnostics/.../doctor/DoctorCommand.kt`
64-75, 239-251; `app/.../cli/ConfigPreflight.kt` 21-51; `README.md` 160, 352-360.

### 5.3 Upgrade, auto-update and the beta channel

| command | does |
|---|---|
| `splice upgrade` | fetch the latest release, check it against its sums, stage it under `releases/`, wait up to 30 min for in-flight turns, repoint the live jar, restart, run doctor |
| `splice upgrade --to vX.Y.Z` | one named version |
| `splice upgrade --now` | skip the waits |
| `splice upgrade --rollback` | put the previous release back. Refuses if that release would refuse your config |
| `splice upgrade --check` | read the newest release on your channel and say so (found in the code; the parser's help line omits it) |

Before anything stops, the new release's own `doctor` must answer and its `check-config` must accept your
config. A release that comes up unhealthy or red is put back and listed in `<state>/upgrade-rejected`. Config and
credentials are never touched. An upgrade does not relaunch sessions.

Auto-update is two keys under `[daemon]`, read live at each check:

```toml
[daemon]
update_channel = "beta"   # "stable" (default) follows releases; "beta" follows prereleases too
auto_update = true        # absent reads as off
```

The first check is 10 min after daemon start, then hourly. It runs the same upgrade with `--auto`: assets are
checked against `sha256sums.txt` only (a beta built while CI is locked has no attestation), and it restarts at
the first moment no turn is in flight, or after the bounded wait (30 min). It acts only where `managed-install`
exists. One try per version per daemon. Any other value of `update_channel` is a config finding.

Source: `features/lifecycle/.../upgrade/UpgradeArgs.kt` 10-60; `UpgradeAuto.kt` 1-70; `UpgradeLayout.kt` 28-62;
`UpgradeCommand.kt` 44-48; `core/.../topology/Topology.kt` 146-148; `TopologyWriter.kt` 386-391; `README.md` 104-120,
738-740; `app/src/main/resources/splice.example.toml` 37-42.

### 5.4 Backup and rollback

- Code-supported rollback: `splice upgrade --rollback` (needs `releases/previous`; `install.sh` sets it).
  Or re-run the installer pinned to the older tag: `curl -fsSL <release>/install.sh | env SPLICE_VERSION=vX.Y.Z bash`.
- A failed `install.sh` restores the old jar and shim by itself. If it cannot, it keeps the backups and prints the
  commands that finish the restore.
- Operator convention, not in code or the README: copy the jar to a dated name before replacing it by hand,
  `cp -p ~/.local/share/splice/splice.jar ~/.local/share/splice/splice.jar.bak-<date>`. The person's own
  `splice.jar.bak-<stamp>-pre-<sha>` files on this machine follow it. Offer it; do not assume it exists.
- `splice.toml` backups: console edits keep the ten newest in `<state>/config-backups/`. `splice add` keeps none.
  A hand copy beside the file is the manager's convention (`operations.md`).

Source: `install.sh` 422-470, 529-569; `README.md` 114, 662-663; `UpgradeActivation.kt` 1-45.

### 5.5 Uninstall

`splice uninstall [<head>|--all]` removes the wrapper symlinks. `--all` includes `splice` itself. It refuses
a name that is not a bare link under `~/.local/bin`. On Linux, `--all` (or no argument) also stops, disables and
removes every systemd user unit splice laid in `~/.local/share/systemd/user`, each one known by its
`# splice-owned unit` first line: the daemon's, the MCP hub's and the listener sockets, sockets first so no request
starts the daemon again. That stops the daemon. A unit of the same name in `~/.config/systemd/user` is the person's
override and stays, running; the command names it. One head's uninstall leaves the units, since splice.toml still
names the head. It never touches the jar, config, keys or state. Removing those is the person's own decision and
needs their explicit yes, with a backup, per `operations.md`.
Source: `features/launch/.../install/UninstallCommand.kt`, `features/lifecycle/.../units/UninstallUnits.kt`.

## 6. Logs and the troubleshooting playbook

### 6.1 Where to look

| source | how |
|---|---|
| daemon log | `splice logs [--head <key>] [--tail N]`. Default 50 lines. Works with the daemon stopped, needs no key. Reads `<logs>/daemon.log` (rotates at 64 MB to `daemon.log.1`) |
| boot crashes | `<logs>/daemon-boot.log`: JVM output when splice started the daemon. Under a `splice.service` unit it is in the systemd journal instead |
| status | `splice status`: one row per head, daemon line first |
| sessions | `splice sessions`: each session, its head, live / stale / gone |
| findings | `splice doctor`, `splice check-config` |
| requests | the console's Requests page, outcome words from 1.5 |
| rates and failures | `splice perf --window 1h\|24h\|7d` |

Never run `splice logs --follow` (it never returns). `splice trace` and `splice wire` print conversation
content: ask first. A log line can quote up to 200 characters of an upstream error body. Never paste a key you
find in output.

Source: `features/diagnostics/.../logs/LogsCommand.kt` 1-45, 163-169; `README.md` 600-601, 362-365;
`app/.../cli/status/StatusCommand.kt` 41-72.

### 6.2 What `splice status` says

First line: `daemon running on <port>`, or `daemon stopped (starts on first launch)`, which is normal and not an error.
Per head, the last column reads `ready` (or `ready (your login)`); `splice install` (wrapper link missing);
`<command> login` (no credential); `out of quota until <time>`; `runtime not answering on <endpoint>` (a local
model server is down); or `not running: <reason>` (the daemon could not build or bind the head).
Source: `StatusCommand.kt` 56-72; `StatusTable.kt` 110-171.

### 6.3 Playbook

"Ask" means the manager asks the person first, per `operations.md`. "Person types" means the person runs it in
their own terminal; you type nothing secret.

| symptom | check | fix |
|---|---|---|
| A head will not boot, or the whole daemon refuses to start | `splice check-config`: exit 3 lists every finding. Then `splice status` for `not running: <reason>`, then `splice logs --tail 100` for `[<key>][boot] SKIPPED` | Whole-file findings refuse boot; a single head that fails to build is skipped with its reason. Ask, back up, edit `splice.toml`, rerun `splice check-config` until exit 0, then the person restarts. In a terminal, a `splice` verb that hits a refusal offers an interactive fix session. Outside a terminal it only prints the list |
| Port in use | `splice status` reasons: `port N is claimed by A and B; give each head its own port`, or `head 'k' has an invalid port N`. Another program: `ss -ltnp` (Linux) or `lsof -iTCP:<port> -sTCP:LISTEN` shows the holder. `splice listeners` prints the ports splice wants | Give each head a unique `port` in `[heads.<key>]`. For the control port set `[daemon] control_port` (or `SPLICE_CONTROL_PORT`). Port 3095 is the hub's and is fixed. Ask, edit, `splice check-config`, restart |
| 401 from a provider | Requests word: Signed out (splice holds nothing) or Credentials refused (provider rejected it). `splice doctor` auth section | OAuth head: person types `splice login <head>` (add `--label <name>` for another account). API-key head: person types `splice key set <ENV_NAME>` or `<command> login`; a saved key applies on the next request, no restart. `splice key list` shows names only. If the line is `This session's splice key is not valid.`, relaunch that Claude Code session (1.5) |
| Quota exhausted on an account | Status row `out of quota until <time>`; Requests shows the window's lock and reset; Sessions card says At limit; `splice doctor` accounts section | It clears at the reset by itself. A head with an account pool moves to a free login. Options for the person: add an account (`splice login <head> --label <name>`), use Continue on to move the session to another head on the Sessions page, or wait |
| Over budget | Requests word Over budget (`error:budget-blocked`) | Budgets are set in the console. The person raises or clears it |
| A session does not come back after a restart | `splice sessions` (live or stale), `splice logs --head <key>`, the session card on Sessions (Stalled, Resumes in, Won't resume, At limit, Signed out) | Claude Code retries by itself. If the turn was cut after text began, the person re-sends. `claude-<head> -r <id>` resumes the transcript. A session that never recovers is a bug: report the log lines |
| Console says Locked or unauthorized | the page shows `Locked` and `splice console` with a Copy button: the browser holds no key splice accepts | Person types `splice console`. It starts the daemon if needed and opens the console with the key in the address fragment. Do it again after the management key is rotated or deleted |
| `splice console` cannot start the daemon | message `the daemon isn't running and couldn't be started`, or `the daemon has no management key yet; run splice restart` | read `<logs>/daemon-boot.log`, run `splice check-config`, then 6.3 first row |
| Usage chart slow or half drawn | first open after idle reads each head's totals: chips read Not counted yet or Not read while a head is still being read | wait and reopen. A long `historyRetentionDays` (default 35 on a fresh install) makes the week read bigger; shorten it only on the person's yes, in Settings > Your data, because it asks before deleting. A head that errors keeps its chip: check that head |
| A model is missing from the picker | `splice models [provider]` lists what the endpoint serves beside what is declared. Check the head's `models` allowlist, the provider's `discovery = { include/exclude }`, and `model_slots` | A `models` list is an allowlist: add the id, or use `model_slots` to map tiers without restricting. A discovery `exclude` hides it. The endpoint's list is read at start and hourly. A provider or head edit needs `splice check-config` and a restart; a pure `context_window` edit does not |
| Claude Code shows the wrong context window or compacts early or late | the row's `context_window` in `splice.toml` (Models page shows it); the id spelling in the picker | Claude Code fixes one window per process (`CLAUDE_CODE_MAX_CONTEXT_TOKENS`, set from the pinned row) and compacts on counts splice reports, scaled by client window over declared window. So correct the row's `context_window` to the real ceiling: it is live, no restart. The bracket in an id, `[1m]` or `[500k]`, is a selector Claude Code reads: `[1m]` is always 1,000,000 to the client, so never put `[1m]` on a row whose real ceiling is lower; declare `1000000` for a `[1m]` row, not 1048576 |
| `splice status` says `daemon stopped` | it starts on the first launch of any head command, `splice console` or `splice restart`. If it will not start: `<logs>/daemon-boot.log`, `splice check-config`, `splice doctor`. A daemon of another version is replaced by the next launch | Ask the person to run `splice restart` in their own terminal (a restart from the manager's pane carries the manager's environment). If the control port is held, see Port in use |
| Tools vanish from a session | `splice doctor` MCP hub row, `systemctl --user status splice-mcp-front.socket` | 2.5. Without the hub, a daemon restart can need `/mcp` in the session |

Source: `app/.../head/HeadBoot.kt` 28-60; `core/.../topology/TopologyMessages.kt` 32-38; `app/.../cli/ConfigFixOffer.kt` 1-3;
`app/.../cli/auth/KeyCommand.kt` 1-6; `app/.../cli/status/ConsoleCommand.kt` 49-72; `app/src/main/resources/console/api.js` 29-147;
`CHANGELOG.md` 183-184, 274-280, 1211-1212, 1952-1955; `app/.../daemon/TopologyWindows.kt` 1-22;
`core/.../model/ModelCatalog.kt` 6-30, 216-288; `app/src/main/resources/splice.example.toml` 460-469, 512-524;
`README.md` 172-185, 372-376, 1024; `features/lifecycle/.../restart/RestartCommand.kt` 1-6.

The example file's comment near `splice.example.toml` line 525 says a window edit needs `splice restart`. The
header at lines 5-6, the README and `TopologyWindows.kt` say it does not. The code wins: no restart.

### 6.4 When the table does not answer

1. Run `splice doctor` and read the failing row's fix.
2. Run `splice logs --tail 200` and `splice logs --head <key> --tail 200`.
3. Search this skill: `grep -rn "<words>" .claude/skills/splice/`.
4. Say "I don't know", name what you checked, and offer `splice doctor --json --with-logs --out <file>` for the
   person to share. It is redacted, and nothing is uploaded.
