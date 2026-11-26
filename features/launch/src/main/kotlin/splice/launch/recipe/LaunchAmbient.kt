// NEW: Nov 23, 2026 (#791 §3) — what a launch merges with the environment its caller runs in rather than replacing:
// NO_PROXY and ANTHROPIC_CUSTOM_HEADERS. Moved out of LaunchService so a head's launch and a launch on the door merge
// them the same way, from one place. The daemon's environment is not the caller's (a service's daemon has neither
// variable of the person's shell): the shim sends the caller's NO_PROXY (shim-30), and merges the caller's own headers
// itself, so a header that holds a secret of theirs never reaches the daemon.
package splice.launch.recipe

import splice.client.desktop.GATEWAY_HEADERS
import splice.core.client.CLIENT_WALL_HEADER
import splice.core.client.SPLICE_CLIENT_HEADER
import splice.core.config.LAUNCH_PROOF_HEADER
import splice.core.model.CLIENT_WINDOW_HEADER
import splice.core.util.EnvReader
import java.net.URI

internal class LaunchAmbient(private val envReader: EnvReader) {
    /** NO_PROXY and no_proxy, both: the caller's lists ([caller], LaunchCaller.noProxy) and the daemon's, with loopback
     *  added, and the host of [address] when it has one (another machine's door or head, LAN share), which a proxy the
     *  shell names can't reach. Claude Code and the statusline's curl both honour it, and curl reads the lowercase
     *  spelling first and ignores NO_PROXY once that is set. Every entry either list already holds is kept. */
    fun noProxy(address: String?, caller: List<String>): Map<String, String> {
        val daemon = sequenceOf(
            envReader("NO_PROXY"),
            envReader("no_proxy"),
            LOOPBACK_HOSTS,
            address?.let { URI.create(it).host?.removeSurrounding("[", "]") },
        ).filterNotNull()
        val merged = (caller.asSequence() + daemon)
            .flatMap { it.split(',').asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(",")
        return mapOf("NO_PROXY" to merged, "no_proxy" to merged)
    }

    /** ANTHROPIC_CUSTOM_HEADERS, which Claude Code reads one per line, as splice sets it for this launch: [given], the
     *  headers a caller's own `--settings` named, then the session's client wall ([wallMs], when there is one) and
     *  [own], splice's other headers. Every header of [given] that splice plants itself ([planted]) is dropped, since
     *  an outer session's proof would reach a head it was not planted for and its wall and window are not this
     *  launch's; a wrapper's own (`x-splice-wrapper`) stays. The shim adds the caller shell's headers, without the
     *  planted ones, before these. Said even when empty, so the shim always drops an outer session's. */
    fun customHeaders(wallMs: Long?, own: List<String>, given: String? = null): Map<String, String> {
        val theirs = given.orEmpty().lines()
            .filter { it.isNotBlank() && it.substringBefore(':').trim().lowercase() !in planted }
        val wall = listOfNotNull(wallMs?.let { "$CLIENT_WALL_HEADER: $it" })
        return mapOf(GATEWAY_HEADERS to (theirs + wall + own).joinToString("\n"))
    }
}

/** What every launch adds to NO_PROXY: the loopback address its own gateway or head listens on. */
private const val LOOPBACK_HOSTS = "127.0.0.1,localhost"

/** The headers a launch plants for its session: its client wall, its window, its launch proof and, from a team
 *  server's command, the word that this is splice. The shim drops the same four from the caller's (caller_headers). */
private val planted: Set<String> =
    setOf(CLIENT_WALL_HEADER, CLIENT_WINDOW_HEADER, LAUNCH_PROOF_HEADER, LAUNCH_OWNER_HEADER, SPLICE_CLIENT_HEADER)
