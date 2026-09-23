// NEW: compose `splice perf` with the perf files and the terminal (LAYOUT-01). The verb moved to
// features/usage; each head's rows come from the same PerfRowsFileSource the daemon's /api/perf reads,
// which is why the CLI and the API print one set of numbers.
package splice.app

import splice.app.sources.PerfRowsFileSource
import splice.core.config.StatePaths
import splice.core.terminal.TerminalOutput
import splice.usage.perf.HeadPerfRows
import splice.usage.perf.PerfCommand

internal object PerfWiring {
    fun command(): PerfCommand = PerfCommand(
        output = TerminalOutput(::println),
        errors = TerminalOutput(System.err::println),
        rows = HeadPerfRows { head, env ->
            val paths = StatePaths(envReader = env)
            PerfRowsFileSource(paths.perfStatsFile(head), paths.perfArchiveDir)
        },
    )
}
