// NEW: what the control plane needs to manage one head — the :core Head lifecycle handle plus
// the file-based truth sources (auth/usage/compact) it reads DIRECTLY, so a DOWN head still
// shows last-known state (the AGENTS.md contract). :control depends on :core only; :app supplies
// the concrete pieces. Config is one shared in-process service (no PATCH fanout — single JVM).
package splice.control

import splice.core.auth.AuthProvider
import splice.core.head.Head
import splice.core.model.ModelCatalog

/** Reads the head's persisted usage/ratelimit (file truth). */
public fun interface HeadUsageSource {
    /** One coherent filesystem read per control/statusline request. */
    public fun snapshot(): UsageView
}

public data class RateLimitView(val limitTokens: Long?, val remainingTokens: Long?, val resetTokens: String?)
public data class UsageView(val outputTokens5h: Long, val entries: Int, val ratelimit: RateLimitView?)

/** Reads the head's compaction stats (file truth). */
public interface HeadCompactSource {
    public fun summary(tailN: Int): CompactView
}

public data class CompactView(val total: Int, val byOutcome: Map<String, Int>, val tail: List<Map<String, String>>)

/** Reads the head's log tail (file truth). */
public interface HeadLogSource {
    public fun tail(lines: Int): String

    /** The log file path — /api/logs reports it (webui LogsPayload.path). */
    public fun path(): String
}

public data class ManagedHead(
    val head: Head,
    val auth: AuthProvider,
    val usage: HeadUsageSource,
    val compact: HeadCompactSource,
    val logs: HeadLogSource,
    val warnPct: Int,
    val warnTokens5h: Long,
    /** The auth dialect for HeadStatus.authKind (webui) — known at wiring time (chatgpt-oauth|api-key). */
    val authKind: String = "unknown",
    /** Present when this head can be launched as a Claude Code wrapper (P4-LAUNCH). */
    val launchSpec: LaunchSpec? = null,
    /** Per-turn perf telemetry rows for /api/perf; null = head has no perf sink wired. */
    val perf: HeadPerfSource? = null,
    /** DR-81: "does this head hold a working api key RIGHT NOW" — read per /launch, never frozen
     *  into [launchSpec] (the spec is assembled once at boot; `splice key set` promises live
     *  pickup, and a boot-frozen gate left the paste-your-key capture hook armed against a
     *  working credential). Default true = capture/advertiser stay disarmed, the safe side. */
    val keyPresence: KeyPresenceProbe = KeyPresenceProbe { true },
    /** This head's model catalog, for the statusline: Claude Code reports every non-"[1m]" row
     *  against the PINNED row's window and splice scales the counts it sends back, so the blob
     *  Claude Code pipes to /statusline carries client units on a scaled row. The catalog is what
     *  turns them back into the row's declared window and its label. Null = render the blob as is. */
    val catalog: ModelCatalog? = null,
)

/** The launch-time key-presence read [ManagedHead.keyPresence] carries (role-named ctor seam). */
public fun interface KeyPresenceProbe {
    public fun keyPresentNow(): Boolean
}
