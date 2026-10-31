// NEW: fail-closed boot (project law: "config findings refuse boot, all listed at once, and the CLI offers an
// interactive fix session; never boot on a finding or fix silently"). Boot used to stop at the FIRST exception
// the loader threw, so a file with three problems took three restarts, each naming only the next one.
//
// Every finding names a key path and a line and NEVER a value: splice.toml legally carries credential-like
// `extra_headers` entries, and a finding reaches the refusal, the daemon log and a fix session alike (the rule
// 7ad358eda set for the structural findings).
package splice.topology

import kotlinx.serialization.SerializationException
import splice.core.config.retiredKnobsByKey
import splice.core.model.HeadDiscoveredModels
import splice.core.topology.Topology
import splice.core.topology.TopologyFinding
import splice.core.topology.TopologyFindings
import splice.core.topology.TopologyRuleViolation
import splice.core.util.SafeFailureText
import splice.core.util.TopologyTypeFailure
import java.nio.file.Path

/** What reading splice.toml for boot produced: the topology, or every reason it cannot serve. */
public sealed class ConfigRead {
    public data class Ready(val topology: Topology) : ConfigRead()

    /** [findings] is never empty. */
    public data class Refused(val findings: List<TopologyFinding>) : ConfigRead()
}

/** What a local runtime that is up says about the rows a topology declares (v0.4.0 section 10, fail-closed boot): a row
 *  it serves less of, a model it does not list, a model it says cannot call tools. Each refusal is a finding with a key
 *  path ([ModelRowLine] reads its line). The network lives in :app; a reader with no runtime to ask passes nothing. */
public fun interface LocalRowFindings {
    public fun of(topology: Topology): List<TopologyFinding>
}

/** Reads splice.toml text the way boot does and collects EVERY finding instead of throwing the first.
 *
 *  Three layers, in the order a reader meets them: the structural guards ktoml lacks (all of them), then the
 *  decode (one finding: ktoml stops at its first type error, and a half-decoded topology has nothing later to
 *  check), then the checks the decode does not make but the daemon would trip on: an undeclared provider, an auth
 *  kind its provider's dialect cannot carry, a bad or shared port, an override the daemon would ignore. The roster
 *  check is left out: it needs the models a runtime discovers, which boot does not have yet, so it stays a per-head
 *  DEGRADED, never a refusal.
 *
 *  [stateBase] only satisfies the knob check's constructor; nothing here reads state but [retired], which adds a
 *  retired knob still set in splice's environment or saved in its state folder, in every layer, since a file the
 *  decode refuses would otherwise hide them until a second boot. */
public object ConfigFindings {
    /** [configDir] is the topology's directory, so a head's relative `system_prompt_file` is read where the daemon
     *  reads it; null leaves the prompt check out. [runtimes] adds what each local runtime that is up refuses. */
    public fun read(
        text: String,
        stateBase: Path,
        configDir: Path? = null,
        runtimes: LocalRowFindings = LocalRowFindings { emptyList() },
        retired: RetiredSettings? = null,
    ): ConfigRead {
        // A structural finding is answered BEFORE any decode: a head roster written as an array of strings sends the
        // decoder into the loop the preflight exists to prevent, so the decoder never sees such a file.
        val structural = TomlStructurePreflight.failures(text).map { finding(it) }
        val elsewhere = { topology: Topology? -> retired?.findings(topology).orEmpty() }
        if (structural.isNotEmpty()) return refused(structural + TopologyTypeMismatch.all(text) + elsewhere(null))
        val decoded = decode(text)
        val topology = decoded.getOrNull()
            ?: return refused(undecodable(text, requireNotNull(decoded.exceptionOrNull())) + elsewhere(null))
        val checks = TopologyFindings.of(
            topology,
            HeadDiscoveredModels { emptyList() },
            stateBase,
            rosters = false,
            configDir = configDir,
        )
            .map { it.copy(message = withFix(it), line = lineOf(text, it.path)) }
        val local = runtimes.of(topology).distinct()
            .map { it.copy(line = rows.of(text, it.path) ?: lineOf(text, it.path)) }
        return refused(checks + local + elsewhere(topology), topology)
    }

    /** A knob finding keeps boot's own reason as written (the console writer pins it word for word); the boot
     *  refusal adds what to do about it. A retired knob has no value left to fix, only its line to remove. */
    private fun withFix(finding: TopologyFinding): String = when {
        !finding.path.startsWith("defaults.") && !finding.path.contains(".overrides.") -> finding.message
        finding.path.substringAfterLast('.') in retiredKnobsByKey -> "${finding.message}; remove the line"
        else -> "${finding.message}; fix the value or remove the line"
    }

    /** The decode as a value: its failure is a finding, never a throw out of a read. */
    private fun decode(text: String): Result<Topology> = try {
        Result.success(TopologyLoader.decode(text))
    } catch (broken: IllegalArgumentException) {
        Result.failure(broken)
    } catch (broken: SerializationException) {
        Result.failure(broken)
    }

    /** A file the decode refused: every wrong type and unknown key the schema proves, the line the parser names for a
     *  syntax error, and only when neither says anything a sentence that the text is withheld. */
    private fun undecodable(text: String, broken: Throwable): List<TopologyFinding> {
        val scanned = TopologyTypeMismatch.all(text)
        val syntax = syntaxFinding(broken)
        val rule = ruleFinding(text, broken)
        return when {
            rule != null -> scanned + rule
            syntax != null -> scanned + syntax
            scanned.isNotEmpty() -> scanned
            else -> listOf(TopologyFinding(FINDING_FILE, WITHHELD))
        }
    }

    /** A table's own rule, which the decoder may wrap: its key, splice's sentence, and the first line assigning it. */
    private fun ruleFinding(text: String, broken: Throwable): TopologyFinding? =
        generateSequence(broken) { it.cause }.take(MAX_CAUSES).filterIsInstance<TopologyRuleViolation>().firstOrNull()
            ?.let { rule ->
                val assigns = Regex("(^|[{,\\s])${Regex.escape(rule.key)}\\s*=")
                val line = text.lineSequence().indexOfFirst { assigns.containsMatchIn(it.substringBefore('#')) }
                TopologyFinding(FINDING_FILE, rule.message.orEmpty(), (line + 1).takeIf { line >= 0 })
            }

    /** The parser's own "Line N:" prefix is the only part of its message read, and only its digits: the rest can quote
     *  the value on that line. */
    private fun syntaxFinding(broken: Throwable): TopologyFinding? =
        PARSER_LINE.find(broken.message.orEmpty())?.let { found ->
            TopologyFinding(FINDING_FILE, SYNTAX, found.groupValues[1].toInt())
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
            val said = SafeFailureText.render(broken)
            val at = STRUCTURE_LINE.find(said)?.groupValues?.get(1)?.toInt()
            TopologyFinding(FINDING_FILE, said.replace(STRUCTURE_LINE, ""), at)
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

    private val rows = ModelRowLine()
    private const val FINDING_FILE = "splice.toml"
    private const val SYNTAX =
        "the parser cannot read this line; check it for a missing =, quote or bracket, or a value the setting rejects"
    private const val WITHHELD =
        "the file cannot be decoded and the parser's text is withheld because it can quote values; check the last edit"

    // why: a decoder wraps a rule failure at most a few levels deep; the walk stops on a cyclic or runaway chain.
    private const val MAX_CAUSES = 8

    private val PARSER_LINE = Regex("^Line (\\d+):")
    private val STRUCTURE_LINE = Regex(" \\(line (\\d+)\\)")
}
