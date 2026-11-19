// NEW: LAN share (docs/specs/lan-share.md) and server mode (docs/specs/server-mode.md, F7). The rules that
// `splice remote add` and `splice remote refresh` share, moved out of RemoteAdd when refresh came to link the commands
// a server starts offering as add links them: whether a name is free for a command another machine serves, how an
// address names a server alone, the commands a team server's profile lists as entries at `<server>/<command>`, and
// how a refusal is said.
package splice.app.cli.share

import splice.core.terminal.CYAN
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.topology.CLAUDE_COMMAND
import splice.core.topology.RemoteConfig
import splice.core.topology.Topology
import splice.share.Asked
import splice.share.ServerProfile
import java.net.URI

internal class RemoteNames(
    private val err: TerminalOutput,
    /** The first LAN-share build's relays, which an address under one's name replaces. */
    private val relays: FirstBuildRelays,
    private val runsShim: ShimLinked,
) {
    // A name a command link can take: a bare file name, as a head's key is.
    private val commandName = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

    // splice's own command. `claude` may name another machine's command once `claude` is wrapped: the wrapped `claude`
    // launches there, and its link stays Claude Code's (Topology.remoteLinks).
    private val reserved = setOf("splice")

    /** The [entries] whose names are free in [topology]; each one that is not is said, with why. */
    fun free(topology: Topology, entries: Map<String, RemoteConfig>): Free {
        val refusals = entries.mapNotNull { (command, remote) ->
            taken(topology, command, remote)?.let { command to it }
        }.toMap()
        refusals.values.forEach { err.line("$REMOTE_VERB: $it") }
        return Free(entries - refusals.keys, complete = refusals.isEmpty())
    }

    /** The commands the profile [read] at [server] lists, each at `<server>/<command>`, with whose key asked and the
     *  telemetry relay it names; null, said, when the server refused the key. */
    fun profiled(server: String, read: Asked<ServerProfile>): ServerOffer? = when (read) {
        is Asked.Refused -> refused(read.why)
        is Asked.Had -> ServerOffer(
            read.value.key.person,
            read.value.commands.associate { it.command to at(server, it.command) },
            read.value.telemetry?.otlp,
        )
    }

    /** [command] as [server] serves it, at `<server>/<command>`. */
    fun at(server: String, command: String): RemoteConfig = RemoteConfig("$server/$command")

    /** [why] said as `splice remote`'s refusal; null, for the verb that refused to return. */
    fun <T> refused(why: String): T? {
        err.line("$REMOTE_VERB: $why")
        return null
    }

    /** Why [command] can't be kept for [remote] here, or null when it can. The same address under the same name is
     *  kept again, which links it anew. */
    private fun taken(topology: Topology, command: String, remote: RemoteConfig): String? {
        val elsewhere = "add this one under another name with $CYAN--as <command>$RESET"
        val held = topology.remote[command]
        return when {
            !commandName.matches(command) -> "${quoted(command)} can't be a command's name; $elsewhere"
            command == CLAUDE_COMMAND && !runsShim(command) ->
                "claude here is Claude Code itself, not wrapped, so it would still run on this machine's own login; " +
                    "wrap it with ${CYAN}splice setup$RESET first, or $elsewhere"
            held?.url == remote.url -> null
            held != null -> "${quoted(command)} already runs on ${held.url}; $elsewhere, or take that one out first " +
                "with ${CYAN}splice remote remove $command$RESET"
            command in reserved || relays.runsHere(topology, command) ->
                "${quoted(command)} is already a command here; $elsewhere"
            else -> null
        }
    }

    /** [text] as an address: trimmed, and over http when it names no scheme, as `splice share status` prints one. */
    fun address(text: String): String = text.trim().let { if ("://" in it) it else "http://$it" }

    /** [address] without a trailing slash when it names a server and nothing after it (`http://devbox.lan:3097`);
     *  null when it names more, as a command's address does. */
    fun serverOnly(address: String): String? = try {
        val uri = URI.create(address)
        address.trimEnd('/').takeIf {
            uri.rawPath.orEmpty().trim('/').isEmpty() && uri.rawQuery == null && uri.rawFragment == null
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun quoted(command: String): String = "'$command'"
}

/** What a filter of names left: the [entries] it kept, and whether it kept every one ([complete]). */
internal data class Free(val entries: Map<String, RemoteConfig>, val complete: Boolean)

/** What a team server's profile offers the [person] whose key asked: its commands as [entries], by command, and the
 *  [telemetry] relay it names, or null when it relays none (F8). */
internal data class ServerOffer(
    val person: String,
    val entries: Map<String, RemoteConfig>,
    val telemetry: String? = null,
) {
    val commands: List<String> get() = entries.keys.toList()

    /** The one line that tells the person where Claude Code's telemetry from [server]'s commands goes now: to the
     *  [telemetry] relay, or nowhere once the server names none. `remote add` and `remote refresh` both say it. */
    fun relayLine(server: String): String {
        val now = telemetry?.let { "goes to $it" } ?: "is no longer relayed"
        return "Claude Code's telemetry from $server's commands $now, from their next launch"
    }
}
