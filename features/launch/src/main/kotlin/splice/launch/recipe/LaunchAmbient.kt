// NEW: Nov 23, 2026 (#791 §3) — what a launch merges with the environment it inherits rather than replacing: NO_PROXY
// and ANTHROPIC_CUSTOM_HEADERS. Moved out of LaunchService so a head's launch and a launch on the door merge them the
// same way, from one place.
package splice.launch.recipe

import splice.core.client.CLIENT_WALL_HEADER
import splice.core.util.EnvReader
import java.net.URI

internal class LaunchAmbient(private val envReader: EnvReader) {
    /** NO_PROXY and no_proxy, both: the ambient lists with loopback added, and the host of [address] when it has one
     *  (another machine's door or head, LAN share), which a proxy the shell names can't reach. Claude Code and the
     *  statusline's curl both honour it, and curl reads the lowercase spelling first and ignores NO_PROXY once that is
     *  set. Every entry the shell already lists is kept. */
    fun noProxy(address: String?): Map<String, String> {
        val merged = sequenceOf(
            envReader("NO_PROXY"),
            envReader("no_proxy"),
            LOOPBACK_HOSTS,
            address?.let { URI.create(it).host?.removeSurrounding("[", "]") },
        )
            .filterNotNull()
            .flatMap { it.split(',').asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(",")
        return mapOf("NO_PROXY" to merged, "no_proxy" to merged)
    }

    /** ANTHROPIC_CUSTOM_HEADERS, which Claude Code reads one per line: the ambient headers without splice's own, then
     *  the session's client wall ([wallMs]) and [own], splice's other headers for this launch. An outer session's
     *  splice headers never ride along: its proof would reach a head it was not planted for, or another machine, and
     *  its window is not this launch's. The ambient headers can hold a person's secrets, so this value stays in the
     *  process environment and never goes in a `--settings` overlay. */
    fun customHeaders(wallMs: Long, own: List<String>): Map<String, String> {
        // Every header splice plants for a session starts "x-splice-" (the client wall, the window, the launch proof).
        val ambient = envReader("ANTHROPIC_CUSTOM_HEADERS").orEmpty().lines()
            .filter { it.isNotBlank() && !it.trim().startsWith("x-splice-", ignoreCase = true) }
        val wall = "$CLIENT_WALL_HEADER: $wallMs"
        return mapOf("ANTHROPIC_CUSTOM_HEADERS" to (ambient + wall + own).joinToString("\n"))
    }
}

/** What every launch adds to NO_PROXY: the loopback address its own gateway or head listens on. */
private const val LOOPBACK_HOSTS = "127.0.0.1,localhost"
