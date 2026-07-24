# Modular backends: typed data selecting a small set of explicit algorithms

**Status:** design plan — nothing implemented yet. For review before it becomes an executable
`manifest.py` campaign.
**Acceptance model (not "behavior-preserving"):** established vendor wire semantics and valid
explicit configurations stay **byte-identical**; each intentional correction declares an **exact
before/after delta** and proves **no collateral delta** outside it. Every manifest item classifies
itself as one of (§4a):

- **pure refactor** — exact byte identity;
- **targeted correction** — a fixture demonstrates the expected delta and proves no collateral delta;
- **validation change** — an invalid configuration moves from silent fallback/late failure to a
  specific typed diagnostic.

**Scope:** the Kotlin gateway under `gateway/`.

## How to review this document

1. **Attack the north star (§1):** *most* backend variation is typed data selecting from a *small,
   explicit* set of algorithms. Find a place this doc mislabels an algorithm as data, or vice-versa.
2. **Check the current-state table (§3)** — each "exists" claim cites its file. Verify them.
3. **Attack B0 (§4-B):** it must run the **real** translator → decorator → `TurnPipeline` → terminal
   path, cover **stream and non-stream**, and pin **all** nondeterministic transport (message-id +
   keepalive pinger). Find an output behavior it fails to pin.
4. **Attack the failure-classification table (§4-D):** static composition = validation; mutable
   credentials + external availability = runtime health. Find a row placed in the wrong column.
5. **Attack the sequencing (§7):** every slot must be independently dispatchable and contract-gated;
   Q1/Q2/Q3 decision-records must precede the slots they shape.

---

## 1. First principle: separate the functor from its parameters

splice is a **bidirectional translation** between the Anthropic Messages API (what Claude Code
speaks) and a backend API. It hears Claude, re-says it to the backend, hears the backend, re-says it
to Claude. That is the entire job.

Every backend difference is one of two kinds:

- **Typed data** — endpoints, headers, field names and placement, OAuth client-id/issuer/scopes/
  credential-path, model families, capability flags, ceilings, cache-key strategy, alias mappings.
- **Algorithm** — a *small, explicit, finite* set: the SSE **stream reducers**; the OAuth **login
  mechanics** (PKCE-loopback, device-flow) *and* the per-provider **credential/refresh policies**
  (§4-C — real algorithms, not data); the canonical parse and the sole SSE emit (written once).

**North star:**

> **Most backend variation is typed data that *selects* a small, explicit set of algorithms.**

The design goal is to move leaked *data* out of the algorithms and make the *selection* typed and
validated, while keeping the genuine algorithms first-class and well-tested. Production bugs from
the leak: `Regex("gpt-5\\.6")` (`ResponsesRequestBuilder.kt:77`); the alias-tier substring heuristic
(`LaunchService.kt:132`); raw-vs-stripped model ids (`ModelCatalog`); and — subtler — `cache_key`
acting as a hidden provider discriminator (`Daemon.kt` `apiKeyResponsesProvider`: `cacheKey ==
"session-id"` selects `GrokProvider`, importing Grok's effort ladder/strictness/tool-choice, not
just a cache-key algorithm).

## 2. The model

> **A backend = (dialect ∈ closed built-in set) × (auth-strategy ∈ closed built-in set) ×
> (a typed, validated descriptor).**

- Reuse an existing dialect, login strategy, credential/refresh policy, and supported capability set
  → **pure validated TOML, zero code.**
- A new credential-file codec, refresh policy, wire protocol, or auth mechanism → **one cohesive new
  module + explicit build + registration edits; no existing dialect/auth *implementation* changes.**

Closed-world by choice: a new `Dialect`/resolved-auth variant *should* break every exhaustive `when`
until each site is handled (that is the safety). A map-shaped registry does **not** preserve this —
adding an enum variant does not make a `Map<K,Factory>` fail compilation for a missing key. So
factory selection stays an **exhaustive `when` over a closed resolved type**, or a map paired with a
mechanical completeness check over all variants (§4-D). An open plugin system is out of scope (§10).

## 3. Current-state assessment (grounded)

