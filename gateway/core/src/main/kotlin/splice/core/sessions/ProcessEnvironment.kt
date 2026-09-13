// NEW (v0.4.0, FEATURES.md §4): the one fact that ties a registered Claude Code session to the
// splice head it talks to — the ANTHROPIC_BASE_URL splice's launcher put in its environment.
// Read from /proc/<pid>/environ, which only this user's own processes expose; anything else
// reads as no environment, and the session is reported under "unknown head".
package splice.core.sessions

import splice.core.util.Cancellables
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

public class ProcessEnvironment(private val procRoot: Path = Paths.get("/proc")) {
    private val localHead = Regex("^https?://127\\.0\\.0\\.1:(\\d+)")

    public fun read(pid: Long): Map<String, String> = Cancellables
        .runCatchingCancellable { Files.readString(procRoot.resolve(pid.toString()).resolve("environ")) }
        .getOrNull()
        ?.split('\u0000')
        ?.filter { '=' in it }
        ?.associate { it.substringBefore('=') to it.substringAfter('=') }
        ?: emptyMap()

    /** The head port when the process was launched against a local splice head, else null. */
    public fun spliceHeadPort(pid: Long): Int? =
        read(pid)["ANTHROPIC_BASE_URL"]?.let { localHead.find(it)?.groupValues?.get(1)?.toIntOrNull() }
}
