# SPLICE GATEWAY — FINAL STABILIZATION REPORT
Six research+audit pairs merged. Repo: /Users/marcos/dev/infra/splice (branch gateway-stabilization-2026-07-18).

---

## 1. RANKED GAP LIST (deduped, high impact first)

### HIGH

**G1. Cross-process credential-file races — no lock, no re-read-before-refresh, no re-read-on-invalid_grant**
- Practice: single-flight must extend across processes: lock (or at minimum re-read) the shared file before POSTing a refresh; on invalid_grant, re-read once and retry with the disk-fresh token. Proven by openai/codex (`UnauthorizedRecovery`: reload-from-disk first, `Semaphore(1)` refresh lock, merge-on-persist), gcloud (SQLite credentials.db), documented failure in claude-code #48786.
- Evidence: `gateway/provider-grok/src/main/kotlin/splice/provider/grok/GrokAuthProvider.kt:91-102`, `provider-kimi/.../KimiAuthProvider.kt:64-72`, `provider-codex/.../CodexAuthProvider.kt:72-91` — read→POST→write with no lock and no freshness check; zero `FileLock` hits repo-wide. Grok/codex share files with official CLIs; kimi has mandatory rotation, so the race loser gets invalid_grant and can trip server-side reuse detection (whole-family revocation).
- Fix: in each `doRefresh()`: (1) FileLock on `<authPath>.lock`; (2) after acquiring, re-read — if expiry now outside the proactive window, use it and skip the POST; (3) on invalid_grant, re-read once and retry with the disk-fresh refresh token. Steps 2-3 alone close most of the race.

**G2. Zero-event 200 stream is undiagnosable and hardcoded retryable-OVERLOADED — the diagnosis half of the dead-auth incident is still open**
- Practice: assert at least one event arrived before declaring success; capture and log the raw first bytes when zero arrive; classify by payload, not transport status. Proven by LiteLLM #29602 (truncated streams logged as success = the anti-pattern), nginx `invalid_header`, HAProxy `empty-response`/`junk-response`, anthropic-sdk-python #1258.
- Evidence: `provider-spi/.../SseReader.kt:170-172` drops malformed frames with no capture; `PassthroughStreamTranslator.kt:86-94` and `ResponsesStreamTranslator.kt:95-105` hardcode zero-event end to `ErrorType.OVERLOADED`; nothing branches on `EVENTS_IN == 0` (`TurnDriver.kt:265`). A dead-auth 200+HTML body makes Claude Code retry a permanently dead head forever (kimi has no HTTP-status path to trigger refresh).
- Fix: buffer first ~1KB of decoded stream text until first successful emit; on zero-event end, log the snippet in the turn ERROR line and run `UpstreamFailureClassifier` over it so auth-shaped bodies classify as AUTHENTICATION (→ refresh path / "run: claudex login") instead of OVERLOADED.

### MEDIUM

**G3. Retry-After / server pushback never read** (merged: proxy-resilience + harness audits)
- Evidence: `UpstreamClient.kt:200` — `Failed(status, text)` discards headers; 429 gets the same 200ms backoff as a 502; zero `Retry-After` hits in main sources.
- Fix: add `retryAfterMs: Long?` to `RetryOutcome.Failed`, populate from the header (+ optional "in Xs" body regex); use `delay(max(retryAfterMs ?: 0, backoffFor(attempt)))` — server hint as a floor (gemini-cli), unparseable/absurd → GIVE_UP (gRPC A6 negative pushback).

**G4. Retry policy under-tuned vs every reference harness** (merged cluster)
- (a) `RETRYABLE_STATUSES = {502,503,529,429}` — 500/504/408 not retried (`UpstreamClient.kt:252`); every surveyed harness retries all 5xx. Fix: `429 || 408 || in 500..599 minus 501`, keeping the encrypted_content and auth-403 carve-outs.
- (b) Default budget is 2 attempts / ~200ms total backoff (`Knob.kt:85-91`) vs codex 4+5, gemini 10, Claude Code 10. A 2-3s blip still fails the turn. Fix: default 4-5 attempts, cap delay at ~10s.
- (c) No jitter (`UpstreamClient.kt:41-43`). Fix: `* Random.nextDouble(0.9, 1.1)` (codex shape), one line.
- (d) No cross-attempt wall-clock deadline (`UpstreamClient.kt:77`; watchdog totalCap starts only at handoff). Fix: record t0 in `post()`, check remaining budget before each attempt/sleep (Envoy per-try vs route-timeout).
- Proof projects: codex `util.rs`, gemini-cli `retry.ts`, Claude Code error reference, Envoy router filter.

