// PORT-OF: splice/gateway/head/HeadServer.kt (HeadDeps, DEFAULT_MAX_REQUEST_BYTES,
// DEFAULT_REQUEST_READ_TIMEOUT_MS) @ 1caedd6 — invariants unchanged: the collaborator bundle a head
// is built from, and the two request-size defaults its callers name. Split out (HD-24) because it
// is a shared CONTRACT rather than a HeadServer private — TurnDriver and TurnDriveFactory take it
// whole, HeadServerFactory builds it — and because it is the single reason splice.head.perf,
// splice.head.usage, splice.core.util and splice.spi's runtime ports appeared in HeadServer.kt's
// import list at all.
package splice.head

import splice.core.budget.HeadBudget
import splice.core.config.Knob
import splice.core.config.RequestByteCap
import splice.core.model.ClientWindows
import splice.core.prompt.HeadSystemPrompt
import splice.core.prompt.SystemPromptLayers
import splice.core.session.ActivityAction
import splice.core.turn.LiveWatchdogBudget
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.core.util.MonoClock
import splice.core.version.ClientVersionTracker
import splice.head.admission.RequestMaterializationGate
import splice.head.admission.TurnQuota
import splice.head.compact.CompactStats
import splice.head.compact.ShadowClassifier
import splice.head.compaction.CompactionRecordings
import splice.head.compaction.CompactionTail
import splice.head.compaction.SessionProjectLookup
import splice.head.perf.PerfStats
import splice.head.turn.LiveTurns
import splice.head.usage.EconomicsStore
import splice.head.usage.QuotaTracker
import splice.head.usage.UsageStore
import splice.head.wire.TraceStore
import splice.head.wire.WireTap
import splice.sessions.prompt.SlotInstructions
import splice.upstream.Ticker
import splice.upstream.Waiter
import splice.upstream.codemode.ProcessTicker
import splice.upstream.codemode.ProcessWaiter
import splice.upstream.credentials.AccountPool
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient

/** Was `HeadDeps.DEFAULT_MAX_REQUEST_BYTES` / `HeadDeps.DEFAULT_REQUEST_READ_TIMEOUT_MS`
 *  (companion consts), then file-scope consts of those names; V4-210 renamed them for what they now
 *  are, values read off the Knob.
 *
 *  V4-150: `internal`, which is the module boundary stated rather than a narrowing. These are DEFAULTS
 *  for two members of the [HeadDeps.HeadPolicy] bundle and nothing outside :daemon-head ever named them —
 *  the only other consumer is HeadServerFailureBranchTest, in this module's own test source set, where
 *  `internal` is visible. Unlike the five declarations V4-104 had to keep, these are CONSTANTS and not
 *  types, so the inference escape does not apply: a default expression is not part of a signature, and
 *  no consumer can bind one by inference. The compiler is the judge of that and it agrees — a public
 *  value parameter defaulting to an internal const is legal Kotlin, which is exactly what makes these
 *  two burnable where the others were not.
 *
 *  V4-210: both READ the Knob (MAX_REQUEST_BYTES, REQUEST_READ_TIMEOUT_MS), the operator-facing
 *  source HeadServerFactory already reads per head. A re-typed default was a KNOB-SHADOW that the
 *  const-single-source law missed only because it could not read a default written by name. */
internal val defaultMaxRequestBytes: Int = Knob.MAX_REQUEST_BYTES.count().toInt()

internal val defaultRequestReadTimeoutMs: Long = Knob.REQUEST_READ_TIMEOUT_MS.count()

/** How long a stop waits for in-flight turns to finish before it cuts them: above a 16s deepseek turn, and the
 *  innermost budget of the daemon's stop ladder (see DaemonStopBudgetTest for the ordering that keeps each link
 *  below the next). A test passes a short one through [HeadDeps.HeadPolicy.stopDrainMs]. */
internal const val DEFAULT_STOP_DRAIN_MS = 45_000L

