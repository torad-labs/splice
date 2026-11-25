// NEW: server mode (docs/specs/server-mode.md, external upstream state and the readiness probe). What the decode
// accepts in `[server.state]` and `[server.probe]` but the daemon could not act on: a state table naming no source
// or more than one, a source that is no path, no http(s) URL and no command, a cadence or a wait that is no time at
// all, a probe kind there is none of, a completion probe naming no model, a `single_url` entry that is no openai-chat
// provider on one `base_url` (#791). A present `[server.state]` must name its
// one source, tuning keys alone included: the table is the operator turning the signal on, and a signal that reads
// nothing would refuse turns on silence or never refuse at all. Each is a boot finding beside every other, naming
// its key and never a value an operator wrote.
package splice.core.topology.availability

import splice.core.topology.Dialect
import splice.core.topology.ServerState
import splice.core.topology.Topology
import splice.core.topology.TopologyFinding
import splice.core.topology.WebUrls

internal class ServerAvailabilityChecks(private val topology: Topology) {
    private val server = topology.server

    fun findings(): List<TopologyFinding> = state() + probe()

    private fun state(): List<TopologyFinding> {
        val state = server.state ?: return emptyList()
        return source(state) + listOfNotNull(
            TopologyFinding("$SERVER_STATE_PATH.every_seconds", "must be a number of seconds, 1 or more")
                .takeIf { state.everySeconds < 1 },
            TopologyFinding("$SERVER_STATE_PATH.retry_after_starting", "must be 0 or more")
                .takeIf { state.retryAfterStarting < 0 },
        )
    }

    /** Exactly one source, and the one named a thing that can be read: a path, an http(s) URL, a command to run. */
    private fun source(state: ServerState): List<TopologyFinding> {
        val sources = listOfNotNull(state.file, state.url, state.command).size
        return listOfNotNull(
            TopologyFinding(SERVER_STATE_PATH, "set one state source: file, url or command").takeIf { sources == 0 },
            TopologyFinding(SERVER_STATE_PATH, "set exactly one state source: file, url or command")
                .takeIf { sources > 1 },
            TopologyFinding("$SERVER_STATE_PATH.file", "must be the path of a state file")
                .takeIf { state.file?.isBlank() == true },
            TopologyFinding("$SERVER_STATE_PATH.url", "must be an http(s) URL, as in \"http://box.lan:8080/state\"")
                .takeIf { state.url?.let { url -> WebUrls.read(url.trim()) == null } == true },
            TopologyFinding(
                "$SERVER_STATE_PATH.command",
                "must be a nonempty command, as in [\"/usr/local/bin/box-state\"]",
            ).takeIf { state.command?.let { argv -> argv.isEmpty() || argv.first().isBlank() } == true },
        )
    }

    /** A probe kind there is, the model a completion asks for, and times a probe can keep. */
    private fun probe(): List<TopologyFinding> {
        val probe = server.probe
        val seconds = "must be a number of seconds, 1 or more"
        return listOfNotNull(
            TopologyFinding("$PROBE.kind", "must be \"health\" or \"completion\"").takeUnless { probe.kind in KINDS },
            TopologyFinding("$PROBE.model", "must name a model when kind is \"completion\"")
                .takeIf { probe.kind == COMPLETION && probe.model.isNullOrBlank() },
            TopologyFinding("$PROBE.timeout_seconds", seconds).takeIf { probe.timeoutSeconds < 1 },
            TopologyFinding("$PROBE.every_seconds", seconds).takeIf { probe.everySeconds < 1 },
        ) + single(probe.singleUrl)
    }

    /** What `single_url` names: providers of splice.toml, each once, that speak openai-chat (the one dialect a probe
     *  asks) from one `base_url`. A provider that lists upstreams is a pool, which the probe asks already. Each finding
     *  names the entry by its place in the list, never by what the operator wrote. */
    private fun single(names: List<String>): List<TopologyFinding> {
        val path = "$PROBE.single_url"
        val twice = TopologyFinding(path, "names a provider twice").takeIf { names.distinct().size != names.size }
        val each = names.withIndex().mapNotNull { (at, name) ->
            val provider = topology.providers[name]
            val why = when {
                provider == null -> "names a provider splice.toml does not declare"
                provider.upstreams.isNotEmpty() -> "names a provider that lists upstreams, which the probe asks already"
                provider.dialect != Dialect.OPENAI_CHAT -> "names a provider whose dialect is not \"openai-chat\""
                provider.baseUrl.isBlank() -> "names a provider with no base_url"
                else -> null
            }
            why?.let { TopologyFinding("$path[$at]", it) }
        }
        return listOfNotNull(twice) + each
    }
}

private const val SERVER_STATE_PATH = "server.state"
private const val PROBE = "server.probe"
private const val COMPLETION = "completion"
private val KINDS = setOf("health", COMPLETION)
