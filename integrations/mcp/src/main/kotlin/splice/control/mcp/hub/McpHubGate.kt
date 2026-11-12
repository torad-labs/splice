// NEW: MCP hub item 6 — whether this machine runs the hub, decided once at boot (splice-config, Oct 12; mcp-hub.md item
// 6). Three checks, in order: a Linux x86-64 machine, since the front and holder are linuxX64 binaries; both binaries
// present and executable beside splice.jar; and a reachable systemd --user, since every holder starts in its own scope.
// The first that fails is the reason the daemon logs and the front is answered with. Boot only: the binaries arrive with
// an install, and an install restarts the daemon.
package splice.control.mcp.hub

import splice.core.util.SafeFailureText
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

// why: how long `systemctl --user is-system-running` may take before systemd --user counts as unreachable.
private const val SYSTEMD_ANSWER_S = 2L

// why: the binaries the hub needs beside splice.jar.
private val HUB_BINARIES = listOf("splice-mcp-front", "splice-mcp-holder")

// why: the states in which systemd --user starts a scope; a degraded manager still runs units.
private val SYSTEMD_UP = setOf("running", "degraded")

/** [binDir] is the folder splice.jar runs from. */
public class McpHubGate(private val binDir: Path) {
    /** Why the hub cannot run on this machine, or null when it can. */
    public fun refusal(): String? = platform() ?: binaries() ?: systemd()

    private fun platform(): String? {
        val os = System.getProperty("os.name").orEmpty()
        val arch = System.getProperty("os.arch").orEmpty()
        val supported = os == "Linux" && arch in setOf("amd64", "x86_64")
        return if (supported) null else "it runs on Linux x86-64 only, and this is $os $arch"
    }

    private fun binaries(): String? = HUB_BINARIES.map { binDir.resolve(it) }.firstOrNull { !Files.isExecutable(it) }
        ?.let { "$it is missing or not executable" }

    private fun systemd(): String? = try {
        val process = ProcessBuilder("systemctl", "--user", "is-system-running")
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .start()
        if (process.waitFor(SYSTEMD_ANSWER_S, TimeUnit.SECONDS)) {
            val state = process.inputStream.bufferedReader().readText().trim()
            if (state in SYSTEMD_UP) null else "systemd --user is '$state', not running"
        } else {
            process.destroyForcibly()
            "systemd --user did not answer within $SYSTEMD_ANSWER_S s"
        }
    } catch (e: IOException) {
        "systemctl could not run (${SafeFailureText.render(e)})"
    }
}
