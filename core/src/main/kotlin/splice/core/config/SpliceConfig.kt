// PORT-OF: server/src/config.mjs getConfig's returned view @ pre-public-port-baseline — the TYPED
// ACCESSOR surface over the merged+normalized knob map. Almost all of it is one repetition: read a
// Knob key, widen to the declared type. Two members are NOT accessors and carry real policy, so
// they must travel with the table rather than being "simplified" into it:
//   · [foldReasoningModels] — the comma-split model set; EMPTY MEANS THE FEATURE IS OFF, so a
//     blank/absent knob must yield an empty set and never a set containing "".
//   · [statuslineGitRoots] — the colon-split TRUST BOUNDARY: relative segments are DROPPED, so a
//     roots list can never walk out of an absolute path.
// The constructor stays `internal`: ConfigService is the only thing allowed to mint one, because
// only a map that went through ConfigCoercion.normalize satisfies these getters' assumptions
// (a raw map would read pre-clamp values and silently hand out un-floored timeouts).
package splice.core.config

import splice.core.perf.HISTORY_DEFAULT_DAYS
import splice.core.perf.HistoryWindow
import splice.core.perf.HistoryWindowWords
import splice.core.turn.ReasoningDisplay
import splice.core.turn.ReasoningDisplayParser

/** The largest request body the gateway will decode, in bytes (knob `maxRequestBytes`), ASKED FOR AT EVERY REQUEST
 *  and never captured at construction: an operator who raises the cap while the daemon runs is obeyed by the next
 *  request. It lives in core because TWO layers enforce the one knob — the head path and the ingress guard in front
 *  of it — and a cap that is live in one of them and stale in the other is worse than one that needs a restart. */
public fun interface RequestByteCap {
    public operator fun invoke(): Int
}

