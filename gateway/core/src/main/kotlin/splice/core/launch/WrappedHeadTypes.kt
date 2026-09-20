// NEW: V4-129 — the DTOs, read seams and outcome types WrappedHead.kt's orchestration is built on,
// split out the same way ClaudeMaterializeTypes.kt carries ClaudeConfigMaterializer's DTOs
// (concentration: a god file is relative to its neighbours, and this package's files already keep
// data shapes separate from the class that acts on them). Same-package FQCNs are unchanged.
package splice.core.launch

/** [splice.control.LaunchService]'s read seam: the real absolute claude binary when the default
 *  `claude` command is wrapped, else null — bare `"claude"` (today's byte-identical behaviour,
 *  resolved through PATH) is correct exactly when this returns null. */
public fun interface WrapStateRead {
    public fun realBinaryPath(): String?
}

/** `now()` as a seam (Kotlin style law: no bare `System.currentTimeMillis()` call sites scattered
 *  through a class that tests want to pin). */
public fun interface NowMillis {
    public fun nowEpochMillis(): Long
}

/** The one fact [splice.control.LaunchService] must read on every launch (see WrappedHead.kt's
 *  header) — written to and read from a file by [WrapStateStore], never held in memory. */
public data class WrapState(
    val realBinaryPath: String,
    val shadowedSymlinkTarget: String,
    val shimPath: String,
    val settingsBackupPath: String,
    val claudeJsonBackupPath: String,
    val wrappedAtEpochMillis: Long,
)

public data class ClaudeHeadStatus(
    public val mode: String, // "separate" | "wrapped"
    public val resolvesTo: String?,
    public val shimPath: String,
    public val realBinaryPath: String?,
)

public sealed class WrapResult {
    public data class Ok(
        val status: ClaudeHeadStatus,
        val settingsBackupPath: String,
        val claudeJsonBackupPath: String,
    ) : WrapResult()

    public data class Refused(val reason: String) : WrapResult()
}

public sealed class UnwrapResult {
    public data class Ok(val status: ClaudeHeadStatus) : UnwrapResult()
    public data class Refused(val reason: String) : UnwrapResult()
}
