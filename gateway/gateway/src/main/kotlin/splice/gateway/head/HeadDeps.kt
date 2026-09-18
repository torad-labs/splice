// PORT-OF: splice/gateway/head/HeadServer.kt (HeadDeps, DEFAULT_MAX_REQUEST_BYTES,
// DEFAULT_REQUEST_READ_TIMEOUT_MS) @ 1caedd6 — invariants unchanged: the collaborator bundle a head
// is built from, and the two request-size defaults its callers name. Split out (HD-24) because it
// is a shared CONTRACT rather than a HeadServer private — TurnDriver and TurnDriveFactory take it
// whole, HeadServerFactory builds it — and because it is the single reason splice.gateway.perf,
// splice.gateway.usage, splice.core.util and splice.spi's runtime ports appeared in HeadServer.kt's
// import list at all.
package splice.gateway.head

import splice.core.model.ClientWindows
import splice.core.prompt.HeadSystemPrompt
import splice.core.prompt.SystemPromptLayers
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.core.util.MonoClock
import splice.core.version.ClientVersionTracker
import splice.gateway.compact.CompactStats
import splice.gateway.compact.ShadowClassifier
import splice.gateway.perf.PerfStats
import splice.gateway.usage.EconomicsStore
import splice.gateway.usage.QuotaTracker
import splice.gateway.usage.UsageStore
import splice.spi.AccountPool
import splice.spi.InflightGate
import splice.spi.ProcessTicker
import splice.spi.ProcessWaiter
import splice.spi.Ticker
import splice.spi.UpstreamClient
import splice.spi.Waiter

/** Was `HeadDeps.DEFAULT_MAX_REQUEST_BYTES` / `HeadDeps.DEFAULT_REQUEST_READ_TIMEOUT_MS`
 *  (companion consts); same names, now at file scope in the same package.
 *
 *  V4-150: `internal`, which is the module boundary stated rather than a narrowing. These are DEFAULTS
 *  for two members of the [HeadDeps.HeadPolicy] bundle and nothing outside :gateway ever named them —
 *  the only other consumer is HeadServerFailureBranchTest, in this module's own test source set, where
 *  `internal` is visible. Unlike the five declarations V4-104 had to keep, these are CONSTANTS and not
 *  types, so the inference escape does not apply: a default expression is not part of a signature, and
 *  no consumer can bind one by inference. The compiler is the judge of that and it agrees — a public
 *  value parameter defaulting to an internal const is legal Kotlin, which is exactly what makes these
 *  two burnable where the others were not. */
internal const val DEFAULT_MAX_REQUEST_BYTES: Int = 8 * 1024 * 1024

internal const val DEFAULT_REQUEST_READ_TIMEOUT_MS: Long = 30_000

