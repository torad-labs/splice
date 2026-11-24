// NEW: configurable Desktop Code-tab window (issue #791). The `[desktop]` table of splice.toml: the window
// Claude Desktop gives its Code tab's Claude Code, and the one it gives a model picked as its 1M variant, as
// this install's Desktop sets them (its shipped 200,000 and 1,000,000, unless the table says otherwise). The
// door scales each app-mode turn's counts to these windows (DesktopAppWindow), so an install whose Desktop is
// given another window — a managed policy, a newer Desktop — states it here and compaction agrees with the
// terminal setup. Read when the daemon starts; an edit is a restart, like adding a provider.
//
// The table is read apart from the schema decode the way the capability and mode tables are (CapabilityToml),
// so each wrong value is a finding of its own at its key path, all listed at once, and it rides the topology
// through TopologyExtensions rather than widening Topology. The values are read as whole numbers here and
// checked here, so a bad one never hides the rest behind a decode error.
package splice.core.topology.desktop

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import splice.core.capability.TomlValueRead
import splice.core.model.CLAUDE_CODE_ONE_MILLION
import splice.core.model.CLIENT_TABLE_WINDOW
import splice.core.topology.TopologyFinding
import splice.core.topology.toml.TomlKind
import splice.core.topology.toml.TomlScan

/** `[desktop]`: the Code-tab windows splice scales Claude Desktop's app-mode counts to. */
public data class DesktopConfig(
    /** The window for a model picked as itself. Desktop's shipped value when the table is absent. */
    val codeWindow: Long = CLIENT_TABLE_WINDOW,
    /** The window for a model picked as its 1M variant; never below [codeWindow]. Desktop also takes this
     *  value as the bar a provider's real window must reach before splice advertises a 1M variant for it:
     *  the variant is a promise the model can fill the big window splice scales to. */
    val codeWindow1m: Long = CLAUDE_CODE_ONE_MILLION,
)

/** The `[desktop]` table read out of splice.toml: the windows it states (Desktop's shipped ones where it is
 *  silent or wrong) and a finding for each fault, at its key path and line, never quoting a value. */
public data class DesktopRead(val config: DesktopConfig, val findings: List<TopologyFinding>)

/** splice.toml with its `[desktop]` table taken out ([rest], same line count), and that table read. */
public data class DesktopText(val rest: String, val read: DesktopRead)

public object DesktopToml {
    /** Splits [text] into what the schema decodes and the `[desktop]` table; a file without one comes back as
     *  it is, read as Desktop's shipped windows. */
    public fun split(text: String): DesktopText {
        val entries = TomlScan(text).entries().filter { it.path.firstOrNull() == DESKTOP_TABLE }
        if (entries.isEmpty()) return DesktopText(text, DesktopRead(DesktopConfig(), emptyList()))
        val blanked = text.toCharArray()
        val check = DesktopTableCheck()
        for (entry in entries) {
            for (i in entry.start until entry.end) {
                if (blanked[i] != '\n' && blanked[i] != '\r') blanked[i] = ' '
            }
            val line = 1 + (0 until entry.start).count { text[it] == '\n' }
            val value = TomlValueRead(text.substring(entry.valueStart, entry.valueEnd)).read() ?: JsonNull
            check.see(entry.kind, entry.path.drop(1), value, line)
        }
        return DesktopText(String(blanked), check.read())
    }
}

/** One `[desktop]` table's reading: its keys as written, then the windows they state with a finding for each
 *  fault. A dotted `desktop.code_window` at the file's root and an inline `desktop = { ... }` land as the
 *  same keys a `[desktop]` table's lines do. */
private class DesktopTableCheck {
    private val keys = LinkedHashMap<String, JsonElement>()
    private val lines = LinkedHashMap<String, Int>()
    private val findings = mutableListOf<TopologyFinding>()
    private var tableLine: Int? = null

    fun see(kind: TomlKind, rest: List<String>, value: JsonElement, line: Int) {
        when (kind) {
            TomlKind.ARRAY_TABLE -> fault(rest, LIST_OF_TABLES, line)
            TomlKind.TABLE -> table(rest, line)
            TomlKind.KEY -> key(rest, value, line)
        }
    }