/** Collaborators the head needs, bundled to keep the constructor lean. */
public data class HeadDeps(
    /** WHERE TURNS GO: the upstream client, the gate that admits them, and the registry of those in flight. */
    val traffic: HeadTraffic,
    /** The two bearer credentials the head's routes accept, one per role. */
    val tokens: HeadTokens,
    /** WHAT THE HEAD STORES (V4-105 item 1): everything it writes observations into. */
    val stores: HeadStores,
    /** WHICH ACCOUNT A TURN SPENDS: the quota trackers, and the pool that decides eligibility. */
    val quotaBundle: HeadQuota,
    /** THE SUBSTITUTABLE SEAMS: exactly what a deterministic test replaces, which is why they are
     *  one bundle — a seam is one port for the same reason. */
    val seams: HeadSeams,
    /** READ-ONCE VALUES: nothing here is derived from a turn. */
    val policy: HeadPolicy,
    val log: LogSink,
) {
    /** The single resolver for which quota tracker a turn reads (V4-99): the SELECTED account's
     *  tracker, else the primary's. Read through the bundle, which alone holds the trackers. */
    internal val turnQuota: TurnQuota get() = quotaBundle.turnQuota

    init {
        require(tokens.inferenceToken.isNotBlank()) { "inferenceToken must not be blank" }
        require(tokens.operatorToken.isNotBlank()) { "operatorToken must not be blank" }
        // The split is the security property (v0.4.0): a head wired with ONE key for both roles hands
        // every session the operator routes again, so that wiring is refused rather than served.
        require(tokens.operatorToken != tokens.inferenceToken) { "operatorToken must differ from inferenceToken" }
        // Read ONCE here, on what is configured now: a wiring that starts with a nonsense ceiling is refused at
        // construction, and a later PATCH to a nonsense value is refused by the knob's own parse.
        require(policy.requestReadTimeoutMs() > 0) { "requestReadTimeoutMs must be positive" }
        require(!policy.mirrorReasoning) { "mirrorReasoning is operator-locked off" }
    }

    /** The head's two bearer credentials, one per role. */
    public data class HeadTokens(
        /** Per-install bearer used by local Claude clients for their TURNS (the turn key). Never use a
         *  source-known sentinel here. */
        val inferenceToken: String,
        /** The management key: the ONLY credential this head's operator routes accept (GET /wire), and
         *  still accepted for turns so a session launched before the v0.4.0 key split keeps working. It
         *  is never what a launched client is handed — that is [inferenceToken] — so a session's
         *  environment cannot read what the head sent upstream on other sessions' behalf. */
        val operatorToken: String,
    )

    /** Where a head's turns go and what is in flight: the client that posts them upstream, the gate that admits
     *  them, and the registry of the live streaming ones. */
    public data class HeadTraffic(
        val upstream: UpstreamClient,
        val gate: InflightGate,
        /** V4-319: the head's live streaming turns, which the console lists and the operator stops, and
         *  the marks that refuse a stopped turn's re-send. Beside [gate] because it is keyed by the gate's
         *  slots. No default, like the bundles below: a head built without the daemon's registry would run
         *  turns no console can see or stop, and compile. */
        val liveTurns: LiveTurns,
        /** The watchdog budget as the live knobs (firstByteTimeoutMs, stallReanchorMs) say it is now: a turn asks it
         *  when it starts. Null is no live knob: the budget the provider was built with. */
        val watchdog: LiveWatchdogBudget? = null,
    )

    /** Where the head writes what it observes. Grouped by ROLE — a bundle is a boundary, not a bag. */
    public data class HeadStores(
        val usageStore: UsageStore,
        val perfStats: PerfStats,
        val economicsStore: EconomicsStore?,
        val compaction: HeadCompaction,
        val shadow: ShadowClassifier,
        val clientWindows: ClientWindows,
        val captures: HeadCaptures,
    )

    /** What the head keeps about compactions. */
    public data class HeadCompaction(
        val compactStats: CompactStats,
        /** V4-216: where a compaction's answer waits for its retry across a daemon restart. No
         *  default: a head built without it would replay only within one process, and compile. */
        val compactionRecordings: CompactionRecordings,
    )

    /** The head's opt-in captures of what crosses it. Both are NULL IS OFF, with no default, so a construction site
     *  that forgets cannot get a tap or a trace. */
    public data class HeadCaptures(
        /** V4-173: the head's opt-in ring of upstream request bodies. NULL IS OFF, and it is the
         *  default for every head an operator has not named a count for — a nullable with no
         *  default, like [HeadStores.economicsStore]. */
        val wireTap: WireTap?,
        /** V4-174: the head's opt-in full request/response trace. NULL IS OFF, the same law as
         *  [wireTap]: no default. */
        val trace: TraceStore?,
    )

    /** Which account a turn spends, and the trackers that decide eligibility. */
    public data class HeadQuota(
        /** The trackers are private on purpose: the only way to read one is [turnQuota], which owns the precedence
         *  (selected account first, the primary after), so no site can re-derive it. */
        private val quota: QuotaTracker?,
        val accountPool: AccountPool?,
        private val accountQuotas: Map<String, QuotaTracker>,
        /** V4-133 review: the head's daily spend budget, which decides eligibility in USD the way
         *  the trackers do in quota. No default, like every member of this bundle: a head built
         *  without one says so ([splice.core.budget.NoHeadBudget]) rather than forgetting. */
        val budget: HeadBudget,
        val credentialAccountNames: CredentialAccountNames,
    ) {
        /** The one resolver for which quota tracker a turn reads (V4-99). */
        internal val turnQuota: TurnQuota = TurnQuota(accountPool, accountQuotas, quota)

        /** A forwarded caller alone uses the legacy path until a stored member is published. */
        val activePool: AccountPool? get() = accountPool?.takeIf { it.active }
    }

    /** A proved account name for the effective credential digest, never the credential or a head alias.
     *  Null means no identity was proved. Implementations never throw or expose credential values. */
    public fun interface CredentialAccountNames {
        public fun forCredential(key: String): String?

        /** The digest of a credential this head just sent on an attempt for [session], never the credential; [session]
         *  is null for a request that named none. A resolver that only names ignores it; the daemon's per-head one
         *  tells the native login owner which login carries the head and that session. */
        public fun sent(key: String, session: String?): Unit = Unit
    }

    /** The substitutable runtime seams. A test drives these instead of sleeping or reading a clock,
     *  which is why they are one bundle: they are the things a deterministic test REPLACES. */
    public data class HeadSeams(
        val clock: ElapsedClock = ElapsedClock(MonoClock::nowMs),
        val waiter: Waiter = ProcessWaiter(),
        val ticker: Ticker = ProcessTicker(),
        val requestMaterializationGate: RequestMaterializationGate = RequestMaterializationGate(),
        val clientVersions: ClientVersionTracker = ClientVersionTracker(),
        val session: SessionSeams = SessionSeams(),
        /** V4-134: where this head reports lifecycle, turn and account events for the console. The
         *  default reports to nobody, like every seam in this bundle; HeadServerFactory gives every
         *  production head a real one, and HeadEventsTest fails if a served turn stops reaching it. */
        val events: HeadEvents = NoHeadEvents,
    )

    /** What a head resolves per client session: its working directory, the team-slot text appended after its layers
     *  and the custom compaction text, each of which a test replaces with a fixed answer. */
    public data class SessionSeams(
        /** V4-124: a session id to its working directory, for the per-project prompt layers. The
         *  default knows no session, so a head built without it carries the head layer only. */
        val sessionProject: SessionProjectLookup = SessionProjectLookup { null },
        /** V4-131: the per-session team-slot text appended after the head's layers (SlotInstructions).
         *  Null (tests, tools) appends nothing; HeadServerFactory gives every production head the
         *  daemon's one resolver, pinned by that row's tests. */
        val slotInstructions: SlotInstructions? = null,
        val compactionTail: CompactionTail = CompactionTail(),
    )

    /** What an operator sets, never what a turn produces — which is what makes it policy rather than state. Most of
     *  it is read once, at start. A field whose type is a READER is asked at every request instead, so an operator
     *  who changes that knob while the daemon runs is obeyed by the next request rather than at the next restart;
     *  either way no turn can change it, and two turns asking at the same moment get the same answer. */
    public data class HeadPolicy(
        val maxRequestBytes: RequestByteCap = RequestByteCap { defaultMaxRequestBytes },
        val requestReadTimeoutMs: RequestReadBudgetMs = RequestReadBudgetMs { defaultRequestReadTimeoutMs },
        val stopDrainMs: Long = DEFAULT_STOP_DRAIN_MS,
        val mirrorReasoning: Boolean = false,
        val progressLine: Boolean = true,
        val forwardClientAuth: Boolean = false,
        /** V4-124: the head layer plus any per-project layers, resolved per turn against the
         *  session's working directory ([SessionSeams.sessionProject]). No projects = the head layer
         *  alone, which is exactly V4-36's bytes. */
        val systemPrompt: SystemPromptLayers = SystemPromptLayers(HeadSystemPrompt()),
    )
}