/** Collaborators the head needs, bundled to keep the constructor lean. */
public data class HeadDeps(
    val upstream: UpstreamClient,
    /** Per-install bearer used by local Claude clients. Never use a source-known sentinel here. */
    val inferenceToken: String,
    val gate: InflightGate,
    /** WHAT THE HEAD STORES (V4-105 item 1): everything it writes observations into. */
    val stores: HeadStores,
    /** WHICH ACCOUNT A TURN SPENDS: the quota trackers, and the pool that decides eligibility. */
    val quotaBundle: HeadQuota,
    /** THE SUBSTITUTABLE SEAMS: exactly what a deterministic test replaces, which is why they are
     *  one bundle — a seam is one port for the same reason. */
    val seams: HeadSeams,
    /** READ-ONCE VALUES: nothing here is derived from a turn. */
    val policy: HeadPolicy,
    val compactionTail: CompactionTail = CompactionTail(),
    val log: LogSink,
) {
    /** The single resolver for which quota tracker a turn reads (V4-99): the SELECTED account's
     *  tracker, else the primary's. A body property, not a constructor param, so it is not part of
     *  the data class's equals/copy — it is a derived collaborator, not a value. */
    internal val turnQuota: TurnQuota =
        TurnQuota(quotaBundle.accountPool, quotaBundle.accountQuotas, quotaBundle.quota)

    init {
        require(inferenceToken.isNotBlank()) { "inferenceToken must not be blank" }
        require(policy.requestReadTimeoutMs > 0) { "requestReadTimeoutMs must be positive" }
        require(!policy.mirrorReasoning) { "mirrorReasoning is operator-locked off" }
    }

    /** Where the head writes what it observes. Grouped by ROLE — a bundle is a boundary, not a bag. */
    public data class HeadStores(
        val usageStore: UsageStore,
        val perfStats: PerfStats,
        val economicsStore: EconomicsStore?,
        val compactStats: CompactStats,
        val shadow: ShadowClassifier,
        val clientWindows: ClientWindows,
    )

    /** Which account a turn spends, and the trackers that decide eligibility. */
    public data class HeadQuota(
        val quota: QuotaTracker?,
        val accountPool: AccountPool?,
        val accountQuotas: Map<String, QuotaTracker>,
    )

    /** The substitutable runtime seams. A test drives these instead of sleeping or reading a clock,
     *  which is why they are one bundle: they are the things a deterministic test REPLACES. */
    public data class HeadSeams(
        val clock: ElapsedClock = ElapsedClock(MonoClock::nowMs),
        val waiter: Waiter = ProcessWaiter(),
        val ticker: Ticker = ProcessTicker(),
        val requestMaterializationGate: RequestMaterializationGate = RequestMaterializationGate(),
        val clientVersions: ClientVersionTracker = ClientVersionTracker(),
        /** V4-124: a session id to its working directory, for the per-project prompt layers. The
         *  default knows no session, so a head built without it carries the head layer only. */
        val sessionProject: SessionProjectLookup = SessionProjectLookup { null },
        /** V4-134: where this head reports lifecycle, turn and account events for the console. The
         *  default reports to nobody, like every seam in this bundle; HeadServerFactory gives every
         *  production head a real one, and HeadEventsTest fails if a served turn stops reaching it. */
        val events: HeadEvents = NoHeadEvents,
    )

    /** Read-once values. Nothing here is derived from a turn, which is what makes it policy rather
     *  than state: an operator sets it and every turn reads the same answer. */
    public data class HeadPolicy(
        val maxRequestBytes: Int = DEFAULT_MAX_REQUEST_BYTES,
        val requestReadTimeoutMs: Long = DEFAULT_REQUEST_READ_TIMEOUT_MS,
        val mirrorReasoning: Boolean = false,
        val progressLine: Boolean = true,
        val forwardClientAuth: Boolean = false,
        /** V4-124: the head layer plus any per-project layers, resolved per turn against the
         *  session's working directory ([HeadSeams.sessionProject]). No projects = the head layer
         *  alone, which is exactly V4-36's bytes. */
        val systemPrompt: SystemPromptLayers = SystemPromptLayers(HeadSystemPrompt()),
    )
}

// V4-134, FEATURES.md §6 — HeadEvents: what a head tells the console, at the seams that already hold each
// fact. :gateway cannot see :control's EventBus (its build depends on :core and :provider-spi only),
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
     *  reports it under (PerfRoutes); [outcome] is the row's outcome tag, verbatim. */
    public fun turnEnded(perfRowId: String, outcome: String)

    /** The account pool moved this turn to another account. [from] is null when the pool had no
     *  previous choice for the session. */
    public fun accountSwitched(from: String?, to: String)

    /** V4-130: [session] called SendMessage addressed to [to] (an address or a name, verbatim from the
     *  tool_use input). [toolUseId] is that block's id, unique per call. Reported once per call, from
     *  the request that carries the assistant turn which made it (MessageEdges). */
    public fun messageSent(session: String, to: String, toolUseId: String)

    /** V4-130: the head answered Claude Code's activity side query locally with [label]. [session] is
     *  null for a request that carried no session header. */
    public fun activityLabel(session: String?, label: String)

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

    override fun turnEnded(perfRowId: String, outcome: String): Unit = Unit

    override fun accountSwitched(from: String?, to: String): Unit = Unit

    override fun messageSent(session: String, to: String, toolUseId: String): Unit = Unit

    override fun activityLabel(session: String?, label: String): Unit = Unit

    override fun labelQueryUpstream(session: String?): Unit = Unit
}
