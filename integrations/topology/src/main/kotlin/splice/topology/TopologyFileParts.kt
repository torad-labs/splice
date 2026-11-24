package splice.topology

import splice.core.capability.CapabilityDeclarations
import splice.core.capability.CapabilityToml
import splice.core.topology.Dialect
import splice.core.topology.Topology
import splice.core.topology.desktop.DesktopConfig
import splice.core.topology.desktop.DesktopToml
import splice.core.topology.mods.ModsToml
import splice.core.util.TopologyRefusal

/** Settings parsed outside the canonical schema, split once the same way for boot and plain reads. */
internal class TopologyFileParts(text: String) {
    val capabilities = CapabilityToml.split(text)
    val modes = ModeTables.split(capabilities.rest)
    val mods = ModsToml(modes.rest).split()
    val desktop = DesktopToml.split(mods.rest)
    val rest: String get() = desktop.rest

    fun parse(): Topology {
        // Guard structures that the decoder cannot read, even when no boot findings are being collected.
        if (mods.findings.isNotEmpty()) throw TopologyRefusal(mods.findings)
        TomlStructurePreflight.check(rest)
        val topology = ModeTables.attached(TopologyLoader.decode(rest), modes.tables)
        val messages = topology.headsSpeaking(Dialect.ANTHROPIC_PASSTHROUGH)
        val capabilities = CapabilityDeclarations(topology.heads.keys, messages = messages).read(capabilities.tables)
        val windows = desktop.read.config.takeIf { desktop.read.findings.isEmpty() } ?: DesktopConfig()
        val extensions = topology.extensions.copy(
            capabilities = capabilities.capabilities,
            mods = mods.config,
            desktop = windows,
        )
        return topology.copy(extensions = extensions)
    }
}
