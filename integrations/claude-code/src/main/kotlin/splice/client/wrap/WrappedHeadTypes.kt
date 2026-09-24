// NEW: V4-129 — the DTOs, read seams and outcome types WrappedHead.kt's orchestration is built on,
// split out the same way ClaudeMaterializeTypes.kt carries ClaudeConfigMaterializer's DTOs
// (concentration: a god file is relative to its neighbours, and this package's files already keep
// data shapes separate from the class that acts on them). Same-package FQCNs are unchanged.
package splice.client.wrap

import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudePolicy
import splice.client.Keys
import splice.client.MaterializeResult
import splice.client.MaterializeSpec
import java.nio.file.Path

/** [splice.launch.recipe.LaunchService]'s read seam: the real absolute claude binary when the default
 *  `claude` command is wrapped, else null — bare `"claude"` (today's byte-identical behaviour,
 *  resolved through PATH) is correct exactly when this returns null. */
public fun interface WrapStateRead {
    public fun realBinaryPath(): String?
}

/** The one fact [splice.launch.recipe.LaunchService] must read on every launch (see WrappedHead.kt's
 *  header) — written to and read from a file by [WrapStateStore], never held in memory. */
public data class WrapState(
    val realBinaryPath: String,
    val shadowedSymlinkTarget: String,
    val shimPath: String,
    val settingsBackupPath: String,
    val claudeJsonBackupPath: String,
    val wrappedAtEpochMillis: Long,
)

/** V4-129 review: one launch THROUGH the wrapped default `claude` command ([WrappedHead.launchThrough]):
 *  the vanilla config dir it runs over, and the narrow materialization that dir takes. Only
 *  [WrappedHead] makes one, so a launch cannot name a config dir its materialization would not write. */
public class WrappedLaunch internal constructor(
    public val configDir: Path,
    private val materializer: ClaudeConfigMaterializer,
) {
    /** THE one spelling of how the vanilla dir is materialized — wrap itself goes through here too, so
     *  a launch re-renders exactly what wrap wrote. [spec]'s configDir and policy are OVERRIDDEN: the
     *  vanilla dir is the only target, and the policy carries settings.json's "global" layer (the very
     *  file being rewritten) forward whatever the source head's share/isolate says. The door is the
     *  materializer's narrow one ([ClaudeConfigMaterializer.materializeWrap]), never a bypass of the
     *  DR-102 guard. */
    public fun materialize(spec: MaterializeSpec): MaterializeResult = materializer.materializeWrap(
        spec.copy(configDir = configDir, policy = ClaudePolicy(share = setOf(Keys.SETTINGS), isolate = emptySet())),
    )
}

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