The output boundary is **already complete**; auth closure and validation are **not yet** what the
plan needs.

| Target piece | Status | Evidence |
| --- | --- | --- |
| Shared input parse | exists | `core/wire/AnthropicRequest.kt` |
| **Dialect-neutral output algebra** | exists | `provider-spi/WireSink.kt` — typed blocks, no terminal verbs by design |
| **Sole Anthropic SSE emitter** | exists | `gateway/wire/SseEmitter.kt` — owns stop_reason, framing, escaping, terminal/error, stream+non-stream parity |
| **Post-translation pipeline** | exists | `gateway/pipeline/TurnPipeline.kt` — gateway runs promote-to-text, honesty gates, mirror, then the sole terminal emit |
| **Non-stream assembly** | exists | `gateway/wire/CollectingTerminal.kt`, `gateway/wire/TurnTerminal.kt` (terminal authority) |
| Sink decorator pattern | exists | `gateway/wire/BufferingWireSink.kt`, `gateway/reasoning/Mirror.kt` |
| Provider SPI | exists | `provider-spi/Provider.kt`, `UpstreamClient.kt`, `SseReader.kt` |
| Shared auth building blocks | exists | `provider-spi/SingleFlight.kt`, `CredentialLock.kt` |
| Closed dialect taxonomy + dispatch | exists | `Topology.kt:75` `enum class Dialect`; `Daemon.kt:298` `when(dialect)` |
| Emitter-to-bytes test harness | exists | `SseEmitterTest.kt`, `SseEscapingParityTest.kt` |
| Auth probes run **after** head start | exists (invariant) | `Daemon.kt:134` `head.start()` then `startAuthProbeIfRefreshable`; providers reread + recover after login/rotation |
| Degraded-boot isolation | exists (invariant) | `Daemon.kt:195` — a bad head is logged, siblings still start, control reports DEGRADED |
| Request byte-goldens per dialect | exists (request only) | `ResponsesContractTest`/`ChatContractTest`/`PassthroughContractTest` (`CONTRACT.md:8`) |
| **Auth taxonomy is NOT closed** | gap | `AuthConfig.kind` is a raw `String` (`Topology.kt:86`); `AuthKind.from()` returns null for unknown (`AuthKind.kt:29`); `AuthKind` omits API-key; daemon `when` `else -> ApiKeyAuthProvider` silently absorbs unknown kinds |
| Capability overlay | exists (partial + buggy) | `ResponsesQuirks.withToml` overlays 5 of 7 quirks; **absence semantics broken** (§4-A) |
| Typed auth-strategy params | **absent** | `AuthConfig` = `{kind, file, env}` only; client-ids/issuer/token-URLs/scopes/redirect are Kotlin constants; login assembly is provider-specific (`LoginCommand.kt:109`) |

## 4a. Acceptance categories (per manifest item)

Because several moves intentionally change behavior (bug fixes), the campaign uses expected-delta
acceptance, not universal preservation. Each item declares:

- **pure refactor** → byte-identical request golden **and** stream corpus;
- **targeted correction** → a fixture pins the intended before/after delta, and the surrounding
  contracts prove no collateral delta;
- **validation change** → an invalid config's diagnostic changes (documented), valid configs
  unchanged.

## 4. The moves

### A — Externalize model-capability resolution (+ absence fix, + cache_key de-coupling)

- **Current:** hardcoded model-family `Regex` literals + `if (model.matches(...))` in the builder;
  `QuirksConfig` fields are non-null, so an omitted TOML key overwrites the provider base default;
  and `cache_key` doubles as a provider selector.
- **Target:** (1) overlay preserves **absence** — an unset key never overrides the base; (2)
  model-family behavior resolves through **one sanctioned resolver** over **typed per-model
  capabilities**, no model-string branch in the builder; (3) provider/profile selection is
  **independent of `cache_key`** — `cache_key` selects only the cache-key algorithm.
- **Concrete changes:** nullable-at-boundary overlay fields (or an explicit unset sentinel); typed
  capabilities declared on each configured `ModelEntry` (the catalog is finite — §8-Q1); an explicit
  provider/dialect selector that no longer keys off `cache_key`.