/** Typed view over the merged+normalized map. */
public class SpliceConfig internal constructor(
    private val m: Map<String, Any?>,
    private val fresh: FreshConfig,
) {
    /** This view's own effective config read again now: a value a change must reach without a restart is read
     *  through it, where the view held at boot keeps the answer it had. */
    public fun current(): SpliceConfig = fresh.read()

    public val port: Int get() = long(Knob.PORT).toInt()
    public val chatgptApiBase: String get() = string(Knob.CHATGPT_API_BASE).orEmpty()
    public val codexAuthPath: String get() = string(Knob.CODEX_AUTH_PATH).orEmpty()
    public val pinnedModel: String get() = string(Knob.PINNED_MODEL).orEmpty()
    public val effort: String? get() = string(Knob.EFFORT)
    public val summary: String? get() = string(Knob.SUMMARY)
    public val showReasoning: ReasoningDisplay get() = ReasoningDisplayParser.from(string(Knob.SHOW_REASONING))
    public val replayReasoning: Boolean get() = bool(Knob.REPLAY_REASONING)
    public val mirrorReasoning: Boolean get() = bool(Knob.MIRROR_REASONING)
    public val progressLine: Boolean get() = bool(Knob.PROGRESS_LINE)

    // Reasoning-continuation folding (codex 518n-2). Models is a comma list → set; empty = feature off.
    public val foldReasoningModels: Set<String>
        get() = string(Knob.FOLD_REASONING_MODELS).orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    public val foldMaxContinue: Int get() = long(Knob.FOLD_MAX_CONTINUE).toInt()
    public val foldMarkerText: String get() = string(Knob.FOLD_MARKER_TEXT).orEmpty()
    public val foldMaxTier: Int get() = long(Knob.FOLD_MAX_TIER).toInt()
    public val maxInflight: Int get() = long(Knob.MAX_INFLIGHT).toInt()
    public val maxQueued: Int get() = long(Knob.MAX_QUEUED).toInt()

    /** How long one client request body read may take. Read LIVE, per read, through the head's RequestReadBudgetMs,
     *  so a PATCH moves the ceiling of the next read and no restart is named for it. */
    public val requestReadTimeoutMs: Long get() = long(Knob.REQUEST_READ_TIMEOUT_MS)

    /** The largest request body this gateway will decode; past it the client is answered 413. Read LIVE, per
     *  request, through a [RequestByteCap] — by the head path and by the ingress guard in front of it, which is why
     *  the reader's type lives here in core rather than beside either of them. */
    public val maxRequestBytes: Int get() = long(Knob.MAX_REQUEST_BYTES).toInt()

    /** Live console switch for reading Claude Code's already-written redacted transcript (V4-354). */
    public val transcriptView: Boolean get() = bool(Knob.TRANSCRIPT_VIEW)
    public val upstreamRetries: Int get() = long(Knob.UPSTREAM_RETRIES).toInt()
    public val upstreamTimeoutMs: Long get() = long(Knob.UPSTREAM_TIMEOUT_MS)
    public val firstByteTimeoutMs: Long get() = long(Knob.FIRST_BYTE_TIMEOUT_MS)
    public val streamIdleMs: Long get() = long(Knob.STREAM_IDLE_MS)
    public val stallReanchorMs: Long get() = long(Knob.STALL_REANCHOR_MS)
    public val authCacheMs: Long get() = long(Knob.AUTH_CACHE_MS)
    public val debug: Boolean get() = bool(Knob.DEBUG)
    public val contextWindowOverride: Long? get() = m[Knob.CONTEXT_WINDOW_OVERRIDE.key] as? Long
    public val grokPort: Int get() = long(Knob.GROK_PORT).toInt()
    public val grokModel: String get() = string(Knob.GROK_MODEL).orEmpty()
    public val xaiApiBase: String get() = string(Knob.XAI_API_BASE).orEmpty()
    public val grokAuthPath: String get() = string(Knob.GROK_AUTH_PATH).orEmpty()
    public val controlPort: Int get() = long(Knob.CONTROL_PORT).toInt()
    public val usageWarnPct: Int get() = long(Knob.USAGE_WARN_PCT).toInt()
    public val usageWarnTokens5h: Long get() = long(Knob.USAGE_WARN_TOKENS_5H)
    public val toolSurfaceOff: Boolean get() = string(Knob.TOOL_SURFACE) == "off"
    public val quotaPollOff: Boolean get() = string(Knob.QUOTA_POLL) == "off"

    /** V4-173: how many upstream request bodies this head keeps in memory; 0 (the default) keeps none. */
    public val wireTap: Int get() = long(Knob.WIRE_TAP).toInt().coerceAtLeast(0)

    /** V4-174: whether this head writes its full request/response trace; off unless the head opted in. */
    public val trace: Boolean get() = bool(Knob.TRACE)

    /** V4-174: how many UTC days of trace files a traced head keeps; at least one. */
    public val traceRetentionDays: Int get() = long(Knob.TRACE_RETENTION_DAYS).toInt().coerceAtLeast(1)

    /**
     * How far back this install keeps its history: the hourly totals AND the request records, as
     * the one setting a person sets on Settings > Your data.
     *
     * WHERE THE ANSWER COMES FROM, and why in this order:
     *  1. `historyRetentionDays`, when the person has set it. A bad word never reaches here —
     *     ConfigCoercion refuses it by name and the knob stays absent, so a typo reads as "unset"
     *     rather than as a short window that would delete history.
     *  2. otherwise the WIDEST of the windows this one setting replaced, never the narrowest: the
     *     records window the install already had (`perfArchiveRetentionDays`, 90 by default) and
     *     the 35 days the hourly totals always kept (V4-122's fixed economics window, now
     *     [HISTORY_DEFAULT_DAYS]). An upgrade never shortens history on its own (Marlin, Oct 10,
     *     2026), and it has to hold for BOTH files: an install that had set a one-day archive
     *     window would otherwise have 34 days of spending deleted the first time this daemon ran,
     *     because splice learned a new name for the question. Taking the wider one keeps records
     *     the person may not want any more, which they can shorten here in one place and see
     *     counted before it happens; taking the narrower one deletes what nobody can bring back.
     *     The legacy 0, "keep no retired generations", falls out of the same rule as 35.
     *
     * A fresh install never reaches 2: its starter splice.toml carries the default in writing.
     */
    public val historyWindow: HistoryWindow
        get() = string(Knob.HISTORY_RETENTION_DAYS)?.let(HistoryWindowWords::of)
            ?: HistoryWindow(maxOf(long(Knob.PERF_ARCHIVE_RETENTION_DAYS).toInt(), HISTORY_DEFAULT_DAYS))

    /**
     * How many UTC days of saved prompts and answers this head keeps NOW, read through [current] by whoever
     * must follow a change without a restart. They follow the one history window (Marlin, Oct 10, 2026), so
     * Requests cannot read "Older than 7 days" while Settings says forever. An upgrade never shortens what
     * someone has: a trace window longer than the default was chosen on purpose, and the longer of it and the
     * history window is kept. Forever keeps every day; a window of zero keeps the day that is running.
     */
    public val traceKeptDays: Int
        get() {
            val window = historyWindow.days ?: return Int.MAX_VALUE
            val chosen = traceRetentionDays
            val kept = if (chosen > Knob.TRACE_RETENTION_DAYS.count()) maxOf(chosen, window) else window
            return kept.coerceAtLeast(1)
        }

    /** V4-174: the longest body a trace record keeps whole, in characters; at least one. */
    public val traceMaxBodyChars: Int
        get() = long(Knob.TRACE_MAX_BODY_CHARS).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

    /** V4-176: the systemd user unit that supervises this install, and the slice hosted MCP servers
     *  are spawned into. splice owns NEITHER — it reads these names, asks systemd about them, and
     *  says so when the answer is that they are not there. */
    public val supervisorUnit: String get() = string(Knob.SUPERVISOR_UNIT).orEmpty()
    public val mcpSlice: String get() = string(Knob.MCP_SLICE).orEmpty()

    // Colon-separated absolute paths → list; relative segments are dropped (trust boundary).
    public val statuslineGitRoots: List<String>
        get() = string(Knob.STATUSLINE_GIT_ROOTS).orEmpty()
            .split(':').map { it.trim() }.filter { it.startsWith("/") }

    public fun asMap(): Map<String, Any?> = m

    private fun string(k: Knob): String? = m[k.key]?.toString()

    private fun long(k: Knob): Long = (m[k.key] as? Long) ?: m[k.key]?.toString()?.toLongOrNull() ?: 0L

    private fun bool(k: Knob): Boolean = m[k.key] == true
}

/** What a [SpliceConfig] reads itself again through: the one service that minted it. */
internal fun interface FreshConfig {
    fun read(): SpliceConfig
}
