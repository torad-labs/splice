// NEW: custom-provider (issue #680, cp-install). `splice add provider <file|url> [--check]` installs a team's
// published provider definition with no hand-edited TOML, as `splice add api-key` installs a key provider. The
// definition is read (DefinitionSource), its heads' missing ports and prefixes are filled in (ProviderDefinition), and
// it is checked as boot checks splice.toml: the same ConfigFindings over it, the cross-file checks over splice.toml
// with it merged in, and the definition's own. Every finding is listed at once and nothing is written; `--check`
// stops there either way. Otherwise the merged topology is written with TopologyWriter (comments and order kept), and a
// running daemon is restarted the way `splice add` restarts it. Installing a provider already installed replaces it:
// its tables, its heads (a head of the same key keeps its port and prefix) and its door commands; another provider's
// are never touched.
package splice.configuration.add

import splice.core.config.StatePaths
import splice.core.terminal.CYAN
import splice.core.terminal.GREEN
import splice.core.terminal.RED
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.topology.DoorConfig
import splice.core.topology.HeadConfig
import splice.core.topology.Topology
import splice.core.topology.TopologyFinding
import splice.core.topology.TopologyFindings
import splice.core.topology.TopologyParse
import splice.core.topology.TopologyWriteResult
import splice.core.topology.TopologyWriter
import splice.core.util.EnvReader
import splice.daemonclient.DaemonSettings
import splice.topology.ConfigFindings
import splice.topology.ConfigRead
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

