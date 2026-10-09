// NEW: what the control plane needs to manage one head — the :core Head lifecycle handle plus
// the file-based truth sources (auth/usage/compact) it reads DIRECTLY, so a DOWN head still
// shows last-known state (the AGENTS.md contract). :daemon-control depends on :core only; :app supplies
// the concrete pieces. Config is one shared in-process service (no PATCH fanout — single JVM).
package splice.app.control

import splice.core.auth.AuthProvider
import splice.core.head.Head
import splice.launch.LaunchSpec

// The per-capability sources this record composes live beside it, one file each: HeadUsageSource.kt,
// HeadCompactSource.kt, HeadEconomicsSource.kt, HeadLogSource.kt, HeadPerfSource.kt, HeadAccountPool.kt;
// [HeadSources] holds the file-backed ones together.
public data class ManagedHead(
    val head: Head,
    val auth: AuthProvider,
    val sources: HeadSources,
    val usageWarning: UsageWarning,
    /** Present when this head can be launched as a Claude Code wrapper (P4-LAUNCH). */
    val launchSpec: LaunchSpec? = null,
    val authSurface: HeadAuthSurface = HeadAuthSurface(),
    val statusline: StatuslineContext = StatuslineContext(),
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
)
