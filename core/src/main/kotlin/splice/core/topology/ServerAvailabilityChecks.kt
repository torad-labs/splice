// NEW: server mode (docs/specs/server-mode.md, external upstream state and the readiness probe). What the decode
// accepts in `[server.state]` and `[server.probe]` but the daemon could not act on: a state table naming no source
// or more than one, a source that is no path, no http(s) URL and no command, a cadence or a wait that is no time at
// all, a probe kind there is none of, a completion probe naming no model. A present `[server.state]` must name its
// one source, tuning keys alone included: the table is the operator turning the signal on, and a signal that reads
// nothing would refuse turns on silence or never refuse at all. Each is a boot finding beside every other, naming
// its key and never a value an operator wrote.
package splice.core.topology

internal class ServerAvailabilityChecks(private val server: ServerConfig) {

    fun findings(): List<TopologyFinding> = state() + probe()

    private fun state(): List<TopologyFinding> {
        val state = server.state ?: return emptyList()
        return source(state) + listOfNotNull(
            TopologyFinding("$STATE.every_seconds", "must be a number of seconds, 1 or more")
                .takeIf { state.everySeconds < 1 },
            TopologyFinding("$STATE.retry_after_starting", "must be 0 or more")
                .takeIf { state.retryAfterStarting < 0 },
        )
    }

    /** Exactly one source, and the one named a thing that can be read: a path, an http(s) URL, a command to run. */
    private fun source(state: ServerState): List<TopologyFinding> {
        val sources = listOfNotNull(state.file, state.url, state.command).size
        return listOfNotNull(
            TopologyFinding(STATE, "set one state source: file, url or command").takeIf { sources == 0 },
            TopologyFinding(STATE, "set exactly one state source: file, url or command").takeIf { sources > 1 },
            TopologyFinding("$STATE.file", "must be the path of a state file")
                .takeIf { state.file?.isBlank() == true },
            TopologyFinding("$STATE.url", "must be an http(s) URL, as in \"http://box.lan:8080/state\"")
                .takeIf { state.url?.let { url -> WebUrls.read(url.trim()) == null } == true },
            TopologyFinding("$STATE.command", "must be a nonempty command, as in [\"/usr/local/bin/box-state\"]")
                .takeIf { state.command?.let { argv -> argv.isEmpty() || argv.first().isBlank() } == true },
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
        )
    }
}

private const val STATE = "server.state"
private const val PROBE = "server.probe"
private const val COMPLETION = "completion"
private val KINDS = setOf("health", COMPLETION)
