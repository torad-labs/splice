export const meta = {
  name: 'gateway-craft-review',
  description: 'Quality-only craft review of the splice gateway (NOT bugs): god objects, duplication/drift, primitive obsession, premature optimization, misleading names. Fans out review lenses over cohesive subsystems, synthesizes a ranked report with concrete refactors.',
  phases: [
    { title: 'Review', detail: 'one craft reviewer per subsystem' },
    { title: 'Synthesize', detail: 'ranked, deduped craft report' },
  ],
}

// A craft review is DISTINCT from a bug hunt (see gateway-audit for the latter): it never reports
// correctness defects, only maintenance pain — the class of "no-bug-but-still-shitty-code" that a
// linter cannot see. detekt already covers the mechanical floor (god class, long method, magic
// number); this finds the architectural craft on top.
const CONTEXT = `
You are a read-only CRAFT reviewer of the splice Kotlin gateway at /Users/marcos/dev/infra/splice.
Do NOT modify files. Do NOT run gradle/npm (a shared build daemon; concurrent runs collide).

THIS IS NOT A BUG HUNT. Correctness is audited separately (gateway-audit). Report ONLY code-quality
/ craft problems, ranked by how much they will hurt maintenance:
- god objects / low cohesion / methods that should decompose
- near-duplicate logic that will drift — the repo's explicit doctrine is "port the proven neighbor,
  don't invent; copies drift (the v29 lesson)". Quantify overlap; name what to EXTRACT and where.
- primitive obsession, data-class bags threaded everywhere, stringly-typed dispatch
- leaky abstractions / logic in the wrong layer / module-boundary smells (the module law:
  gateway/build-logic/src/main/kotlin/splice.module-law.gradle.kts)
- premature/micro-optimization that trades real readability for unmeasured gains — call it out;
  is the speed worth the risk surface?
- inefficiency NOT caught by complexity rules (needless allocation/copies, re-parsing, repeated
  file reads, O(n) where O(1) fits)
- misleading names, comments that lie or merely restate the code, dead abstractions

For each finding: file:line, severity (high/med/low = MAINTENANCE PAIN, not correctness), a
one-sentence problem, and a CONCRETE refactor (what to extract/rename/collapse). Read the actual
code before claiming anything. Max 10 per reviewer, worst first. Also list what is genuinely
CLEAN / well-crafted so it is not "fixed". No correctness claims.
`

const REVIEW_SCHEMA = {
  type: 'object',
  required: ['findings', 'clean'],
  properties: {
    findings: {
      type: 'array',
      maxItems: 10,
      items: {
        type: 'object',
        required: ['title', 'file', 'severity', 'problem', 'refactor'],
        properties: {
          title: { type: 'string' },
          file: { type: 'string' },
          line: { type: 'integer' },
          severity: { enum: ['high', 'medium', 'low'] },
          problem: { type: 'string' },
          refactor: { type: 'string' },
        },
      },
    },
    clean: { type: 'array', items: { type: 'string' } },
  },
}

// Cohesive subsystems, each reviewed by one agent. Durable groupings for this gateway; add more
// via args.extra (each { key, prompt }).
const LENSES = [
  {
    key: 'providers-dialects',
    prompt: `Focus DUPLICATION and DRIFT across the providers + Daemon wiring: provider-codex/grok/openai
providers, the shared ResponsesProvider base (dialect-openai-responses), app/Daemon.kt dispatch +
assembleHead builders, ResponsesRequestBuilder (include-vs-replay, quirks.withToml, resolveSummary/
resolveEffort). Is the "one builder parameterized by Quirks" doctrine UPHELD or VIOLATED? Quantify
any copy-pasted glue; name the shared factory/base to extract. Flag names that mean two things.`,
  },
  {
    key: 'perf-sse-hotpath',
    prompt: `Focus the HOT PATH: core/perf/TurnPerf, gateway/perf/PerfStats, gateway/wire/SseEmitter +
ImmediateSseWriter, provider-spi SseReader + UpstreamClient + InflightGate, gateway/usage/UsageHud,
gateway/compact/Compact. Judge whether each "performance" construct (reused decode scratch,
hand-built JSON frames, in-memory rings, coalescing) EARNS its complexity or just obfuscates. Flag
two-source-of-truth parsers, premature micro-opt, comments that oversell ("zero-copy" that isn't),
over/under-engineered thread-safety, and JSONL-sink duplication.`,
  },
  {
    key: 'dialects-honesty',
    prompt: `Focus the three stream translators + request builders (dialect-openai-responses,
dialect-openai-chat, dialect-anthropic-passthrough): duplicated honest-catch skeletons, JSON scalar
accessors (str/num/intOr) copied with semantic divergence (JsonNull-safe vs not), truncated/stalled
terminal-outcome construction repeated per provider-tag, MfjsSanitizer recursion/blocklist shape,
misleading vocabulary drift across the family. Name what to extract to provider-spi/core vs. what
should stay deliberately separate and why.`,
  },
  {
    key: 'auth-oauth',
    prompt: `Focus auth/oauth/login across provider-codex/grok/kimi + app (CodexRefresh, GrokRefresh,
KimiRefresh, OAuthLoginFlow, DeviceLoginFlow, LoginIo). Hunt: percent-encoder/formEncode copies,
secure-credential-write copies (which is the weak non-atomic variant?), token-response parsers with
divergent missing-field policy, JsonNull-safe helpers, stringly-typed auth.kind dispatch fanned
across files, dead abstractions (persisted-but-never-sent identity). Name the shared util to extract.`,
  },
]

phase('Review')
const lenses = [...LENSES, ...(args && Array.isArray(args.extra) ? args.extra : [])]
const focus = args && args.focus ? `\n\nEXTRA FOCUS for this run: ${args.focus}` : ''
const reviews = await parallel(
  lenses.map((l) => () =>
    agent(CONTEXT + l.prompt + focus, {
      label: `craft:${l.key}`,
      phase: 'Review',
      schema: REVIEW_SCHEMA,
      effort: 'high',
    }).then((r) => (r ? { key: l.key, ...r } : null)),
  ),
)

const valid = reviews.filter(Boolean)
const totalFindings = valid.reduce((n, r) => n + (r.findings ? r.findings.length : 0), 0)
log(`${valid.length}/${lenses.length} reviewers returned; ${totalFindings} raw craft findings`)

phase('Synthesize')
const report = await agent(
  `You are the craft-review synthesis judge for the splice gateway. You receive per-subsystem craft
findings (quality, NOT bugs) with concrete refactors. Produce clean markdown for the maintainer.

REVIEWS:
${JSON.stringify(valid, null, 1)}

Write: 1) Verdict — is the craft healthy or drifting, in two paragraphs grounded in the findings;
2) Cross-cutting themes FIRST — the duplication/drift and naming hazards that recur across
subsystems (these are the highest-leverage: one extraction kills N findings), each with the concrete
shared-util/base to extract; 3) Per-subsystem findings, severity-ranked, deduped, worst first
(file:line, problem, refactor); 4) What is genuinely well-crafted (do not "fix" it); 5) Top 5
refactors in order — what + why (one sentence each), preferring extractions that collapse the most
duplication. Merge duplicate themes. Keep under ~2500 words. Return ONLY the markdown.`,
  { label: 'synthesize:craft', phase: 'Synthesize', effort: 'high' },
)

return { reviewers: valid.length, totalFindings, reviews: valid, report }
