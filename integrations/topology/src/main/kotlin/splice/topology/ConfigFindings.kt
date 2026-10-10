// NEW: fail-closed boot (project law: "config findings refuse boot, all listed at once, and the CLI offers an
// interactive fix session; never boot on a finding or fix silently"). Boot used to stop at the FIRST exception
// the loader threw, so a file with three problems took three restarts, each naming only the next one.
//
// Every finding names a key path and a line and NEVER a value: splice.toml legally carries credential-like
// `extra_headers` entries, and a finding reaches the refusal, the daemon log and a fix session alike (the rule
// 7ad358eda set for the structural findings).
package splice.topology

import kotlinx.serialization.SerializationException
import splice.core.model.HeadDiscoveredModels
import splice.core.topology.Topology
import splice.core.topology.TopologyFinding
import splice.core.topology.TopologyFindings
import splice.core.util.SafeFailureText
import splice.core.util.TopologyTypeFailure
import java.nio.file.Path

/** What reading splice.toml for boot produced: the topology, or every reason it cannot serve. */
public sealed class ConfigRead {
    public data class Ready(val topology: Topology) : ConfigRead()

    /** [findings] is never empty. */
    public data class Refused(val findings: List<TopologyFinding>) : ConfigRead()
}

/** Reads splice.toml text the way boot does and collects EVERY finding instead of throwing the first.
 *
 *  Three layers, in the order a reader meets them: the structural guards ktoml lacks (all of them), then the
 *  decode (one finding: ktoml stops at its first type error, and a half-decoded topology has nothing later to
 *  check), then the checks the decode does not make but the daemon would trip on: an undeclared provider, a bad or
 *  shared port, an override the daemon would ignore. The roster check is left out: it needs the models a
 *  runtime discovers, which boot does not have yet, so it stays a per-head DEGRADED, never a refusal.
 *
 *  [stateBase] only satisfies the knob check's constructor; nothing here reads state. */
public object ConfigFindings {
    public fun read(text: String, stateBase: Path): ConfigRead {
        val structural = TomlStructurePreflight.failures(text).map { finding(it) }
        val decoded = try {
            TopologyLoader.decode(text)
        } catch (broken: IllegalArgumentException) {
            return refused(structural + finding(broken))
        } catch (broken: SerializationException) {
            return refused(structural + finding(broken))
        }
        val checks = TopologyFindings.of(decoded, HeadDiscoveredModels { emptyList() }, stateBase, rosters = false)
            .map { it.copy(line = lineOf(text, it.path)) }
        return refused(structural + checks, decoded)
    }

    private fun refused(findings: List<TopologyFinding>, topology: Topology? = null): ConfigRead = when {
        findings.isNotEmpty() -> ConfigRead.Refused(findings)
        topology != null -> ConfigRead.Ready(topology)
        else -> error("a read with neither findings nor a topology")
    }

    /** A failure as a finding. A type failure names its key and line; the others have only their safe sentence, and
     *  a structural one already carries its own line. */
    private fun finding(broken: Throwable): TopologyFinding {
        return if (broken is TopologyTypeFailure) {
            TopologyFinding(broken.key, "expects ${broken.expected.label}: ${broken.fix()}", broken.line)
        } else {
            TopologyFinding(FILE, SafeFailureText.render(broken))
        }
    }

    /** The line a finding's key path sits on: its own `key =` line inside its table, else its table's header, else
     *  nothing. A path like heads.x.port is table heads.x, key port; a dotted key under a table is matched by its
     *  leaf. */
    private fun lineOf(text: String, path: String): Int? {
        val segments = path.split('.')
        val table = segments.dropLast(1).joinToString(".")
        val leaf = segments.last()
        var current = ""
        var header: Int? = null
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.startsWith("[") && !line.startsWith("[[")) {
                current = line.removePrefix("[").substringBefore("]").trim().replace("\"", "")
                if (current == table && header == null) header = index + 1
            } else if (current == table && keyOf(line) == leaf) {
                return index + 1
            }
        }
        return header
    }

    /** The key a `key = value` line assigns, or null when the line assigns nothing. */
    private fun keyOf(line: String): String? = line.takeIf { it.contains('=') }?.substringBefore("=")?.trim()

    private const val FILE = "splice.toml"
}