// V4-134, FEATURES.md §6 — HeadEvents: what a head tells the console, at the seams that already hold each
// fact. :daemon-head cannot see :daemon-control's EventBus (its build depends on :core and :upstream only),
// so a head reports through this interface and :app adapts it to the bus, once, in
// ConsoleEventPublisher. Nothing here is a new probe: every call site is a line that already knew
// the fact — the head lifecycle mutex, the ready turn's admission, the one perf-row emitter.
//
// ONE INSTANCE PER HEAD, keyed by :app when it builds that head's HeadSeams, so no call site passes
// its head key and none can pass the wrong one.
//
// EVERY METHOD MUST RETURN AT ONCE AND NEVER THROW: they run on the turn path and inside the
// lifecycle lock, and the console is an observer — a slow or broken observer must lose events, never
// stall a turn or a restart. The bus behind the production implementation drops rather than waits
// (EventBus.publish).
//
// V4-130 ADDED THREE METHODS, ALL ABSTRACT: messageSent, activityLabel, labelQueryUpstream. None has a
// default no-op body, on purpose: a defaulted method is how a producer forgets a family. The V4-126
// EventBus default was exactly that (a family nobody produced, silently), and V4-134 removed it; an
// implementer of this interface must now say what it does with each fact, even if that is nothing.
//
// WHY IN THIS FILE: HeadSeams.events is the interface's only holder, and every other seam a head is
// built with is declared here; the seam's type sits beside the field that carries it.