- **Acceptance:** *targeted correction* (absence fix changes affected configs — declare the delta) +
  *pure refactor* (capability resolution and cache_key de-coupling emit identical bytes for existing
  valid configs).
- **Proof:** a **topology → provider-profile → request** matrix parsing TOML through the
  **production overlay** (the existing golden builds `ResponsesQuirks` directly and misses this) for
  Codex, Grok, and API-key Responses; omitted fields retain each provider's defaults byte-identical,
  and the Grok-vs-OpenAI split is reproduced without `cache_key` as the discriminator. Plus an
  ast-grep wall banning a **model-string branch** — scoped to "branch on the upstream-model string"
  so it does not false-positive on unrelated `.contains` (`ChatStreamTranslator.kt:351
  already.contains(final)`); red/green against real violations and benign lookalikes; write-time and
  at the gate.

### A′ — Alias mapping as declared data (with explicit fallback)

- **Current:** `LaunchService.kt:132` classifies alias tiers by substring
  (`contains("mini")||contains("fast")`), misbinding HAIKU for non-OpenAI vendors, and fills missing
  positions with the pinned model. Valid catalogs have <4 models (starter OpenRouter has one).
- **Target:** a **direct optional alias map** per provider, not a bare per-model `tier` field:
  ```toml
  [providers.example.aliases]
  opus = "model-a"; sonnet = "model-b"; haiku = "model-c"; fable = "model-a"
  ```
  Omitted aliases fall back explicitly to `pinned_model` (preserving single-model configs); a model
  may serve multiple aliases; validation checks referenced ids exist. The substring heuristic is
  deleted.
- **Acceptance:** *targeted correction* — declare the alias delta for each affected catalog
  (non-OpenAI heads change; OpenAI/codex-style heads that matched "mini" stay the same or are
  restated explicitly).
- **Proof:** tests asserting correct OPUS/SONNET/HAIKU/FABLE binding for grok/kimi/openrouter names
  and correct fallback for single-model catalogs.

### B — Output pipeline: extract features as sink decorators; keep `WireSink`/`SseEmitter`

The output boundary already exists; this move adds no event IR. Extract *demonstrated* cross-cutting
output operations (request transforms, reducer-local handlers, `WireSink` decorators,
terminal/post-reducer stages) into typed boundaries composed per descriptor; reducer-local protocol
behavior stays in reducers. Introduce a new event IR **only if** the design pass shows a concrete
behavior neither `WireSink` nor a decorator can express. Reducers depend on **synchronous handles**
from `openText`/`openThinking`/`openTool`.

- **Risk:** highest — the streaming hot path and its subtle state (reasoning envelopes,
  redacted-thinking, watchdog, terminal derivation).
- **Proof — hard prerequisite (B0), over the PRODUCTION path:**
  ```
  backend events → real dialect translator → configured WireSink decorators/folding
                 → TurnOutcome → real TurnPipeline → SseEmitter → exact client bytes
  ```
  Driving translators directly into `SseEmitter` is insufficient — it skips promote-to-text, honesty
  gates, mirror, terminal selection, usage shaping, and fold flush/discard, which run in
  `TurnPipeline` **after** translation. B0 must also include a **paired non-stream contract**:
  `stream:false` runs the same translator/pipeline through `CollectingTerminal`/`TurnTerminal`, which
  separately own content assembly, tool-JSON parsing, error envelopes, and HTTP status — SSE bytes
  can stay identical while non-stream regresses. **Determinism:** pin the message-id
  (`SseEmitter.kt:296` stamps `msg_${System.currentTimeMillis()}`) **and** the async keepalive
  pinger — recommended: a protocol-byte corpus with the pinger disabled plus a separate deterministic
  transport/pinger contract; do **not** normalize ids or strip comments after capture (that stops
  being a byte contract). Build on the existing `SseEmitterTest`/`SseEscapingParityTest` harness.
  Minimum cases: text/reasoning; tool calls + partial JSON; thinking signatures + redacted reasoning;
  mirror + promote-to-text; usage incl. cached tokens; every stop-reason precedence combination;
  provider error + truncation; pre-frame vs post-frame tear; empty response; client abandonment;
  fold flush/discard.