**G5. No stream-reconnect budget: "handed off" != "client saw output"**
- Evidence: `UpstreamClient.kt:126` — `streamHandedOff` flips on HTTP 200 (`:150`), before any client frame exists; a stream torn between the 200 and the first content event fails the turn even though re-issue is provably safe.
- Fix: thread `clientFrameEmitted: () -> Boolean` from the emitter (FIRST_FRAME already exists, `TurnDriver.kt:186`) into the guard: `streamHandedOff && clientFrameEmitted()`; optionally a small separate stream-reissue budget (2-3). Keep the hard no-retry rule after any frame is out. Proof: codex `DEFAULT_STREAM_MAX_RETRIES=5`; Claude Code position-aware retry (silent re-issue before visible output only).

**G6. Codex head has zero expiry awareness — the grok bug's exact latent shape** (merged oauth+harness)
- Evidence: `CodexAuthProvider.kt:47` serves cached token unconditionally; `CodexOAuth.kt:127-148` persists no expiry; `CodexRefresh.kt:44-48` never parses `expires_in`. Works only because ChatGPT happens to 401 cleanly.
- Fix: decode JWT `exp` via existing `decodeJwtClaims` (`CodexOAuth.kt:102`) — no auth.json format change — and copy the grok proactive-window + serve-stale block (`GrokAuthProvider.kt:58-63`). Proof: codex itself (`CHATGPT_ACCESS_TOKEN_REFRESH_WINDOW_MINUTES = 5`).

**G7. Grok/codex refresh calls have no error classification or transient retry**
- Evidence: `GrokRefresh.kt:21-50`, `CodexRefresh.kt:24-51` — single attempt, any non-2xx or thrown transport error → null; invalid_grant and a DNS blip are indistinguishable. Kimi already has the correct loop in-repo (`KimiRefresh.kt:44-81`).
- Fix: extract KimiRefresh's classify/retry into a shared `:app` helper, route grok/codex through it; add jitter while there. Proof: google-auth-python `_can_retry` ({internal_failure, server_error, temporarily_unavailable} + transport retryable; invalid_grant terminal).

**G8. No pre-traffic auth/health probe loop — dead auth still discovered by user traffic**
- Evidence: no periodic probe anywhere in gateway main sources; revoked-but-unexpired tokens invisible until a real turn fails.
- Fix: per-head daemon coroutine (~60s inter), cheap auth-exercising probe (models/introspection), log every state transition, trigger existing single-flight refresh on failure. Proof: HAProxy inter/rise/fall + observe/on-error; Envoy health checking.

**G9. Malformed/skipped SSE frames dropped with zero telemetry** (merged proxy+platform)
- Evidence: `SseReader.kt:169-172` — `getOrNull()` drop path has no counter, no log, no callback; the kimi silent-drop class is fixed only for the space prefix specifically.
- Fix: `onMalformed` hook mirroring `onBytes` → `PerfKeys.FRAMES_SKIPPED` in the perf row + log first malformed snippet per turn. Proof: nginx `invalid_header`, HAProxy `junk-response` as counted classes.

**G10. Installed launch shim is stale — deployed daemon cold-starts with NO JVM opts**
- Evidence: `~/.local/share/splice/splice-launch:48` is `nohup java -jar "$JAR" daemon` (no `$SPLICE_JVM_OPTS`); repo `bin/splice-launch:16,52` has `-Xmx1024m`; `AdminSupport.kt:57`'s "both cold-start paths agree" claim is false on disk. This silently resurrects the un-capped-heap pitfall.
- Fix: re-run `installShim` now; structurally, embed the shim as a jar resource with a version marker verified at startup so drift is detected.

