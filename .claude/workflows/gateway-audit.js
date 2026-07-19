export const meta = {
  name: 'gateway-audit',
  description: 'Adversarial multi-agent audit of the splice Kotlin gateway: 8 bug finders + 3 arch lenses, 3-vote verification, ranked report. Reusable — pass args.scope to focus.',
  phases: [
    { title: 'Gate', detail: 'one full gradle check for ground truth' },
    { title: 'Find', detail: '8 finders across bug dimensions' },
    { title: 'Architecture', detail: '3 independent architecture lenses' },
    { title: 'Verify', detail: '3-vote adversarial verification per finding' },
    { title: 'Synthesize', detail: 'ranked, deduped report' },
  ],
}

const CONTEXT = `
You are auditing the Kotlin gateway of the splice repo at /Users/marcos/dev/infra/splice.
The code under review is gateway/ (Gradle multi-module). server/ is the LEGACY Node reference
implementation being ported FROM — use it only as the reference for PORT-OF contract claims,
do not report bugs against it.

Module map (gateway/): :core (domain, framework-free), :provider-spi (UpstreamClient, SseReader,
Watchdog, InflightGate, Provider SPI), :dialect-openai-responses, :dialect-openai-chat,
:provider-codex, :provider-grok, :provider-openai, :gateway (HeadServer, TurnPipeline, SseEmitter,
CoalescingSseWriter, UsageStore, CompactStats, PerfStats), :control (ControlServer), :app (Daemon,
Main, CLI, OAuth flows). NEW half-landed work may exist: :dialect-anthropic-passthrough and
:provider-kimi (scaffolding in progress — report on their wiring state factually).

Locked invariants (violations are CRITICAL findings):
- L2: exactly ONE mirrorInto (gateway/reasoning/Mirror.kt), called from the stream path.
- L3: SseEmitter is the SOLE Anthropic wire emitter; clean stop ONLY via emitTerminal (owns
  stop_reason derivation); failures ONLY via emitError; frames are byte-exact
  'event: X\\ndata: {json}\\n\\n'. The hand-built hot-delta frames MUST be byte-identical to what
  kotlinx-serialization would emit for the same payload (escaping differences are wire corruption).
- L4: no fake summaries — empty in, empty out; promote-to-text promotes MODEL content only.
- Honest failures: a failed/truncated stream must never masquerade as clean end_turn.

Review the CURRENT on-disk state (parallel sessions may be co-editing — \`git diff\`, \`git log
--oneline -10\`, and \`git status\` are available). SCOPE: ${args && args.scope === 'diff'
  ? 'concentrate on files changed vs the base branch (git diff --name-only origin/main...HEAD or the working tree) — this is a pre-merge review of recent work.'
  : 'the whole gateway/ tree.'}${args && args.focus ? ' EXTRA FOCUS: ' + args.focus : ''}

Discipline: cite exact file:line for every claim (read the file first — never cite from memory).
Report only findings of severity medium or higher, max 8, best first; skip style nits (detekt
covers those). Every finding needs a CONCRETE failure scenario: specific input/state -> specific
wrong behavior. Do NOT run gradle or any build/test commands (a shared daemon; concurrent runs
collide) — one dedicated agent does that. Do not modify any files.
`

const FINDINGS_SCHEMA = {
  type: 'object',
  required: ['findings'],
  properties: {
    findings: {
      type: 'array',
      maxItems: 8,
      items: {
        type: 'object',
        required: ['title', 'file', 'severity', 'category', 'description', 'failure_scenario'],
        properties: {
          title: { type: 'string' },
          file: { type: 'string' },
          line: { type: 'integer' },
          severity: { enum: ['critical', 'high', 'medium'] },
          category: { type: 'string' },
          description: { type: 'string' },
          failure_scenario: { type: 'string' },
          evidence: { type: 'string' },
        },
      },
    },
  },
}

const VERDICT_SCHEMA = {
  type: 'object',
  required: ['refuted', 'reasoning'],
  properties: {
    refuted: { type: 'boolean' },
    reasoning: { type: 'string' },
    severity_opinion: { enum: ['critical', 'high', 'medium', 'low', 'unchanged'] },
  },
}

