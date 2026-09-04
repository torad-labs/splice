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

import splice.core.turn.ReasoningDisplay
import splice.core.turn.ReasoningDisplayParser

/** Typed view over the merged+normalized map. */
public class SpliceConfig internal constructor(private val m: Map<String, Any?>) {
    public val port: Int get() = long(Knob.PORT).toInt()
    public val chatgptApiBase: String get() = string(Knob.CHATGPT_API_BASE).orEmpty()
    public val codexAuthPath: String get() = string(Knob.CODEX_AUTH_PATH).orEmpty()
    public val pinnedModel: String get() = string(Knob.PINNED_MODEL).orEmpty()
    public val effort: String? get() = string(Knob.EFFORT)
    public val summary: String? get() = string(Knob.SUMMARY)
    public val showReasoning: ReasoningDisplay get() = ReasoningDisplayParser.from(string(Knob.SHOW_REASONING))
    public val replayReasoning: Boolean get() = bool(Knob.REPLAY_REASONING)
    public val mirrorReasoning: Boolean get() = bool(Knob.MIRROR_REASONING)

    // Reasoning-continuation folding (codex 518n-2). Models is a comma list → set; empty = feature off.
    public val foldReasoningModels: Set<String>
        get() = string(Knob.FOLD_REASONING_MODELS).orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    public val foldMaxContinue: Int get() = long(Knob.FOLD_MAX_CONTINUE).toInt()
    public val foldMarkerText: String get() = string(Knob.FOLD_MARKER_TEXT).orEmpty()
    public val foldMaxTier: Int get() = long(Knob.FOLD_MAX_TIER).toInt()
    public val maxInflight: Int get() = long(Knob.MAX_INFLIGHT).toInt()
    public val maxQueued: Int get() = long(Knob.MAX_QUEUED).toInt()
    public val upstreamRetries: Int get() = long(Knob.UPSTREAM_RETRIES).toInt()
    public val upstreamTimeoutMs: Long get() = long(Knob.UPSTREAM_TIMEOUT_MS)
    public val firstByteTimeoutMs: Long get() = long(Knob.FIRST_BYTE_TIMEOUT_MS)
    public val streamIdleMs: Long get() = long(Knob.STREAM_IDLE_MS)
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

    // Colon-separated absolute paths → list; relative segments are dropped (trust boundary).
    public val statuslineGitRoots: List<String>
        get() = string(Knob.STATUSLINE_GIT_ROOTS).orEmpty()
            .split(':').map { it.trim() }.filter { it.startsWith("/") }

    public fun asMap(): Map<String, Any?> = m

    private fun string(k: Knob): String? = m[k.key]?.toString()

    private fun long(k: Knob): Long = (m[k.key] as? Long) ?: m[k.key]?.toString()?.toLongOrNull() ?: 0L

    private fun bool(k: Knob): Boolean = m[k.key] == true
}