    private fun table(rest: List<String>, line: Int) {
        if (rest.isEmpty()) defined(line) else fault(rest, NOT_A_SETTING, line)
    }

    // TOML forbids defining a table twice, by a header or an inline table, and the split has already blanked this one
    // out of the text the structural preflight reads, so the refusal the schema's tables get there is this reader's.
    private fun defined(line: Int) {
        if (tableLine != null) findings += TopologyFinding(DESKTOP_TABLE, TABLE_TWICE, line) else tableLine = line
    }

    private fun key(rest: List<String>, value: JsonElement, line: Int) {
        when {
            rest.isEmpty() && value is JsonObject -> {
                defined(line)
                value.forEach { (name, held) -> put(name, held, line) }
            }
            rest.isEmpty() -> findings += TopologyFinding(DESKTOP_TABLE, NOT_A_TABLE, line)
            rest.size == 1 -> put(rest.single(), value, line)
            else -> fault(rest, NOT_A_SETTING, line)
        }
    }

    private fun put(name: String, value: JsonElement, line: Int) {
        // A key written twice is TOML's to forbid; the preflight never sees this blanked table, so it is
        // refused here and the first writing stands, the way the mods reader holds a repeated key.
        if (name in keys) {
            fault(listOf(name), ALREADY_DECLARED, line)
            return
        }
        lines[name] = line
        keys[name] = value
    }

    private fun fault(rest: List<String>, message: String, line: Int?) {
        val path = (listOf(DESKTOP_TABLE) + rest).joinToString(".")
        findings += TopologyFinding(path, message, line)
    }

    fun read(): DesktopRead {
        keys.keys.filter { it !in WINDOW_KEYS }.forEach { name ->
            fault(listOf(name), NOT_A_SETTING, lines[name] ?: tableLine)
        }
        val standard = window(CODE_WINDOW_KEY, CLIENT_TABLE_WINDOW)
        val million = window(CODE_WINDOW_1M_KEY, CLAUDE_CODE_ONE_MILLION)
        // The pair as it would run, absent keys filled by their defaults, so a lone `code_window` past the
        // shipped 1M window refuses too; a value already refused as not positive draws no second finding.
        if (standard > 0 && million in 1 until standard) {
            findings += TopologyFinding(
                "$DESKTOP_TABLE.$CODE_WINDOW_1M_KEY",
                AT_LEAST_STANDARD,
                lines[CODE_WINDOW_1M_KEY] ?: lines[CODE_WINDOW_KEY] ?: tableLine,
            )
        }
        return DesktopRead(DesktopConfig(standard, million), findings)
    }

    /** The window [name] states: [shipped] when the table is silent or the value is no whole number, else the
     *  value as written, with its finding when it cannot run. */
    private fun window(name: String, shipped: Long): Long {
        val value = keys[name] ?: return shipped
        val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        if (number == null) {
            fault(listOf(name), WHOLE_NUMBER, lines[name])
            return shipped
        }
        if (number <= 0) fault(listOf(name), POSITIVE, lines[name])
        return number
    }
}

/** The table splice.toml states Claude Desktop's Code-tab windows in: `[desktop]`. */
private const val DESKTOP_TABLE = "desktop"

/** A `[desktop]` table's two keys, as written in splice.toml. */
private const val CODE_WINDOW_KEY = "code_window"
private const val CODE_WINDOW_1M_KEY = "code_window_1m"

private val WINDOW_KEYS = setOf(CODE_WINDOW_KEY, CODE_WINDOW_1M_KEY)

private const val NOT_A_SETTING = "is not a splice.toml setting; remove the line"
private const val NOT_A_TABLE = "is not a table; write it as a [desktop] table"
private const val TABLE_TWICE = "[desktop] is already declared"
private const val ALREADY_DECLARED = "already declared"
private const val LIST_OF_TABLES = "is a list of tables; write it as one [desktop] table"
private const val WHOLE_NUMBER = "must be a whole number of tokens"
private const val POSITIVE = "must be positive"
private const val AT_LEAST_STANDARD = "must be at least code_window, the window for a model picked as itself"
