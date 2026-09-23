// NEW: what the control plane needs to manage one head — the :core Head lifecycle handle plus
// the file-based truth sources (auth/usage/compact) it reads DIRECTLY, so a DOWN head still
// shows last-known state (the AGENTS.md contract). :daemon-control depends on :core only; :app supplies
// the concrete pieces. Config is one shared in-process service (no PATCH fanout — single JVM).
package splice.control

import splice.core.auth.AuthProvider
import splice.core.head.Head
import splice.core.model.ClientWindows
import splice.core.model.ModelCatalog

// The per-capability sources this record composes live beside it, one file each: HeadUsageSource.kt,
// HeadCompactSource.kt, HeadEconomicsSource.kt, HeadLogSource.kt, HeadPerfSource.kt, HeadAccountPool.kt.
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
    /** v0.4.0 (FEATURES.md §3): the same rows with outcome tags, for the windowed summary. */
    val perfRows: PerfRowsSource? = null,
    /** Hourly quota rollup for /api/economics; null = head has no economics sink wired. */
    val economics: HeadEconomicsSource? = null,
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
    // V4-127: the head's DECLARED model list (`HeadConfig.models`) and its provider key are
    // deliberately NOT fields here. Both were added first, and the constructor-width ratchet refused
    // the change: ManagedHead is already a recorded offender at 17 parameters and this grew it to 19
    // with a fourth subsystem, which the gate reads as WIDENED — "a recorded offender is DEBT, not
    // permission to keep adding parameters". Re-baselining would be the bypass that gate exists to
    // stop, so the two facts arrive through DeclaredModels instead: one injected role, assigned on
    // ControlServer after construction (the shape V4-136 gave `compaction`). A per-route input does
    // not belong on a whole-head record anyway.
    //
    // The route genuinely needs the declared list (a declared slot that resolved to NOTHING must
    // still be its own row, and only the declared list knows it was declared), so this is a move,
    // not a deletion.
    /** Where the statusline route records each session's real window (`session_id` +
     *  `context_window_size` from the blob) so the head scales that session's counts against it.
     *  Null = a head that never learns (tests). */
    val clientWindows: ClientWindows? = null,
    /** Head-local OAuth account selections and quotas, projected without credential material. */
    val accountPool: HeadAccountPoolSource? = null,
    /** Per-account masked descriptions; never passed to the unauthenticated statusline. */
    val accountAuth: HeadAccountAuthSource? = null,
)

/** The launch-time key-presence read [ManagedHead.keyPresence] carries (role-named ctor seam). */
public fun interface KeyPresenceProbe {
    public fun keyPresentNow(): Boolean
}