### C — Auth: one shared refresh skeleton + typed provider policies/codecs (+ C0 typed login params)

Two login mechanics exist and are separated (PKCE-loopback for codex/grok; device-flow for kimi).
Runtime credential/refresh logic is genuinely per-provider **algorithm**: codex builds bearer creds
+ derives expiry from JWT claims; grok derives expiry from file mtime and preserves foreign CLI
fields on merge; kimi yields `x-api-key`, uses a dynamic proactive window, mandates refresh-token
rotation, and vetoes refresh on entitlement-shaped 401s.

- **C0 (prerequisite — lands FIRST, as P4.0):** add **typed, strategy-specific login/credential
  parameters** to the descriptor (client-id, issuer, authorize/token URLs, scopes, redirect,
  credential-file path) consumed by the existing loopback/device algorithms — this is the
  `strategyParams` the skeleton's `exchange` reads, so it must exist *before* skeleton-birth (§7-P4).
  Without it, the Tier-1 "auth-param variation is TOML-only" promise is false — today `AuthConfig`
  carries only `{kind, file, env}` and the rest are Kotlin constants.
- **Target:** **one shared refresh orchestration skeleton** (cache → expiry decision → single-flight
  → lock → peer-rotation → bounded exchange → latch → lifecycle cancel) with **explicit typed
  provider policies/codecs**: parse credentials · derive expiry · construct wire credentials ·
  validate refresh result · merge/rewrite official-CLI file · classify non-refreshable failures.
  Not a loose TOML bag.
- **Sequencing:** the **first** migration (P4a) is a distinct "skeleton-birth" item — it designs and
  extracts the skeleton *and* migrates one provider; bigger and riskier than the two that follow.
- **Proof:** each provider's auth tests stay green; split by provider; live proof per provider (P7
  carve-out); delete old code only in a final enumerated item.

### D — Registry + closed-type validation, preserving the recoverable-auth lifecycle

- **Auth closure:** the TOML `kind` may stay a string for diagnostics, but it must **resolve to a
  closed type** —
  ```
  ResolvedAuthKind = ApiKey | OAuth(ChatGpt | Grok | Kimi)
  ```
  Unknown auth strings become **typed coherence failures, not API-key fallback** (today's
  `else -> ApiKeyAuthProvider` is exactly the leak). Factory selection is an exhaustive `when` over
  the resolved type (or a map with a mechanical completeness check).
- **Failure classification** — static composition is validation; **mutable credentials and external
  availability are runtime health, not bind-time** (heads bind independent of creds today and
  recover after `splice login`/rotation; rejecting a head for a missing credential would break that):

  | Failure | Boundary |
  | --- | --- |
  | Malformed TOML / schema | Global — reject before any bind |
  | Duplicate listener ports/commands | Global — reject before any bind |
  | Unknown provider referenced by a head | Per-head degraded (preserve current) |
  | Unsupported dialect/auth/capability composition | Per-head rejected before its bind |
  | Missing pinned model | Per-head rejected before its bind |
  | Missing/expired/corrupt credentials | **Head binds; runtime health unhealthy** |
  | Provider auth endpoint unavailable | **Head binds; runtime health unhealthy** |
  | Inference upstream unavailable | **Head binds; external health failure** |
  | One head's listener cannot bind | Per-head degraded; siblings remain |

- **Acceptance:** *validation change* — unknown-auth and incoherent-composition move to typed
  diagnostics; credential/availability behavior is **unchanged**.
- **Proof:** an invalid-topology suite covering every row; every valid in-repo topology still boots;
  a missing-credential head still binds and recovers after login without a daemon restart.

## 5. Correct by construction (strongest first)

1. **Sealed types + exhaustive `when`** — `Dialect` closed today; auth closed via `ResolvedAuthKind`
   (§4-D). A new variant breaks every `when` until handled.
2. **Load-time validation at the right granularity** (§4-D) — global-invalid before bind; per-head
   incoherence rejected; credentials/availability stay runtime.
3. **Descriptor-driven resolution** (Move A) — one capability resolver; no model-string branch;
   provider selection independent of `cache_key`.