/** The head lifecycle states the console shows (`head.state`'s `state` field). */
public enum class HeadLifecycle(public val wire: String) {
    /** The engine is bound and admission is open. */
    STARTED("started"),

    /** Admission is closed and in-flight turns are draining; the engine is still up. */
    DRAINING("draining"),

    /** The engine is down. */
    STOPPED("stopped"),
}

public interface HeadEvents {
    /** The head moved to [state]. */
    public fun lifecycle(state: HeadLifecycle)

    /** A turn was admitted and is about to be served or refused with a perf row. Fired for exactly
     *  the turns that end in [turnEnded], so a console can pair the two. [session] is the client's
     *  full session id, or null for a request that carried none. */
    public fun turnStarted(session: String?)

    /** A turn's perf row was written. [perfRowId] is that row's `ts`, the key /api/perf/turns
     *  reports it under (PerfRoutes); [outcome] is the row's outcome tag, verbatim; [session] is the
     *  session [turnStarted] named for the same turn, so a console hears from a session when its turn
     *  ends as well as when it starts. */
    public fun turnEnded(perfRowId: String, outcome: String, session: String?)

    /** The account pool moved this turn to another account. [from] is null when the pool had no
     *  previous choice for the session. */
    public fun accountSwitched(from: String?, to: String)

    /** V4-130: [session] called SendMessage addressed to [to] (an address or a name, verbatim from the
     *  tool_use input). [toolUseId] is that block's id, unique per call. Reported once per call, from
     *  the request that carries the assistant turn which made it (MessageEdges). */
    public fun messageSent(session: String, to: String, toolUseId: String)

    /** V4-130: the head answered Claude Code's activity side query locally with [action]. [session] is
     *  null for a request that carried no session header. */
    public fun activityLabel(session: String?, action: ActivityAction)

    /** V4-130: a request that looks like the activity side query but did not match its opening went
     *  upstream as an ordinary turn (ActivityLabel.looksLikeSideQuery). Counted so an empty label
     *  history can name a client mismatch instead of reading as an idle session. */
    public fun labelQueryUpstream(session: String?)
}

/** The head that reports to nobody: a head built without a console (tests, tools). Production heads
 *  get a real one from HeadServerFactory, and HeadEventsTest fails if a turn stops reaching it. */
public object NoHeadEvents : HeadEvents {
    override fun lifecycle(state: HeadLifecycle): Unit = Unit

    override fun turnStarted(session: String?): Unit = Unit

    override fun turnEnded(perfRowId: String, outcome: String, session: String?): Unit = Unit

    override fun accountSwitched(from: String?, to: String): Unit = Unit

    override fun messageSent(session: String, to: String, toolUseId: String): Unit = Unit

    override fun activityLabel(session: String?, action: ActivityAction): Unit = Unit

    override fun labelQueryUpstream(session: String?): Unit = Unit
}
