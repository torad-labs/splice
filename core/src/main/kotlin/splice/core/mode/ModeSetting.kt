// NEW: splice.toml's `[modes.<name>]` tables (modes/README.md, "Turning a mode on and off"), read apart from the
// schema decode the way capability tables are (CapabilityToml), so each wrong one is a finding of its own, all listed
// at once, and none quotes a value. The modes themselves are :modes-api's: core is handed their names, never them.
package splice.core.mode

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import splice.core.capability.CapabilityTable
import splice.core.capability.CapabilityToml
import splice.core.topology.TopologyFinding

/** One mode's switches in splice.toml: [on] for every head (null when unset), and [heads], head key -> on. */
public data class ModeSetting(val on: Boolean? = null, val heads: Map<String, Boolean> = emptyMap())

/** The modes splice carries, as boot is handed them: their names, and the catalog's findings for broken ones. */
public data class FoundModes(val names: Set<String>, val findings: List<TopologyFinding> = emptyList())

/** The well-formed tables by mode name, and a finding for each fault. */
public data class ModeSettingsRead(val settings: Map<String, ModeSetting>, val findings: List<TopologyFinding>)

/** Reads `[modes.<name>]` tables against the heads splice.toml declares ([heads] null leaves that check out) and the
 *  modes splice found ([modes] null leaves that check out). */
public class ModeSettingsReader(private val heads: Set<String>?, private val modes: Set<String>?) {
    public fun read(tables: List<CapabilityTable>): ModeSettingsRead {
        val findings = mutableListOf<TopologyFinding>()
        val settings = LinkedHashMap<String, ModeSetting>()
        for (table in tables) {
            val before = findings.size
            val setting = ModeTableCheck(table, heads, modes, findings).setting()
            if (findings.size == before) settings[table.name] = setting
        }
        return ModeSettingsRead(settings, findings)
    }
}

/** One table's reading; each fault goes to [findings] by its key path. */
private class ModeTableCheck(
    private val table: CapabilityTable,
    private val heads: Set<String>?,
    private val modes: Set<String>?,
    private val findings: MutableList<TopologyFinding>,
) {
    private val path = "$MODE_TABLES.${table.name}"

    private fun fault(key: String?, message: String) {
        val at = key?.let { table.lines[it] } ?: table.line
        findings += TopologyFinding(if (key == null) path else "$path.$key", message, at)
    }

    fun setting(): ModeSetting {
        if (modes != null && table.name !in modes) fault(null, "names no mode splice carries")
        if (table.listed) fault(null, "is a list of tables; write it as one [$path] table")
        table.keys.keys.filter { it !in KEYS }.forEach { key ->
            if (key == CapabilityToml.WHOLE) fault(null, "is not a table") else fault(key, NOT_A_KEY)
        }
        return ModeSetting(on(), heads())
    }

    private fun on(): Boolean? {
        val value = table.keys[MODE_ON] ?: return null
        return flag(value) ?: null.also { fault(MODE_ON, "is not true or false") }
    }

    private fun heads(): Map<String, Boolean> {
        val value = table.keys[MODE_HEADS] ?: return emptyMap()
        val entries = value as? JsonObject
        if (entries == null) fault(MODE_HEADS, "is not a table of head = true or false")
        return entries.orEmpty().mapNotNull { (head, on) -> head(head, on)?.let { head to it } }.toMap()
    }

    /** One `heads` entry: its value when it is a boolean for a declared head, else null with its findings. */
    private fun head(head: String, value: JsonElement): Boolean? {
        val at = table.lines[MODE_HEADS] ?: table.line
        val known = heads == null || head in heads
        val key = "$path.$MODE_HEADS.$head"
        if (!known) findings += TopologyFinding(key, "names a head splice.toml does not declare", at)
        val flag = flag(value)
        if (flag == null) findings += TopologyFinding(key, "is not true or false", at)
        return flag?.takeIf { known }
    }

    private fun flag(value: JsonElement): Boolean? =
        (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
}

/** The tables splice.toml turns modes on and off with: `[modes.<name>]`. */
public const val MODE_TABLES: String = "modes"

/** A `[modes.<name>]` table's every-head switch, and its per-head table. */
public const val MODE_ON: String = "on"

public const val MODE_HEADS: String = "heads"

private val KEYS = setOf(MODE_ON, MODE_HEADS)
private const val NOT_A_KEY = "is not a key a mode's table takes"