const ARCH_SCHEMA = {
  type: 'object',
  required: ['verdict', 'strengths', 'problems'],
  properties: {
    verdict: { type: 'string' },
    strengths: { type: 'array', items: { type: 'string' } },
    problems: {
      type: 'array',
      maxItems: 10,
      items: {
        type: 'object',
        required: ['title', 'severity', 'description', 'recommendation'],
        properties: {
          title: { type: 'string' },
          severity: { enum: ['critical', 'high', 'medium', 'low'] },
          files: { type: 'array', items: { type: 'string' } },
          description: { type: 'string' },
          recommendation: { type: 'string' },
        },
      },
    },
  },
}

const GATE_SCHEMA = {
  type: 'object',
  required: ['green', 'summary'],
  properties: {
    green: { type: 'boolean' },
    summary: { type: 'string' },
    failures: { type: 'array', items: { type: 'string' } },
  },
}

const FINDERS = [
  {
    key: 'concurrency',
    prompt: `Hunt CONCURRENCY and CANCELLATION bugs. Focus: gateway/gateway/src/main/kotlin/splice/gateway/head/HeadServer.kt (turn drive, withContext(Job()), NonCancellable slot release, TurnPerf shared across reader/writer/watchdog), provider-spi Watchdog.kt (poller vs turn cancel races, fired-sentinel ordering), InflightGate.kt (FIFO admission, cancellation while queued, hot maxInflight), CoalescingSseWriter.kt (non-thread-safe fields — who calls it from where), SseEmitter.kt (frameBuf reuse across suspend points, started/ended flags), UpstreamClient.kt (retry loop vs coroutine cancel, single-flight refresh), UsageStore ring cache (lock coverage incl. file write outside/inside lock), Daemon/Main lifecycle (shutdown hook, daemon lock). Ask for each shared mutable field: which coroutines/threads touch it, and is every path ordered by happens-before?`,
  },
  {
    key: 'wire-parity',
    prompt: `Hunt WIRE-CONTRACT bugs on the Anthropic SSE surface. The hand-built hot-delta path in gateway/gateway/src/main/kotlin/splice/gateway/wire/SseEmitter.kt (hotDelta + appendJsonEscaped + writeRawFrame) claims byte-identity with kotlinx-serialization JsonObject.toString() — verify character by character: quotes, backslash, control chars, non-ASCII (kotlinx does NOT escape non-ASCII by default; does appendJsonEscaped match?), lone surrogates, DEL 0x7f, \\b and \\f (kotlinx emits \\b/\\f — does the hand escape?). Also SseReader.kt (UTF-8 carry across chunk edges, CR/LF handling, [DONE], data: prefix, line compaction), frame ordering guarantees (message_start lazy, ping, block pairing), UsageHud buildUsagePayload math (input minus cached clamp, used_percentage), terminalMessageJson. Compare against server/src/anthropic/sse.mjs where the PORT-OF header claims parity.`,
  },
  {
    key: 'stream-machine',
    prompt: `Hunt STREAM STATE MACHINE bugs. gateway/dialect-openai-responses/.../ResponsesStreamTranslator.kt: block lifecycle (eager tool open, lazy text/reasoning, int reasoning keys REASONING_KEY_BASE collisions with real output_index >= 1_000_000?), the NEW late-reasoning emission (emitReasoningItemText/appendLateReasoning — double emit vs deltas, thinkingBuf.contains(text) as dedup — false positives/negatives, separator handling), harvestFallback interplay with late emission, terminal decision (watchdog vs completed vs clientGone), usage capture, incomplete flag. Also dialect-openai-chat/ChatStreamTranslator.kt (delta vs message fold, applyFinalMessage textBuf.isEmpty guard, tool_calls index handling, finish_reason mapping) and gateway/pipeline/TurnPipeline.kt (promote/honesty/mirror ordering, compact gates, outcome tags).`,
  },
  {
    key: 'request-build',
    prompt: `Hunt REQUEST-BUILDING fidelity bugs. gateway/dialect-openai-responses/.../ResponsesRequestBuilder.kt: effort precedence chain (body fields > /effort budget > config > high) vs the documented v27 contract, visibility floor, grok clamp, summary resolution + spark reject regex, include vs replay split (includeEncryptedReasoning default true — is it correctly gated per provider? codex/grok/openai providers pass what?), replay decode path (redacted_thinking -> reasoning input item IN POSITION), compact stripping (tools, instructions, model/effort inheritance — the cache law), prompt_cache_key derivations, image/document handling incl. tool_result follow-up messages. ChatRequestBuilder.kt: message folding order (tool messages before user text), reasoning_effort emission. core/reasoning/Replay.kt envelope codec (tag/version pairing, byte-compat, the 'unnecessary safe call' warning at line ~65 hints at recent edits). Cross-check PORT-OF claims against server/src/codex/translate-request.mjs where suspicious.`,
  },
  {
    key: 'config-state',
    prompt: `Hunt CONFIG/STATE/PERSISTENCE bugs. core/config/ConfigService.kt (layer precedence defaults<-headOverrides(TOML)<-file<-env<-runtime, mtime cache staleness, patch persistence, coercion table, normalize floors), core/config/Knob.kt + core/topology/Topology.kt configOverrides (TOML key coverage vs knobs, [defaults] free-form map), app/TopologyLoader.kt (loadOrMaterialize, expandHome), gateway/usage/UsageHud.kt UsageStore (ring cache load-once vs external file changes, MAX_USAGE_FILE_BYTES treats-oversize-as-empty data loss, write inside runCatching but ring already mutated — divergence on write failure, prune comparisons), gateway/perf/PerfStats.kt + compact readJsonlTail (partial-line drop, unbounded growth), StatePaths contracts (frozen names), MgmtKey. Also: providers snapshot config at CONSTRUCTION (Daemon.start reads cfg once) — enumerate which knobs claim to be hot but are actually frozen per-boot (replayReasoning, showReasoning, summary, effort, authCacheMs...) and whether anything documents/handles that mismatch.`,
  },
  {
    key: 'error-paths',
    prompt: `Hunt ERROR-HANDLING and SILENT-FAILURE bugs. Sweep every runCatchingCancellable / try-catch in gateway/ production code: which swallow errors that should surface (auth refresh failures, file persistence, topology load, OAuth flows in app/)? HeadServer.driveOrEmitError catch set (UpstreamAuthMissing/UpstreamFailed/IOException — what escapes: SerializationException? IllegalStateException from Ktor? what happens to the SSE stream then?), UpstreamFailureClassifier mapping (which statuses/bodies map to retryable vs not; 401 after refresh spent; encrypted_content no-retry heuristic false positives — a 4xx whose error MESSAGE merely mentions encrypted_content), watchdog-fired vs client-gone vs truncated attribution in noCompletionOutcome, TurnPipeline honesty gates (empty compact, HONESTY_MIN), CompactStats/PerfStats/UsageStore best-effort writes (is telemetry loss detectable?), Main persistentLogger writer-drop-on-failure path, control ControlServer error responses. For each: concrete scenario where an operator sees a lie (success that wasn't) or silence (failure with no trace).`,
  },
  {
    key: 'providers-auth',
    prompt: `Hunt PROVIDER and AUTH bugs. provider-codex (CodexProvider extraHeaders account-id quirk, CodexAuthProvider authCacheMs caching, refresh single-flight + persistence, CodexOAuthEndpoints), provider-grok (GrokProvider quirks incl. session-id cache key + x-grok-conv-id lastSessionId @Volatile — cross-turn races between concurrent sessions on the same head: turn A's buildTurn then turn B's buildTurn then A's extraHeaders sends B's conv id?, GrokAuthProvider/GrokRefresh), provider-openai (ApiKeyAuthProvider env/file reads, OpenAiResponsesProvider/OpenAiChatProvider), app/Daemon.kt provider dispatch (which BuildOptions/config each provider passes — include vs replay wiring consistency across codex/grok/openai; missing includeEncryptedReasoning anywhere?), app/OAuthLoginFlow.kt + CodexRefresh/GrokRefresh (token endpoint, PKCE, port callbacks, error paths), and the kimi/anthropic-passthrough scaffolding state (settings.gradle entries vs module dirs vs Daemon wiring — will the build/daemon break if half-present?).`,
  },
  {
    key: 'leaks-resources',
    prompt: `Hunt RESOURCE LEAKS and unbounded growth. Ktor CIO client lifecycle (one shared client per head? per UpstreamClient? closed ever?), response body channels on early exit/cancel, watchdog poller cancel on ALL exits (exception paths in driveOneTurn — poller.cancel() only on the happy path?), InflightGate slot release NonCancellable coverage, SseEmitter/CoalescingSseWriter after respondTextWriter closes (writes to a dead Writer — exception type and where it lands), Netty embedded servers stop grace (100ms/500ms — in-flight turns on stop?), Main persistentLogger writer lifetime + shutdown flush, memory: ResponsesEventReducer blocks map growth on pathological streams, thinkingBuf/textBuf unbounded (multi-MB reasoning), perf JSONL + compact JSONL unbounded file growth (readers are tail-bounded but files grow forever — quantify), daemon.log growth (no rotation), UsageStore cachedRing bounds. Also check the deployed daemon runs with default JVM heap — any OOM risk math worth flagging.`,
  },
]

