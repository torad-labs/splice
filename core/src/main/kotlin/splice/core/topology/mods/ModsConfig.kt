package splice.core.topology.mods

import splice.core.topology.TopologyFinding
import java.net.URI

/** Pinned mode archives declared outside the canonical topology schema. */
public data class ModsConfig(
    val dir: String? = null,
    val jars: List<ModJar> = emptyList(),
    val dirLine: Int? = null,
)

/** A well-formed declaration, still to be verified against its archive. */
public data class ModJar(
    val name: String,
    val path: String?,
    val url: URI?,
    val sha256: String,
    val index: Int,
    val lines: Map<String, Int>,
) {
    public fun finding(message: String, key: String = ""): TopologyFinding {
        val field = if (key.isEmpty()) "" else ".$key"
        return TopologyFinding("mods.jar[$index]$field", message, lines[key] ?: lines[""])
    }
}