4. **Byte contracts** — request goldens **plus the B0 stream + non-stream corpora**; every item
   proven against its declared acceptance category (§4a).

## 6. The honest spectrum

| Tier | New backend needs | Files touched |
| --- | --- | --- |
| 0 | Existing dialect + auth (another OpenAI-compatible vendor) | **0 — TOML only** (today) |
| 1 | A capability / alias / **externalized** auth-param variation | **0 — TOML only** (after A, A′, C0) |
| 2 | A recombination of existing stages | assemble from existing decorators/reducers — a few lines (after B) |
| 3 | New wire protocol, credential codec, refresh policy, or auth mechanism | **one new module + explicit build/registration edits; no existing implementation changed** (after D) |

Tier-1 auth-param variation is TOML-only **only** for parameters C0 actually externalizes; a new
codec or refresh policy is Tier-3 code, correctly.

## 7. Fence-aware sequenced roadmap

P0 distinguishes **decision-records**, **manifest-cut** items, and **implementation** items. Q1/Q2/
Q3 are resolved in §8 (their decision-records are ready to transcribe into the manifest); the fences
they gate can now be cut.

- **P0 — Campaign birth.** Create `dev/campaigns/modular-backends.toml`; transcribe the §8 Q1/Q2/Q3
  decision-records; cut each slot's exact exclusive fences (unblocked by §8); declare
  environment-of-record and deletion policy. **No non-P0 item is dispatchable until P0 produces:**
  the exact P2/P3 before/after fixture deltas; one manifest item per exclusive file fence with its
  own verify command; exact path/symbol inventories for each deletion item (incl. the P4.4 auth
  collapse and the Q2 fold-orchestrator ownership of `FoldRunner`/`TurnDriver`); and a certification
  command that launches the **candidate** JAR, verifies its hash + topology digest, and rejects
  malformed or expired waivers.
- **P1 — Characterize & enforce first.** Topology→profile request matrix; the **B0 stream +
  non-stream corpora** over the production path; the scoped model-resolution wall (red/green +
  same-checker-twice).
- **P2 — Overlay absence + cache_key de-coupling.** Preserve absence; provider selection off
  `cache_key`; production-path tests prove no collateral delta.
- **P3 — Capability resolution + alias map.** One resolver, typed per-model capabilities; alias map
  with fallback; delete the substring heuristic.
- **P4 — Auth** (C0 first — the skeleton/policy consume the strategy descriptor, so it must exist
  before skeleton-birth; resolve `ResolvedAuthKind` before/alongside P4.0 so the new skeleton is
  wired through typed dispatch, not the raw-string fallback it would otherwise replace in P6):
  - **P4.0** — C0 typed login/auth-strategy parameters (`strategyParams`).
  - **P4.1** — skeleton-birth + Codex policy.
  - **P4.2** — Grok policy migration.
  - **P4.3** — Kimi policy migration.
  - **P4.4** — enumerated deletion / reference-zero item.
  Live proof per provider (P7 carve-out).
- **P5 — Output pipeline.** Extract demonstrated cross-cutting operations against the B0 corpora, one
  feature/dialect at a time.
- **P6 — Registry & closed-type validation.** `ResolvedAuthKind`; exhaustive/completeness-checked
  factory selection; the failure-classification table (§4-D).
- **P7 — Certification.** Full gate; request + stream + non-stream contracts; **candidate-artifact**
  live receipts (env-of-record must run the *candidate* JAR, not an arbitrary installed one). A
  **live-proof waiver is outstanding debt, not equivalent proof**: it records slot/provider/model,
  candidate revision + JAR hash, redacted topology digest, probe command/version, endpoint +
  observed HTTP classification, timestamp, named owner, expiry/retry date, reopening trigger, and any
  prior valid live receipt. **Expired waivers fail certification.** Then reference-zero/deletion
  proof; final wall reruns + manifest verification.

## 8. Design decisions (P0 decision-records)

The governing rule for Q2 and Q3: **a feature's home is determined by what data it needs.** That
single principle decides every placement below and is the invariant the campaign enforces.

### Q1 — resolved: capabilities per configured model

