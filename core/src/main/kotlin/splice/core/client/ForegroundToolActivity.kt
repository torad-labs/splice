// NEW: foreground-tool callbacks carry opaque ownership and phase, never tool content.
package splice.core.client

/** Lifecycle facts, independent of the tool's name, input, output or provider request. */
public enum class ForegroundToolPhase { START, END, SESSION_START, SESSION_END }

/** The per-launch value travels in the transport header, never in the hook's three-field JSON body. */
public const val FOREGROUND_OWNER_HEADER: String = "X-Splice-Foreground-Owner"

// why: random UUID launch owners have one fixed textual width; both transport ends validate the same bound.
public const val FOREGROUND_OWNER_LENGTH: Int = 36

/** Immutable client environment preserves this value even when another launch rewrites shared hook scripts. */
public const val FOREGROUND_OWNER_ENV: String = "SPLICE_FOREGROUND_OWNER"

/** An opaque callback plus its transport owner. Neither identity is inferred from tool contents. */
public data class ForegroundToolCall(
    public val sessionId: String,
    public val toolUseId: String?,
    public val phase: ForegroundToolPhase,
    public val owner: String,
) {
    /** The transport owner is never part of a diagnostic rendering. */
    override fun toString(): String = "ForegroundToolCall(phase=$phase)"
}

/** The authenticated local hook's write boundary, shared by launch transport and session freshness. */
public fun interface ForegroundToolActivity {
    public fun record(call: ForegroundToolCall)
}
