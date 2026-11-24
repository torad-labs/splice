package splice.topology

import splice.core.capability.CapabilityDeclarations
import splice.core.capability.CapabilityToml
import splice.core.topology.Dialect
import splice.core.topology.Topology
import splice.core.topology.toml.ModsToml
import splice.core.util.TopologyRefusal

/** Settings parsed outside the canonical schema, split once the same way for boot and plain reads. */
internal class TopologyFileParts(text: String) {
    val capabilities = CapabilityToml.split(text)
    val modes = ModeTables.split(capabilities.rest)
    val mods = ModsToml(modes.rest).split()

    fun parse(): Topology {
        // Guard structures that the decoder cannot read, even when no boot findings are being collected.
        if (mods.findings.isNotEmpty()) throw TopologyRefusal(mods.findings)
        TomlStructurePreflight.check(mods.rest)
        val topology = ModeTables.attached(TopologyLoader.decode(mods.rest), modes.tables)
        val messages = topology.headsSpeaking(Dialect.ANTHROPIC_PASSTHROUGH)
        val capabilities = CapabilityDeclarations(topology.heads.keys, messages = messages).read(capabilities.tables)
        val extensions = topology.extensions.copy(mods = mods.config, capabilities = capabilities.capabilities)
        return topology.copy(extensions = extensions)
    }
}