const ARCH_LENSES = [
  {
    key: 'invariants-boundaries',
    prompt: `ARCHITECTURE LENS 1 — module boundaries and invariant enforcement. Assess: does the module law (gateway/build-logic/src/main/kotlin/splice.module-law.gradle.kts) match reality on disk? Are L2/L3/L4 actually enforced in the Kotlin tree (compare .rules/kotlin-splice/*.yml walls vs what the code now does — e.g. the hand-built hot frames vs 'sole wire emitter' spirit, late-reasoning emission vs L2 single mirror)? Is :gateway still dialect-free? Is HeadServer accreting responsibilities (perf, usage, compact, launch...) toward god-object? Is the Provider SPI clean or leaking dialect concerns? Grade the boundary integrity and name the top structural risks with file evidence.`,
  },
  {
    key: 'duplication-drift',
    prompt: `ARCHITECTURE LENS 2 — duplication, drift, and half-migrations. Compare the two dialects (responses vs chat) and three+ providers for near-copy logic that will drift (the v29 lesson the headers cite). Audit PORT-OF slot headers: sample 6-8 files and check the header claims still match the code after today's rewrites (SseReader, SseEmitter, UpstreamClient, Compact, UsageHud especially — stale invariant text is drift debt). Map the include-vs-replay reasoning split: is it wired consistently across codex/grok/openai providers and both dialects, or half-migrated? Assess the kimi/anthropic-passthrough scaffold state. Assess test-to-code drift: which recent behaviors (late reasoning emission, ring cache, coalescing writer, hot deltas, TOML overrides) have real tests vs none. Name concrete consolidation moves.`,
  },
  {
    key: 'operational',
    prompt: `ARCHITECTURE LENS 3 — operational reality. Vendor-contract violations invisible to mock-based tests are the historical failure class here (stream_options and gzip-on-xai both shipped green in the past). Assess: what structural gap lets vendor-contract breakage through, and what's the cheapest wall (e.g. a request-shape allowlist test per provider pinned against recorded real requests? a canary turn on deploy? contract fixtures?). Also assess: deploy/restart story (manual cp + pkill — no versioned rollout, lock handling), config layering comprehensibility for an operator (five layers — is the TOML story coherent), observability coherence (turnLine vs cache line vs perf line vs shadow line — redundancy/gaps), daemon.log/state growth ops burden, and the parallel-session development mode (what process/tooling wall would catch a broken-in-tree state before deploy). Recommend the 3 highest-leverage operational fixes.`,
  },
]

