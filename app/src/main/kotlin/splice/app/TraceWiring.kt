// NEW: compose `splice trace` with the application's topology read and the two terminal streams
// (LAYOUT-01): the verb lives beside the TraceStore in features/turns and reaches neither the topology
// loader nor System.out itself.
package splice.app

import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.head.trace.TraceCommand
import splice.head.trace.TraceDirSource
import splice.head.trace.TraceHeadSource
import splice.head.trace.TraceHeads
import splice.topology.TopologyLoader
import splice.topology.TopologyStatePaths
import java.io.IOException
import java.nio.file.Files

internal object TraceWiring {
    fun run(args: List<String>): Boolean = TraceCommand(
        output = TerminalOutput(::println),
        errors = TerminalOutput(System.err::println),
        heads = TopologyTraceHeads(),
        traceDirs = TraceDirSource { env -> TopologyStatePaths(env).current().traceDir },
    ).trace(args, EnvReader(System::getenv))
}

/** The heads `splice trace` checks a head against: the configured topology's, or why it was unreadable. */
internal class TopologyTraceHeads : TraceHeadSource {
    override fun load(env: EnvReader): TraceHeads {
        val path = TopologyLoader.configPath(env)
        return try {
            TraceHeads.Configured(path.toString(), TopologyLoader.parse(Files.readString(path)).heads.keys)
        } catch (unreadable: IOException) {
            TraceHeads.Unreadable(path.toString(), unreadable)
        }
    }
}
