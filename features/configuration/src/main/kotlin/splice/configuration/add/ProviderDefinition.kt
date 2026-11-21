// NEW: custom-provider (issue #680, cp-install). A team's provider definition, as text: a TOML file in splice.toml's
// own grammar holding one [providers.<key>] table with its [[providers.<key>.models]], the [heads.*] on that provider,
// and any [door.commands.*] on those heads; nothing else. A head may leave out `port` and `discovery_prefix`: splice
// fills them in here, before the text is decoded, so the definition decodes as splice.toml does.
package splice.configuration.add

import splice.core.topology.Topology
import splice.core.topology.TopologyFinding

/** One table header of a definition: its dotted path (quotes removed) and the line it sits on. */
internal data class DefinitionTable(val path: List<String>, val line: Int)

/** [text] as a provider definition. */
internal class ProviderDefinition(private val text: String) {
    private val lines = text.lines()
    private val tables: List<DefinitionTable> = lines.mapIndexedNotNull { i, raw ->
        HEADER.matchEntire(raw.substringBefore('#').trim())?.let { m ->
            DefinitionTable(SEGMENT.findAll(m.groupValues[1]).map { it.value.trim('"', '\'') }.toList(), i + 1)
        }
    }

    /** The provider it defines: the key of its first [providers.<key>] table, or null when it holds none. */
    val providerKey: String? =
        tables.firstOrNull { it.path.firstOrNull() == PROVIDERS && it.path.size >= 2 }?.path?.get(1)

    /** The heads it holds, in its order. */
    val heads: List<String> = tables.filter { it.path.size == 2 && it.path[0] == HEADS }.map { it.path[1] }.distinct()

    /** What the definition holds that a definition may not, each named by its table, before anything is decoded. */
    fun findings(): List<TopologyFinding> {
        val key = providerKey ?: return listOf(TopologyFinding(DEFINITION, "holds no [providers.<key>] table"))
        val loose = lines.takeWhile { HEADER.matchEntire(it.substringBefore('#').trim()) == null }
            .mapIndexedNotNull { i, raw ->
                KEY.find(raw.substringBefore('#'))?.let { TopologyFinding(it.groupValues[1], only(key), i + 1) }
            }
        val foreign = tables.filterNot { allowed(it.path, key) }.map { table ->
            val other = table.path.getOrNull(1)?.takeIf { table.path.first() == PROVIDERS }
            val path = if (other != null) "$PROVIDERS.$other" else table.path.first()
            val why = if (other != null) "a provider definition holds one provider, $key" else only(key)
            TopologyFinding(path, why, table.line)
        }
        return (loose + foreign).distinctBy { it.path }
    }

    /** The definition's own findings once decoded: a head on another provider, a door command on a head it does not
     *  hold. */
    fun decodedFindings(decoded: Topology): List<TopologyFinding> {
        val key = providerKey ?: return emptyList()
        val heads = decoded.heads.filterValues { it.provider != key }.map { (head, _) ->
            TopologyFinding("heads.$head.provider", "a head in this definition must be on its provider, $key")
        }
        val commands = decoded.door.commands.filterValues { it.head !in decoded.heads }.map { (name, _) ->
            TopologyFinding("door.commands.$name.head", "names a head this definition does not hold")
        }
        return heads + commands
    }

    /** The text with each head's missing `port` and `discovery_prefix` written in: its port from [ports], its prefix
     *  from [prefixes] (the one a head already installed keeps), else `claude-<head>--`. */
    fun filled(ports: Map<String, Int>, prefixes: Map<String, String>): String {
        val out = lines.toMutableList()
        heads.reversed().forEach { head ->
            val (header, end) = span(head)
            val body = out.subList(header + 1, end).map { it.substringBefore('#') }
            val added = buildList {
                if (body.none { PORT.containsMatchIn(it) }) ports[head]?.let { add("port = $it") }
                if (body.none { DISCOVERY.containsMatchIn(it) }) {
                    add("discovery_prefix = \"${prefixes[head] ?: "claude-$head--"}\"")
                }
            }
            out.addAll(header + 1, added)
        }
        return out.joinToString("\n")
    }

    /** The heads that name no port of their own, which splice chooses one for. */
    fun portless(): List<String> = heads.filter { head ->
        val (header, end) = span(head)
        lines.subList(header + 1, end).none { PORT.containsMatchIn(it.substringBefore('#')) }
    }

    /** Head [head]'s table in [lines]: the index of its header line and the index of the next table's header, or the
     *  end of the text. Filling only adds lines after a header, and heads are filled last to first, so the span read
     *  from the definition as written is the span in the text being filled. */
    private fun span(head: String): Pair<Int, Int> {
        val header = tables.first { it.path == listOf(HEADS, head) }.line - 1
        val end = tables.firstOrNull { it.line - 1 > header }?.let { it.line - 1 } ?: lines.size
        return header to end
    }

    private fun allowed(path: List<String>, key: String): Boolean = when (path.firstOrNull()) {
        PROVIDERS -> path.getOrNull(1) == key
        HEADS -> path.size >= 2
        DOOR -> path.getOrNull(1) == COMMANDS && path.size >= DOOR_COMMAND_DEPTH
        else -> false
    }

    private fun only(key: String): String =
        "a provider definition holds only [providers.$key], its [[providers.$key.models]], the [heads.*] on it and " +
            "their [door.commands.*]; remove it"
}

private const val PROVIDERS = "providers"
private const val HEADS = "heads"
private const val DOOR = "door"
private const val COMMANDS = "commands"
private const val DEFINITION = "definition"

private val HEADER = Regex("""^\[\[?\s*(.+?)\s*]]?$""")
private val SEGMENT = Regex(""""[^"]*"|'[^']*'|[^.\s]+""")
private val KEY = Regex("""^\s*([A-Za-z0-9_-]+)\s*=""")
private val PORT = Regex("""^\s*port\s*=""")
private val DISCOVERY = Regex("""^\s*discovery_prefix\s*=""")

// A door command's table is three names deep: door, commands, and the command's own.
private const val DOOR_COMMAND_DEPTH = 3
