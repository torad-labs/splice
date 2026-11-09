// NEW: compose `splice perf` with the perf files and the terminal (LAYOUT-01). The verb moved to
// features/usage; each head's rows come from the same PerfRowsFileSource the daemon's /api/perf reads,
// which is why the CLI and the API print one set of numbers.
package splice.app

import splice.app.sources.PerfRowsFileSource
import splice.core.terminal.TerminalOutput
import splice.topology.TopologyStatePaths
import splice.usage.perf.HeadPerfRows
import splice.usage.perf.PerfCommand

internal object PerfWiring {
    fun run(args: List<String>, env: splice.core.util.EnvReader): Boolean {
        var source: splice.app.observability.PerfOfflineSources? = null
        return try {
            command().perf(
                args,
                env,
                splice.usage.perf.query.HeadPerfQuery { reader ->
                    val keys = splice.topology.TopologyLoader.parse(
                        java.nio.file.Files.readString(splice.topology.TopologyLoader.configPath(reader)),
                    ).heads.keys
                    val offline = splice.app.observability.PerfOfflineSources(
                        TopologyStatePaths(reader).current(),
                        keys,
                    )
                    source = offline
                    splice.usage.perf.query.PerfQueryService(offline)
                },
                splice.usage.perf.query.PerfProfilesCommand(
                    splice.app.observability.PerfRemoteProfiles(env, TerminalOutput(System.err::println)),
                ),
            )
        } finally {
            source?.close()
        }
    }

    fun command(): PerfCommand = PerfCommand(
        output = TerminalOutput(::println),
        errors = TerminalOutput(System.err::println),
        rows = HeadPerfRows { head, env ->
            val paths = TopologyStatePaths(env).current()
            PerfRowsFileSource(paths.perfStatsFile(head), paths.perfArchiveDir)
        },
    )
}