**G11. TCP connect timeout is the 5-minute firstByteTimeout**
- Evidence: `UpstreamClient.kt:260` `connectTimeoutMillis = firstByteTimeoutMs` (300s); a blackholed address stalls to OS SYN timeout (~75s on macOS) x connectAttempts=3 before the retry classifier ever sees an error. Headers-wait is already covered by socketTimeout.
- Fix: `connectTimeoutMillis = 10_000` (own knob); dead address then fails in ~10s into the existing transport-retry loop. Proof: Cloudflare AI Gateway (timeout measured to first byte, separate from connect), Envoy per-try.

**G12. SSE parser is incident-driven, not WHATWG-derived** (merged sse+platform)
- Evidence: `SseReader.kt:144-146` dispatches per `data:` line (no blank-line event assembly — a spec-legal multi-line data event parses as N malformed fragments and silently drops); `:137-143` lone CR not a terminator; no BOM strip; complete-line-at-EOF still emitted contra the spec's discard-pending rule.
- Fix: accumulate data lines joined with `\n`, dispatch on blank line, drop pending buffer at EOF, accept `\r` terminator with a lastByteWasCR flag, strip one leading U+FEFF. Proof: WHATWG HTML §9.2 ABNF; rexxars/eventsource-parser as the reference implementation.

**G13. Pre-stream request rejections return HTTP 200 + error JSON**
- Evidence: `HeadServer.kt:168-172` — `respondText` with no status argument; the same "200 masking an error" shape splice suffered as a client.
- Fix: `status = HttpStatusCode.BadRequest` (4xx per rejection class), keep the Anthropic-shaped body. Proof: OpenRouter/LiteLLM two-phase model (JSON + real status pre-commit; typed SSE error post-commit).

### LOW (batched, roughly in order)

