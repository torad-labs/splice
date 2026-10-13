// NEW: MCP hub step 6 (docs/specs/mcp-hub.md, proof f): when splice checks `--plugin-dir-no-mcp`. Each installed
// Claude Code version (a binary named for its version under ~/.local/share/claude/versions) from the proven one up
// that has no verdict is checked once, one version at a time, in the background: every installed one when the daemon
// starts, and any new one when a launch asks (request), which a launch does when its version has no verdict and goes
// on without waiting. That launch runs without the flag (PluginFlagVerdicts.holds), and the next one has the
// verdict. The check is silent: only a version that fails is logged, once, and doctor names it.
package splice.client.mcp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.core.client.ClientVersionParser
import splice.core.util.LogSink
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

// why: proof (f) ran on this version; older ones predate the hub and keep their plugins' own copies.
private const val FIRST_CHECKED = "2.1.296"

/** Checks every installed version [verdicts] has no entry for, with [check], on [dispatcher] inside [scope]. */
public class PluginFlagChecks(
    private val verdicts: PluginFlagVerdicts,
    private val log: LogSink,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val check: PluginFlagCheck = PluginFlagCheck(),
) {
    private val parser = ClientVersionParser()
    private val floor = checkNotNull(parser.parse(FIRST_CHECKED))
    private val sweeping = Mutex()

    /** Starts a pass over every unchecked installed version and returns at once. Passes run one after another, and
     *  each re-reads the verdicts, so a second request for a version already checked costs one directory listing. */
    public fun request() {
        scope.launch(dispatcher) { sweeping.withLock { sweep() } }
    }

    private suspend fun sweep() {
        unchecked().forEach { version ->
            val verdict = runInterruptible(dispatcher) { check.run(verdicts.versionsDir.resolve(version)) }
            try {
                verdicts.record(version, verdict)
            } catch (error: IOException) {
                // Unrecorded, the version is checked again on the next pass; its launches go without the flag.
                log("[mcp-hub] Claude Code $version's plugin flag verdict was not saved (${error::class.simpleName})\n")
            }
            if (!verdict.holds) {
                log(
                    "[mcp-hub] Claude Code $version does not hold --plugin-dir-no-mcp (${verdict.why}); " +
                        "its launches keep each plugin's own MCP servers\n",
                )
            }
        }
    }

    /** Installed versions from [FIRST_CHECKED] up with no verdict, newest first. */
    private fun unchecked(): List<String> {
        val recorded = verdicts.all().keys
        return installed()
            .mapNotNull { name -> parser.parse(name)?.takeIf { it >= floor && name !in recorded }?.let { it to name } }
            .sortedByDescending { it.first }
            .map { it.second }
    }

    private fun installed(): List<String> = try {
        Files.list(verdicts.versionsDir).use { files ->
            files.filter { Files.isRegularFile(it) && Files.isExecutable(it) }.map { it.fileName.toString() }.toList()
        }
    } catch (_: IOException) {
        emptyList()
    }
}

/** Where Claude Code keeps one binary per installed version, under the user's [home]. */
public class ClaudeCodeVersionsDir(private val home: Path) {
    public fun path(): Path = home.resolve(".local").resolve("share").resolve("claude").resolve("versions")
}
