// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 3). Starts Google's `agy acp`, unmodified, for
// a Desktop session: in the project's folder, with Desktop's extra folders (`--add-dir`), and with
// `--dangerously-skip-permissions` when Desktop starts in `bypassPermissions`. agy's standard error goes to a scratch
// folder of its own (made and taken back by [AgyScratch]), to say why agy ended; ending the session ends agy and
// removes the folder.
package splice.provider.antigravity

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.io.IOException
import java.nio.file.Path

/** agy as it was started: its connection, or why there is none, and the scratch folder it was given. */
internal class StartedAgy(val connection: AcpConnection?, private val scratch: AgyScratch?, val reason: String) {
    /** Ends agy and removes its scratch folder; a folder that stays is said to [log]. */
    fun close(log: LogSink) {
        connection?.close()
        scratch?.remove(log)
    }
}

/** Starts the agy [command] in [cwd], its output read on [io]. */
internal class AgyLaunch(
    private val command: List<String>,
    private val cwd: Path,
    private val io: CoroutineDispatcher,
    private val extraEnv: Map<String, String> = emptyMap(),
) {
    /** agy started for [claude] in [scope]. */
    fun start(claude: ClaudeArgs, scope: CoroutineScope): StartedAgy {
        val flags = claude.addDirs.flatMap { listOf("--add-dir", it) } +
            listOfNotNull(SKIP_PERMISSIONS.takeIf { claude.permissionMode == BYPASS_MODE })
        val scratch = AgyFolders().make()
        if (scratch.path == null) return StartedAgy(null, null, scratch.reason)
        return try {
            StartedAgy(
                AcpConnection(command + flags, cwd, scratch.path.resolve("agy.err").toFile(), scope, io, extraEnv),
                scratch,
                "",
            )
        } catch (notStarted: IOException) {
            val why = SafeFailureText.render(notStarted)
            StartedAgy(null, scratch, "agy could not be started ($why); install it from Google and sign in with `agy`")
        }
    }
}

/** Desktop's mode that asks about no tool, which agy runs with its own skip flag. */
internal const val BYPASS_MODE: String = "bypassPermissions"

private const val SKIP_PERMISSIONS = "--dangerously-skip-permissions"
