package splice.core.topology.toml

import kotlinx.serialization.json.JsonPrimitive
import splice.core.capability.TomlValueRead
import splice.core.topology.ModJar
import splice.core.topology.ModsConfig
import splice.core.topology.TopologyFinding

/** Reads and blanks the mod tables in place, leaving line numbers of all other settings alone. */
public class ModsToml(private val text: String) {
    private val findings = mutableListOf<TopologyFinding>()
    private val rows = mutableListOf<Row>()
    private var dir: String? = null
    private var dirLine: Int? = null
    private var rootSeen = false
    private var current: Row? = null

    public fun split(): Read {
        val entries = TomlScan(text).entries().filter { it.path.firstOrNull() == MODS_ROOT }
        if (entries.isEmpty()) return Read(text, ModsConfig(), emptyList())
        val rest = text.toCharArray()
        entries.forEach { entry ->
            blank(rest, entry)
            val line = 1 + (0 until entry.start).count { text[it] == '\n' }
            when (entry.kind) {
                TomlKind.ARRAY_TABLE -> jarHeader(entry, line)
                TomlKind.TABLE -> rootHeader(entry, line)
                TomlKind.KEY -> key(entry, line)
            }
        }
        val names = HashSet<String>()
        val jars = rows.mapNotNull { it.read(names, findings) }
        val usable = if (dirLine != null && dir == null) jars.filter { it.url != null } else jars
        return Read(String(rest), ModsConfig(dir, usable, dirLine), findings)
    }

    private fun blank(rest: CharArray, entry: TomlEntry) {
        for (offset in entry.start until entry.end) {
            if (rest[offset] != '\n' && rest[offset] != '\r') rest[offset] = ' '
        }
    }

    private fun jarHeader(entry: TomlEntry, line: Int) {
        current = if (entry.path == JAR_TABLE && JAR_HEADER.matches(header(entry))) {
            Row(rows.size + 1, line).also(rows::add)
        } else {
            findings += TopologyFinding(MODS_ROOT, "expected [[mods.jar]] for an archive", line)
            null
        }
    }

    private fun rootHeader(entry: TomlEntry, line: Int) {
        current = null
        if (entry.path != listOf(MODS_ROOT) || !MODS_ROOT_HEADER.matches(header(entry))) {
            findings += TopologyFinding(MODS_ROOT, "expected [mods] or [[mods.jar]]", line)
        } else if (rootSeen) {
            findings += TopologyFinding(MODS_ROOT, "[mods] is already declared", line)
        } else {
            rootSeen = true
        }
    }

    private fun header(entry: TomlEntry): String = text.substring(entry.start, entry.end).substringBefore('#').trim()

    private fun key(entry: TomlEntry, line: Int) {
        val row = current?.takeIf { entry.tableDepth == JAR_TABLE.size }
        when {
            entry.path == DIR_KEY -> directory(value(entry), line)
            row != null && entry.path.dropLast(1) == JAR_TABLE ->
                row.put(entry.path.last(), value(entry), line, findings)
            else -> findings += TopologyFinding(MODS_ROOT, "unknown mod setting; use dir or a [[mods.jar]] entry", line)
        }
    }

    private fun value(entry: TomlEntry): String? {
        val assignment = text.substring(entry.start, entry.valueStart).trimEnd()
        val trailing = text.substring(entry.valueEnd, entry.end).trimStart()
        val raw = text.substring(entry.valueStart, entry.valueEnd)
        val isString = assignment.endsWith('=') && raw.firstOrNull() in STRING_QUOTES
        val cleanEnd = trailing.isEmpty() || trailing.startsWith('#')
        if (!isString || !cleanEnd) return null
        val primitive = TomlValueRead(raw).read() as? JsonPrimitive
        return primitive?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    }

    private fun directory(value: String?, line: Int) {
        if (dirLine != null) {
            findings += TopologyFinding("mods.dir", "already declared", line)
        } else {
            dirLine = line
            dir = value
            if (value == null) findings += TopologyFinding("mods.dir", "expected a non-empty TOML string", line)
        }
    }

    public data class Read(val rest: String, val config: ModsConfig, val findings: List<TopologyFinding>)

    private class Row(private val index: Int, line: Int) {
        private val values = mutableMapOf<String, String?>()
        private val lines = mutableMapOf("" to line)

        fun put(key: String, value: String?, line: Int, findings: MutableList<TopologyFinding>) {
            when {
                key !in KEYS -> findings += TopologyFinding("mods.jar[$index]", "unknown mod entry key", line)
                key in lines -> findings += TopologyFinding("mods.jar[$index].$key", "already declared", line)
                else -> {
                    lines[key] = line
                    values[key] = value
                    if (value == null) findings += issue(key, "expected a non-empty TOML string")
                }
            }
        }

        fun read(names: MutableSet<String>, findings: MutableList<TopologyFinding>): ModJar? {
            val name = name(names, findings)
            val hash = values["sha256"]?.takeIf(PIN::matches)?.lowercase()
            if (hash == null) findings += issue("sha256", "required: 64 hexadecimal SHA-256 digits")
            val source = source(findings)
            if (name == null || hash == null) return null
            return source?.let { (path, url) -> ModJar(name, path, url, hash, index, lines) }
        }

        private fun name(names: MutableSet<String>, findings: MutableList<TopologyFinding>): String? {
            val name = values["name"]
            if ("name" !in values) findings += issue("name", "required")
            if (name != null && !names.add(name)) findings += issue("name", "duplicate mod name")
            return name
        }

        private fun source(findings: MutableList<TopologyFinding>): Pair<String?, String?>? {
            if (("path" in values) == ("url" in values)) {
                findings += issue("", "declare exactly one of path or url")
                return null
            }
            return (values["path"] to values["url"]).takeUnless { (path, url) -> (path ?: url) == null }
        }

        private fun issue(key: String, message: String): TopologyFinding {
            val field = if (key.isEmpty()) "" else ".$key"
            return TopologyFinding("mods.jar[$index]$field", message, lines[key] ?: lines[""])
        }
    }
}

private const val MODS_ROOT = "mods"
private val JAR_TABLE = listOf(MODS_ROOT, "jar")
private val DIR_KEY = listOf(MODS_ROOT, "dir")
private val KEYS = setOf("name", "path", "url", "sha256")
private const val MODS_KEY = """(?:mods|"mods"|'mods')"""
private const val JAR_KEY = """(?:jar|"jar"|'jar')"""
private val MODS_ROOT_HEADER = Regex("""\[\s*$MODS_KEY\s*]""")
private val JAR_HEADER = Regex("""\[\[\s*$MODS_KEY\s*\.\s*$JAR_KEY\s*]]""")
private val PIN = Regex("[0-9a-fA-F]{64}")
private val STRING_QUOTES = setOf('"', '\'')
