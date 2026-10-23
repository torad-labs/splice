// NEW: LAN share, phase 2 (docs/specs/lan-share.md). splice.toml as `splice share` and `splice remote` read and change
// it: parsed fresh, and written through the one TopologyWriter (backup first; an edit that brings a finding is
// refused and said, leaving the file byte-identical). Both verbs had their own copy of this until phase 2.
package splice.app.cli.share

import splice.core.config.StatePaths
import splice.core.terminal.TerminalOutput
import splice.core.topology.Topology
import splice.core.topology.TopologyParse
import splice.core.topology.TopologyWriteResult
import splice.core.topology.TopologyWriter
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.topology.TopologyLoader
import java.nio.file.Files

/** [verb] prefixes every line said on [err] (`splice share`, `splice remote`). */
internal class ShareTopologyFile(
    private val err: TerminalOutput,
    private val verb: String,
    private val env: EnvReader,
) {
    val path = TopologyLoader.configPath(env)

    /** The file as it is now, or null, said, when it does not load. */
    fun loaded(): Topology? = Cancellables.runCatchingCancellable { TopologyLoader.parse(Files.readString(path)) }.fold(
        onSuccess = { it },
        onFailure = { failure ->
            err.line("$verb: $path does not load: ${SafeFailureText.render(failure)}")
            null
        },
    )

    /** True when [wanted] is what the file now says; a refusal is said finding by finding. */
    fun saved(wanted: Topology): Boolean {
        val backups = StatePaths(envReader = env).configBackupsDir
        val writer = TopologyWriter(path, backups, TopologyParse(TopologyLoader::parse))
        val result = Cancellables.runCatchingCancellable { writer.write(wanted) }.getOrElse { failure ->
            err.line("$verb: $path was not changed: ${SafeFailureText.render(failure)}")
            return false
        }
        if (result is TopologyWriteResult.Refused) result.findings.forEach { err.line("$verb: ${it.text()}") }
        return result !is TopologyWriteResult.Refused
    }
}