The gateway exposes only explicitly-configured `ModelEntry` rows (`ModelCatalog` requires ≥1;
membership accepts only configured ids; `ProviderConfig.models` is finite), so **declare capabilities
per configured model — no pattern resolver** unless dynamic upstream discovery is separately
introduced. Capability set and alias-map schema per §4-A / §4-A′.

### Q2 — resolved: five typed phases; a composite feature spans phases under one named orchestrator

The unit of placement is an **operation**, not a feature. Every operation lands in one of five typed
phases, chosen by the data it needs. A **composite feature** is a set of operations across phases,
with **one named orchestrator** owning their order — it is not forced into a single home.

| If the operation needs… | Phase | Exemplar / today |
| --- | --- | --- |
| the **request** (before upstream) | **request transform** (builder stage, descriptor-gated) | cache-key injection, summary delivery, lite reshaping, replay *decode-into-input* |
| **per-frame synchronous block handles** | **reducer-local** (inside the dialect state machine) | protocol frame handling that calls `openText`/`openThinking`/`openTool`; encrypted-reasoning *capture/emit in wire position* |
| to transform the **live frame stream** per-frame | **`WireSink` decorator** (wraps the sink during streaming) | `BufferingWireSink` — buffers the tentative final output of a fold round |
| the **aggregate `TurnOutcome`** (after the machine, before the sole emit) | **post-reducer / terminal stage** (in `TurnPipeline`) | promote-to-text, honesty gates, mirror — already there (`TurnPipeline.kt`) |
| both a completed round's `TurnOutcome` **and** the ability to issue the **next** request | **round/turn orchestrator** | `FoldRunner` (`TurnDriver.kt:355`) driving `FoldController` — decide/repost/accumulate/finalize |

Placement of the named features (composites are decomposed):
- **cache-key, summary delivery, lite reshaping** → request transforms only.
- **promote-to-text, honesty gates, mirror** → post-reducer/terminal stages; already run on the
  aggregate `TurnOutcome` in `TurnPipeline` (gated by e.g. `mirror_reasoning`). This move makes them
  *explicit descriptor-gated stages*, it does not relocate them.
- **reasoning replay (composite):** *decode prior transcript envelope into upstream input* is a
  **request transform** (`ResponsesRequestBuilder.kt:433`); *capture the encrypted envelope in
  output-item order and emit/store it* is **reducer-local** (`ResponsesStreamTranslator.kt:314`).
- **reasoning-continuation fold (composite):** the **round/turn orchestrator** (`FoldRunner`) owns
  decide-continue / build+POST next request / accumulate cross-round usage / flush-vs-discard /
  invoke the terminal exactly once (`ResponsesFold.kt`, `TurnDriver.kt`); the **`WireSink` decorator**
  (`BufferingWireSink`) is only its tentative-output buffer sub-part. Extracting the fold therefore
  owns `FoldRunner`/`TurnDriver`, not just decorator wiring.
- Anything genuinely per-frame and protocol-specific stays **reducer-local** — the reducers depend on
  synchronous handles, so this is where an immutable event IR would fight the grain (§4-B).

**File ownership consequence:** Move B touches (a) request-transform assembly in the builders, (b)
`TurnPipeline` to make post-reducer stages explicit/composable, (c) decorator wiring, and — for the
fold composite — (d) the `FoldRunner`/`TurnDriver` round orchestrator. It does **not** touch reducer
internals except to *remove* inlined behavior that belongs in one of the other phases. Each extraction
is a *pure refactor* proven against the B0 corpora.

### Q3 — resolved: the auth policy interface + the shared skeleton

The three providers share `SingleFlight<Credentials?>` + `CredentialLock` and the **three-tier
proactive-window shape** — but *not identical thresholds*: codex/grok use `>=300s` / `>=30s` / `<30s`
(`CodexAuthProvider.kt:80`, `GrokAuthProvider.kt:93`), kimi uses `>=max(300, expiresIn/2)` / `>60s` /
`<=60s` (`KimiAuthProvider.kt:68`). The *shape* is shared; **threshold + window derivation are
policy**. The skeleton owns the sequence; a `ProviderAuthPolicy` owns the seams below (expanded from
the earlier six-op sketch, which could not express exchange, refresh-token access, grok's
mtime-derived expiry, per-provider timing, or the two distinct failure boundaries):