phase('Gate')
const gateP = agent(
  CONTEXT + `
Your ONLY job: run the build gate ONCE and report ground truth. Execute:
cd /Users/marcos/dev/infra/splice/gateway && JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew check --console=plain
(allow up to 10 minutes). Then: green=true/false, summary one paragraph, failures = list of failing
task + first error lines (empty if green). Also note in summary whether the working tree changed
during the run (git status --short before and after).`,
  { label: 'gate:gradle-check', phase: 'Gate', schema: GATE_SCHEMA, effort: 'low' },
)

phase('Architecture')
const archP = parallel(
  ARCH_LENSES.map((l) => () =>
    agent(CONTEXT + l.prompt + `\nReturn verdict (one-paragraph overall judgment: how bad is it really), strengths, and problems (each with severity, files, recommendation).`, {
      label: `arch:${l.key}`,
      phase: 'Architecture',
      schema: ARCH_SCHEMA,
      effort: 'high',
    }),
  ),
)

phase('Find')
const finderResults = await parallel(
  FINDERS.map((f) => () =>
    agent(CONTEXT + f.prompt, {
      label: `find:${f.key}`,
      phase: 'Find',
      schema: FINDINGS_SCHEMA,
      effort: 'high',
    }).then((r) => (r && r.findings ? r.findings.map((x) => ({ ...x, finder: f.key })) : [])),
  ),
)