- **G14.** Transport-failure backoff shorter than a real resolver blip (200/400ms) — branch DNS-class failures onto a 1s/2s/4s schedule (Envoy `dns_failure_refresh_rate` shape). `UpstreamClient.kt:41-43`, hook exists at `:129`.
- **G15.** Terminal invalid_grant latch missing — dead refresh token re-POSTed every turn (`GrokAuthProvider.kt:60-63`); depends on G7 classification. Cache "terminally dead as of <token-hash>" until file content changes.
- **G16.** Post-send SocketException retried identically to connect-phase failure — possible double token burn (`UpstreamClient.kt:125-127, 237-243`). Cheap 80%: log a distinct "possible-duplicate" class on post-send retries (nginx `non_idempotent` caution).
- **G17.** No background refresh tier — proactive refresh blocks the request path (`GrokAuthProvider.kt:62`). Fire-and-forget between stale floor and window; await only below floor (AWS prefetch/stale two-tier).
- **G18.** Grok auth file without `expires` treated as never-expiring (`GrokAuthProvider.kt:60`) — synthesize mtime + 4h TTL.
- **G19.** AUTHENTICATION errors via UpstreamFailed lack the "run: claudex login" hint (`TurnDriver.kt:202-207`) — append per-head instruction when classified type is AUTHENTICATION. (Matters more once G6 lands.)
- **G20.** No per-head passive health counters (local-origin vs provider-error split, Envoy `split_external_local_origin_errors`) and no cooling state — value is diagnosis speed, not ejection.
- **G21.** Unbounded admission queue, shed load never surfaced (`InflightGate.kt:23`, `HeadServer.kt:184`) — bound waiters, respond 529 "gateway at capacity" (Envoy max_pending + UO flag).
- **G22.** No aggregate retry budget across concurrent turns (Envoy RetryBudget 20%/min 3) — only if fan-out grows.
- **G23.** No CPU-vs-bytes watchdog to recycle a wedged HttpClient (CIO selector spin class recurs upstream: ktor #1041, KTOR-1126, KTOR-4308) — ThreadMXBean sampler, rebuild the head's client; per-head isolation makes recycle safe.
- **G24.** Positive JVM DNS TTL not pinned (`Main.kt:26` sets only negative) — one-liner.
- **G25.** No JEP 346 idle heap uncommit — add `-XX:G1PeriodicGCInterval=60000` to `AdminSupport.kt:124` and `bin/splice-launch`.
- **G26.** TCP_NODELAY unverified on either hop — one-time check; loopback downstream makes real exposure small.
- **G27.** Non-goals, correctly skipped: stream resumption cursor (Claude Code sends no Last-Event-ID), cross-head failover (session state can't migrate; cheap version = name healthy sibling heads in the give-up error), hedging (paid non-idempotent completions, correctly absent per gRPC A6), multi-endpoint redispatch (single URL per head is architectural).

---

## 2. WHAT SPLICE ALREADY DOES RIGHT

All seven incidents are closed at the root, mostly to a standard stricter than the surveyed references:

- **Transport-exception retry taxonomy** — UnresolvedAddress/UnknownHost/Connect/Socket/timeouts walked through the Ktor cause chain, retried pre-handoff (`UpstreamClient.kt:85-95, 231-251`). This is the bug Claude Code itself shipped (#37077) and continue.dev shipped (#12421).
- **Negative DNS cache killed correctly** — `Security.setProperty` (not the -D trap) as the first statement of main, before any lookup (`Main.kt:26`). The 37-failure amplifier is structurally gone.
- **Commitment rule** — never retry after handoff; every post-commit failure becomes an honest typed `event: error` frame, never a truncated 200 (`UpstreamClient.kt:125-151`, `TurnDriver.kt:102-114`). Matches nginx/gRPC-A6/Claude Code exactly.
- **Hot-spin fix done right** — ensureActive + awaitContent + MAX_SPURIOUS_WAKEUPS=1024 honest EOF, pinned by regression test (`SseReader.kt:39-41, 90-100`).
- **Auth-refresh classification stricter than every surveyed harness** — 401 always; 403 only with auth-shaped body regex so a plan/permission 403 can't spend the refresh (`UpstreamClient.kt:209-221`). None of codex/gemini/aider refresh on 403 at all.
- **Single-flight refresh with cancellation isolation** (`SingleFlight.kt:41-48`) + **merge-not-overwrite atomic 0600 persistence** — the exact gemini-cli #21691 failure mode, already found and fixed in-repo (`GrokAuthProvider.kt:110-133`, `SecureFile.kt`).
- **Proactive expiry refresh** for grok (5-min window, serve-stale-on-failure) and kimi (half-life) — the codex-reference pattern.
- **No zero-log failure path** — every turn ends in exactly one terminal (emitTerminal/emitError/abandon with idempotence), every failure class logs + emits a perf row with attempt/retry/refresh counters; truncated streams are counted failures, never success (the LiteLLM #29602 anti-pattern, avoided).
- **Two-tier watchdog** — firstByte vs streamIdle vs totalCap, typed sentinel set before cancel, raw-read liveness touch (`Watchdog.kt:53-76`) — the codex `stream_idle_timeout_ms` defense.
- **In-stream errors classified by typed payload, not the frozen 200** (anthropic-sdk-python #1258 avoided); WHATWG optional-space data parsing with kimi regression test.
- **Half-close handling** — 10s SSE-comment pinger as disconnect probe, child-Job turn trees so client disconnect cancels the upstream call (stops provider billing), cancel-safe FIFO inflight gate.
- **RFC 8628 device flow done to spec** for kimi (permanent +5s slow_down, bounded expired_token restarts).
- **Kimi refresh classification** (`KimiRefresh.kt`) is already the google-auth `_can_retry` shape — it just needs to be shared (G7).

---

## 3. PLATFORM VERDICT: STAY ON KOTLIN/JVM

**Recommendation: stay. Do not port to Bun/Node, do not migrate to KMP.** Decisive reasons:

1. **The instability was application logic, not platform.** 5 of 7 incidents (space-less SSE frames, 401-vs-403, unread expiry field, unretried thrown exceptions, silent empty stream) would have shipped identically in TypeScript. The two JVM-attributable items (negative DNS cache, RSS) were fixed with one line and one flag respectively. A rewrite ports five bug classes and re-litigates the two hardest already-fixed ones (cancellation propagation, torn-stream teardown) in a language where structured concurrency is convention, not compiler-enforced — Bun additionally converts the 600%-CPU class from "cores burned, sessions alive" into "all heads frozen" (single loop) and adds an open segfault surface in exactly the ReadableStream/Response paths a proxy hammers (oven-sh/bun #31159).
2. **The measured JVM cost is already small.** 348MB RSS running / 111MB boot / 0.58s startup / 138MB committed heap, 56MB live (audit measurements) — not the feared ~500MB, which was default heap ergonomics. The gap to the Go bar (<30MB, sub-100ms) is real but irrelevant for a single-user loopback daemon started once a day.
3. **KMP's cost lands exactly on the hottest surface for zero payoff.** Measured split: the portable 3,400 LOC (dialects/control) isn't where incidents happen; provider-spi/core/gateway/app (~6,700 LOC) hinge on java.nio.file (55 imports), java.security PKCE, CharsetDecoder/ByteBuffer hot path, ProcessBuilder/file-lock glue. Multi-target buys nothing for a single-machine daemon.

**If staying (do these):**
- Fix the stale shim (G10) — the only deployment where "JVM tuning" is currently *absent*.
- Add `-XX:G1PeriodicGCInterval=60000` (JEP 346) so idle RSS doesn't ratchet to burst peak.
- Optional tightening if footprint ever matters: `-Xmx256m -XX:MaxMetaspaceSize=96m -Xss512k`, verified with `jcmd VM.native_memory`.
- CDS/AppCDS: skip — startup is already 0.58s; payoff ~200-300ms for extra build complexity.
- GraalVM native-image: keep as the *documented exit*, not a task — ~240MB RSS and ~500ms saved against 8-15min CI builds and closed-world config for logback/CIO. Only exercise it (via the existing e2e wall) if RSS becomes a real constraint.

---

## 4. NEXT ACTIONS (payoff-per-effort order)

1. **Re-run `splice install` to refresh the stale shim** (G10) — minutes; restores the heap cap on the deployed cold-start path. Then add a shim version marker so drift is detected.
2. **Retry-policy batch in `UpstreamClient.kt`** (G4a-c + G3): widen RETRYABLE_STATUSES to 408/429/5xx-minus-501, jitter (one line), raise default attempts to 4-5 with a 10s delay cap, capture Retry-After into `RetryOutcome.Failed` and use it as a backoff floor. One file plus a Knob default; closes four gaps.
3. **Zero-event diagnostic buffer + classifier** (G2) — first ~1KB side buffer in `sseJsonEvents`, classify via `UpstreamFailureClassifier` on zero-event end. Highest remaining incident-class payoff; moderate effort.
4. **Cheap cross-process race closure** (G1 steps 2-3): re-read-before-refresh + re-read-on-invalid_grant in all three `doRefresh()`s; add the FileLock after.
5. **Codex proactive expiry via JWT `exp`** (G6) — reuses `decodeJwtClaims` and the grok pattern; ~30 lines.
6. **Shared refresh classify/retry helper extracted from KimiRefresh** (G7) — mostly code motion; enables the invalid_grant latch (G15) later.
7. **`connectTimeoutMillis = 10_000`** (G11) — one line plus a knob.
8. **FRAMES_SKIPPED counter + first-malformed-snippet log** (G9) — small; mirrors existing onBytes plumbing.
9. **Stream-reconnect gate on `clientFrameEmitted()`** (G5) — small guard change, big UX win for the torn-before-first-frame case; keep hard no-retry after any frame.
10. **Cross-attempt deadline in `post()`** (G4d) — t0 + budget check before each attempt/sleep.
11. **WHATWG event assembly in SseReader** (G12): blank-line dispatch, multi-line data join, lone-CR, BOM, discard-pending-at-EOF. Do together with 8 since it's the same file.
12. **4xx statuses on pre-stream rejections** (G13) — one call site.
13. **Per-head auth probe loop** (G8) — new coroutine, ~60s interval, logs transitions, reuses single-flight refresh.
14. **`-XX:G1PeriodicGCInterval=60000` + pin positive DNS TTL** (G24/G25) — two one-liners alongside items already touching those files.
15. **Lows as opportunistic batch** (G14-G23, G26): DNS-class backoff branch, possible-duplicate log split, serve-stale async refresh tier, synthetic grok TTL, auth-error login hint, admission-queue bound, passive health counters, CPU-vs-bytes client recycler, TCP_NODELAY verification. None urgent for a single-user daemon; several become trivial after items 2-6 land.

Explicit non-goals (documented, not built): stream resumption cursors, cross-head mid-session failover, hedging on completions, multi-endpoint redispatch, GraalVM/KMP/Bun migration.