// NEW: V4-129 — the DTOs, read seams and outcome types WrappedHead.kt's orchestration is built on,
// split out the same way ClaudeMaterializeTypes.kt carries ClaudeConfigMaterializer's DTOs
// (concentration: a god file is relative to its neighbours, and this package's files already keep
// data shapes separate from the class that acts on them). Same-package FQCNs are unchanged.
package splice.client.wrap

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.client.Keys
import java.nio.file.Path
import java.nio.file.Paths

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
    /** Only a wrap made before V4-445 records these: it copied the operator's settings.json and .claude.json
     *  aside before rewriting them, and unwrap puts them back. A wrap now writes neither, so both are blank. */
    val settingsBackupPath: String,
    val claudeJsonBackupPath: String,
    val wrappedAtEpochMillis: Long,
)

/** V4-129 review: one launch THROUGH the wrapped default `claude` command ([WrappedHead.launchThrough]): the
 *  vanilla config dir the launch READS its transcripts from (a bounded `-c`, a named `-r`). Only [WrappedHead]
 *  makes one. V4-445: the launch WRITES nothing there and does not name it as CLAUDE_CONFIG_DIR: Claude Code
 *  reads its global state from ~/.claude.json only while that variable is unset (from
 *  $CLAUDE_CONFIG_DIR/.claude.json when it is set), so naming the dir hid the operator's mcpServers, projects
 *  and account behind a fresh file. The head's settings ride the launch as a `--settings` overlay instead. */
public class WrappedLaunch internal constructor(public val configDir: Path) {
    /** Clear an inherited splice head's root, but retain an operator's custom vanilla root. */
    public fun configRootUnsets(inherited: String?, headDirs: List<Path>): List<String> {
        val path = inherited?.let(Paths::get) ?: return emptyList()
        val isHead = path.isAbsolute && headDirs.any { it.toAbsolutePath().normalize() == path.normalize() }
        return if (isHead) listOf("CLAUDE_CONFIG_DIR") else emptyList()
    }

    /** The `--settings` JSON a wrapped launch hands Claude Code in place of a materialized settings.json: the
     *  head's model roster (as an enforced allowlist, so the picker offers what the head serves) and its status
     *  line. The client merges it over the operator's own settings for this run only, so nothing is written to
     *  ~/.claude/settings.json and a plain `claude` afterwards reads the file it always read. A null roster is a
     *  head whose client picks its own models (V4-449): the overlay then carries the status line alone. */
    public fun settingsOverlay(availableModelIds: List<String>?, statuslineCommand: String): String =
        buildJsonObject {
            if (availableModelIds != null) {
                putJsonArray(Keys.AVAILABLE_MODELS) { availableModelIds.forEach { add(it) } }
                put("enforceAvailableModels", true)
            }
            putJsonObject(Keys.STATUS_LINE) {
                put("type", "command")
                put("command", statuslineCommand)
                put("padding", 0)
            }
        }.toString()
}

public data class ClaudeHeadStatus(
    public val mode: String, // "separate" | "wrapped"
    public val resolvesTo: String?,
    public val shimPath: String,
    public val realBinaryPath: String?,
)

public sealed class WrapResult {
    public data class Ok(val status: ClaudeHeadStatus) : WrapResult()

    public data class Refused(val reason: String) : WrapResult()
}

public sealed class UnwrapResult {
    public data class Ok(val status: ClaudeHeadStatus) : UnwrapResult()
    public data class Refused(val reason: String) : UnwrapResult()
}

/** What [WrappedHead.reconcile] found. */
public sealed class ReconcileResult {
    /** Nothing is wrapped, so there is nothing to keep wrapped. */
    public data object NotWrapped : ReconcileResult()

    /** `claude` is still the shim and the recorded real binary is there. */
    public data object Intact : ReconcileResult()

    /** The updater re-pointed `claude`, or deleted the recorded binary: the state now names [realBinaryPath],
     *  and `claude` is the shim again. */
    public data class Rewrapped(val realBinaryPath: String) : ReconcileResult()

    /** Something is not settled and is left as it is: an update in the middle of its swap, a `claude` that is
     *  not a symlink, a missing shim. [reason] says which; the next event or tick tries again. */
    public data class Waiting(val reason: String) : ReconcileResult()
}