const all = finderResults.filter(Boolean).flat()
log(`finders returned ${all.length} raw findings`)

// Dedup across finders: same file + ~same region collapses (keep the highest severity copy).
const rank = { critical: 3, high: 2, medium: 1 }
const byKey = new Map()
for (const f of all) {
  const key = `${f.file}#${Math.floor((f.line || 0) / 40)}`
  const prev = byKey.get(key)
  if (!prev || (rank[f.severity] || 0) > (rank[prev.severity] || 0)) byKey.set(key, f)
}
const deduped = [...byKey.values()]
log(`${deduped.length} findings after dedup — verifying each with a 3-lens panel`)

const LENSES = [
  'CODE lens: read the cited file and surroundings; is the claimed defect actually in the code as described, or misread?',
  'REACHABILITY lens: is the failure scenario reachable with real Claude Code traffic through this daemon (real request shapes, real event orders), or only in theory?',
  'MITIGATION lens: is it already handled elsewhere — a guard upstream/downstream, a test pinning it, an invariant that prevents the state?',
]

const verified = await parallel(
  deduped.map((f) => () =>
    parallel(
      LENSES.map((lens) => () =>
        agent(
          CONTEXT + `
ADVERSARIALLY VERIFY this finding — your default is refuted=true unless the evidence forces
you to concede it is real. ${lens}

FINDING (from finder '${f.finder}'):
title: ${f.title}
file: ${f.file}${f.line ? ' line ~' + f.line : ''}
severity claimed: ${f.severity}
description: ${f.description}
failure_scenario: ${f.failure_scenario}
evidence: ${f.evidence || '(none given)'}

Read the actual code before judging. refuted=true kills it; also give severity_opinion.`,
          { label: `verify:${(f.file || '').split('/').pop()}`, phase: 'Verify', schema: VERDICT_SCHEMA, effort: 'medium' },
        ),
      ),
    ).then((votes) => {
      const valid = votes.filter(Boolean)
      const refutes = valid.filter((v) => v.refuted).length
      const confirmed = valid.length > 0 && refutes < 2 // kill on >=2 of 3 refutations
      return { ...f, confirmed, refutes, panel: valid.map((v) => v.reasoning.slice(0, 300)) }
    }),
  ),
)

const confirmedFindings = verified.filter(Boolean).filter((v) => v.confirmed)
const killed = verified.filter(Boolean).length - confirmedFindings.length
log(`${confirmedFindings.length} findings CONFIRMED, ${killed} killed by the panel`)

phase('Synthesize')
const gate = await gateP
const arch = (await archP).filter(Boolean)

const report = await agent(
  CONTEXT + `
You are the synthesis judge. Produce the final audit report as clean markdown for the operator
(direct, no hedging, severity-ranked). You get: the build-gate ground truth, adversarially
CONFIRMED bug findings (each survived a 3-lens refutation panel; 'refutes' = how many of 3 panels
still disagreed — treat refutes=1 as slightly less certain), and three architecture lens reports.

GATE: ${JSON.stringify(gate)}

CONFIRMED FINDINGS (${confirmedFindings.length}, ${killed} were killed in verification):
${JSON.stringify(confirmedFindings, null, 1)}

ARCHITECTURE LENSES:
${JSON.stringify(arch, null, 1)}

Write the report with sections: 1) Verdict — a straight two-paragraph answer to "is this codebase
a disaster?" grounded in the evidence; 2) Confirmed bugs — severity-ranked table + one paragraph
each (file:line, failure scenario, suggested fix); 3) Architecture assessment — merged from the
three lenses, deduplicated, worst first; 4) Top 5 actions — the highest-leverage moves in order,
each one sentence of what + why. Merge duplicate themes across lenses/findings. Where a finding
had refutes=1, mark it '(contested)'. Keep it under ~2500 words. Return ONLY the markdown.`,
  { label: 'synthesize:report', phase: 'Synthesize', effort: 'high' },
)

return {
  gate,
  confirmedCount: confirmedFindings.length,
  killedCount: killed,
  confirmed: confirmedFindings,
  architecture: arch,
  report,
}