internal class AddProviderCommand(
    private val output: TerminalOutput,
    errors: TerminalOutput,
    private val ports: AddPorts,
    private val bindable: HeadPortBindable,
) {
    private val settings = DaemonSettings(errors)

    /** [args] are the words after `provider`. True when the definition is good (and, without --check, installed). */
    suspend fun add(args: List<String>, env: EnvReader): Boolean {
        val source = args.filterNot { it == CHECK_FLAG }.singleOrNull() ?: return usage()
        return when (val read = DefinitionSource(source).read()) {
            is DefinitionRead.Refused -> refused(source, listOf(TopologyFinding(SOURCE, read.why)))
            is DefinitionRead.Text -> definition(source, read.text, check = CHECK_FLAG in args, env)
        }
    }

    /** The definition [text] read from [source]: checked against splice.toml, and installed unless [check]. */
    private suspend fun definition(source: String, text: String, check: Boolean, env: EnvReader): Boolean {
        val path = TopologyLoader.configPath(env)
        val existing = if (Files.exists(path)) Files.readString(path) else ""
        val current = TopologyLoader.parse(existing)
        val definition = ProviderDefinition(text)
        val key = definition.providerKey ?: return refused(source, definition.findings())
        val checked = checked(definition, key, current, path)
        val merged = checked.merged
        return when {
            checked.findings.isNotEmpty() || merged == null -> refused(source, checked.findings)
            check -> true.also {
                output.line("$GREEN✓$RESET $source is a good provider definition (${summary(key, checked.decoded)})")
            }
            else -> install(path, current, Installed(merged, key, checked.decoded), env)
        }
    }

    /** What an install writes: splice.toml with the definition merged in, its provider, and the definition itself. */
    private data class Installed(val merged: Topology, val key: String, val decoded: Topology)

    /** What checking found: the definition decoded, splice.toml with it merged in, and every finding. */
    private data class Checked(val decoded: Topology, val merged: Topology?, val findings: List<TopologyFinding>)

    private fun checked(definition: ProviderDefinition, key: String, current: Topology, path: Path): Checked {
        val installed = current.heads.filterValues { it.provider == key }
        val ports = portsFor(definition, key, current, installed)
        val filled = definition.filled(ports, installed.mapValues { it.value.discoveryPrefix })
        val dir = path.toAbsolutePath().parent
        val stateBase = StatePaths().configBackupsDir
        val read = ConfigFindings.read(filled, stateBase, dir)
        val decoded = (read as? ConfigRead.Ready)?.topology
        val merged = decoded?.let { merged(current, it, key) }
        val standing = TopologyFindings.of(current, stateBase = stateBase, rosters = false, configDir = dir).toSet()
        val crossFile = merged?.let { TopologyFindings.of(it, stateBase = stateBase, rosters = false, configDir = dir) }
            .orEmpty()
            .filterNot { it in standing }
        val decodedOwn = decoded?.let(definition::decodedFindings).orEmpty()
        // lines name the filled text, not the definition as written, so they are left out
        val boot = (read as? ConfigRead.Refused)?.findings.orEmpty()
        val found = (boot + decodedOwn + crossFile).map { it.copy(line = null) }
        val findings = definition.findings() + found
        // the definition read alone and the merged file can report one finding twice; the same words are listed once
        return Checked(decoded ?: Topology(), merged, findings.distinct())
    }

    /** A port for each head that names none: the one a head of the same key already installed holds, else the next
     *  free one, as `splice add` chooses it. */
    private fun portsFor(
        definition: ProviderDefinition,
        key: String,
        current: Topology,
        installed: Map<String, HeadConfig>,
    ): Map<String, Int> {
        val choice = AddPortChoice(bindable, LAST_HEAD_PORT)
        var taken = current
        return definition.portless().mapNotNull { head ->
            val port = installed[head]?.port
                ?: choice.choose(taken, null, choice.floor(taken))
                ?: return@mapNotNull null
            val holder = HeadConfig(provider = key, port = port, discoveryPrefix = "")
            taken = taken.copy(heads = taken.heads + ("$key.$head" to holder))
            head to port
        }.toMap()
    }

    /** [current] with provider [key] replaced by [definition]'s: its table, its heads (those it no longer names
     *  removed) and the door commands on them. */
    private fun merged(current: Topology, definition: Topology, key: String): Topology {
        val provider = definition.providers[key] ?: return current
        val gone = current.heads.filterValues { it.provider == key }.keys + definition.heads.keys
        val commands = current.door.commands.filterValues { it.head !in gone } + definition.door.commands
        return current.copy(
            providers = current.providers + (key to provider),
            heads = current.heads.filterKeys { it !in gone } + definition.heads,
            door = DoorConfig(commands),
        )
    }

    private suspend fun install(path: Path, current: Topology, change: Installed, env: EnvReader): Boolean {
        val writer = TopologyWriter(path, StatePaths().configBackupsDir, TopologyParse(TopologyLoader::parse))
        val (merged, key, decoded) = change
        return when (val written = writer.write(merged, writer.tree(current))) {
            is TopologyWriteResult.Refused -> refused(path.toString(), written.findings)
            is TopologyWriteResult.Written -> {
                output.line("$GREEN✓$RESET installed ${summary(key, decoded)} in $path")
                decoded.door.commands.forEach { (name, command) ->
                    output.line("  door command $CYAN$name$RESET on ${command.head}")
                }
                restarted(merged, env) && next(key, decoded)
            }
        }
    }

    /** A running daemon reads splice.toml at start, so it restarts as `splice add` restarts it. */
    private suspend fun restarted(merged: Topology, env: EnvReader): Boolean {
        if (!ports.daemonUp(settings.controlPort(merged, env))) {
            output.line("  daemon     not running; the provider comes up when splice starts")
            return true
        }
        val ok = ports.restart()
        val said = if (ok) DAEMON_RESTARTED else "  $RED✗$RESET daemon restart failed: run ${CYAN}splice restart$RESET"
        output.line(said)
        return ok
    }

    private fun next(key: String, decoded: Topology): Boolean {
        val provider = decoded.providers[key]
        val head = decoded.heads.keys.firstOrNull()
        if (provider?.auth?.isCommand == true && head != null) {
            output.line("  next       sign in: ${CYAN}splice login $head$RESET, or /login in a claude session")
        } else {
            output.line("  next       ${CYAN}claude$RESET, then /model")
        }
        return true
    }

    private fun summary(key: String, decoded: Topology): String {
        val heads = decoded.heads.entries.joinToString(", ") { (head, config) ->
            "$head as ${config.claude.command ?: head}"
        }
        val models = decoded.providers[key]?.models.orEmpty().joinToString(", ") { it.id }
        return "provider $key; heads: ${heads.ifEmpty { "none" }}; models: ${models.ifEmpty { "none" }}"
    }

    private fun refused(source: String, findings: List<TopologyFinding>): Boolean {
        output.line("splice add provider: $source has ${findings.size} problem(s); nothing was written:")
        findings.forEach { output.line("  - ${it.text()}") }
        return false
    }

    private fun usage(): Boolean {
        output.line("usage: splice add provider <file|https-url> [--check]")
        return false
    }
}

private const val CHECK_FLAG = "--check"
private const val SOURCE = "definition"
private const val DAEMON_RESTARTED = "  daemon     restarted; the next session's /model lists the provider"