```
skeleton (shared, one copy):
  read bytes+fileMetadata (mtime/TTL cache) → policy.parseSnapshot
    → policy.refreshTiming(now) three-tier decision
    → SingleFlight → CredentialLock
    → policy.refreshToken(S)              // read inside the lock, before exchange
    → policy.exchange(refreshToken, strategyParams)
    → policy.validateRefresh → policy.merge+persist (0600)
    → latch invalid_grant → lifecycle cancel

interface ProviderAuthPolicy<S : Snapshot, R>:
  parseSnapshot(bytes, fileMetadata): S        // fileMetadata carries mtime — grok synthesizes expiry
                                               //   as mtime + TTL when the file omits `expires`
  accessIdentity(S): String                    // the currently-served access token/key
  refreshToken(S): String?                     // material the skeleton reads inside the lock
  constructCredentials(S): Credentials         // Bearer(access[,accountId]) | ApiKey(x-api-key)
  refreshTiming(S, now): { proactiveWindow, blockingFloor }   // per-provider thresholds
  exchange(refreshToken, strategyParams): RefreshAttempt<R>    // the token-endpoint call
  validateRefresh(R): ValidatedRefresh         // shape/rotation checks (kimi: mandatory rotation)
  merge(oldBytes, fileMetadata, validated, now): bytes        // rewrite CLI file, preserve foreign fields
  classifyRefreshFailure(status, body): RefreshFailure        // response FROM the refresh endpoint
  allowRefreshAfterInferenceFailure(status, body): Boolean    // gate refresh on a 401 from INFERENCE
                                                              //   (kimi's entitlement veto lives here)
```

The last two are **distinct boundaries**: `classifyRefreshFailure` judges the refresh endpoint's own
response (latch invalid_grant); `allowRefreshAfterInferenceFailure` decides whether an inference-side
401 should trigger a refresh at all (kimi vetoes entitlement 401s — `KimiAuthProvider.kt:99`). One
method cannot name both.

`Snapshot` is a per-provider typed record that **includes refresh material**: codex
`{access, accountId, refresh, expiresAtMs}`, grok `{access, refresh, expiresAtMs}`, kimi
`{access, refresh, expiresAtS, expiresInS}`, so the skeleton reads the refresh token generically in
`S` without a side channel. **Deletion inventory (P4 final item):** the three
`*AuthProvider.credentials()`/`refresh()` bodies collapse to one skeleton; only the policy seams +
`Snapshot` records remain per provider. **C0 login-param schema** (client-id, issuer, authorize/token
URLs, scopes, redirect, credential-path) is `strategyParams` above and feeds the two login strategies
and `exchange`/`constructCredentials`, so a same-strategy same-policy vendor is TOML-only.

## 9. Success criteria

- A vendor selecting an existing dialect, login strategy, credential/refresh policy, and supported
  capability set needs **only TOML** — proven by adding one to `splice.example.toml` + a test.
- **No model-string branch** in any request builder; **provider selection independent of
  `cache_key`** — scoped ast-grep wall + the topology→profile matrix.
- Omitted TOML preserves every provider's current bytes; each correction ships with its declared
  delta and a no-collateral-delta proof.
- **One** auth refresh skeleton; auth `kind` resolves to `ResolvedAuthKind`; unknown kinds are typed
  failures.
- Every dialect's request golden **and** B0 stream + non-stream corpora hold across the campaign.
- Global-invalid topology fails before bind; per-head incoherence rejects that head; a missing
  credential leaves the head bound and recoverable — invalid-topology + recovery suites.

## 10. Non-goals

- No new backend protocol/auth mechanism is added; the campaign only makes adding one cheaper.
- No change to reasoning/mirror **semantics** or any vendor wire contract beyond declared deltas.
- No open plugin system — closed-world by choice (§2).
- No dynamic upstream model discovery (keeps Q1 to per-configured-model capabilities).
- Operational items (e.g. the `splice restart` self-auth papercut) are tracked separately.
