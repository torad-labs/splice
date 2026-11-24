// NEW: splice.toml's `[modes.<name>]` tables (modes/README.md), read apart from the schema decode the way capability
// tables are: the parse takes them out of the text, and the well-formed ones ride the topology.
package splice.topology

import splice.core.capability.CapabilityTable
import splice.core.capability.CapabilityText
import splice.core.capability.CapabilityToml
import splice.core.mode.MODE_TABLES
import splice.core.mode.ModeSettingsReader
import splice.core.topology.Topology

internal object ModeTables {
    /** [text] with its `[modes.<name>]` tables taken out (same line count), and those tables. */
    fun split(text: String): CapabilityText = CapabilityToml.split(text, MODE_TABLES)

    /** [topology] with the well-formed tables among [tables]; the parse checks no mode names, boot does. */
    fun attached(topology: Topology, tables: List<CapabilityTable>): Topology = if (tables.isEmpty()) {
        topology
    } else {
        val modes = ModeSettingsReader(topology.heads.keys, null).read(tables).settings
        topology.copy(extensions = topology.extensions.copy(modes = modes))
    }
}
