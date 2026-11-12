// NEW: Oct 13, 2026 (gaps 461/462) — the words Models' in-place fields offer, served beside the topology they edit, so no
// control's options are typed by hand in the page: the sign-in kinds splice knows, the items a command can share with
// Claude Code, and each quirk with its kind, its words and the dialects that read it.
package splice.configuration.topology

import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.topology.API_KEY_WIRE
import splice.core.topology.AuthKindRegistry
import splice.core.topology.ClaudeSharingDefaults
import splice.core.topology.Dialect
import splice.core.topology.DialectWires
import splice.core.topology.QuirkReaders
import splice.core.topology.QuirksConfig
import splice.core.topology.Topology

internal class TopologyVocab {
    private val kinds: Map<SerialKind, String> = mapOf(
        PrimitiveKind.BOOLEAN to "bool",
        PrimitiveKind.INT to "int",
        PrimitiveKind.LONG to "int",
        PrimitiveKind.STRING to "string",
        SerialKind.ENUM to "string",
        StructureKind.LIST to "list",
        StructureKind.CLASS to "table",
    )

    /** [topology] is the decoded file, for the items it shares; null when it does not decode, which shares the defaults. */
    fun of(topology: Topology?): JsonObject = buildJsonObject {
        putJsonArray("auth_kinds") {
            addJsonObject {
                put("kind", API_KEY_WIRE)
                put("label", "API key")
            }
            AuthKindRegistry.knownKinds().forEach { kind ->
                addJsonObject {
                    put("kind", kind.wire)
                    put("label", kind.signInLabel)
                }
            }
        }
        putJsonArray("share") { (topology?.claude ?: ClaudeSharingDefaults()).share.forEach { add(it) } }
        putJsonArray("quirks") {
            val quirks = QuirksConfig.serializer().descriptor
            for (i in 0 until quirks.elementsCount) {
                val name = quirks.getElementName(i)
                val readers = QuirkReaders.of(name)
                if (readers.isNotEmpty()) addJsonObject { quirk(this, name, quirks.getElementDescriptor(i), readers) }
            }
        }
    }

    private fun quirk(
        out: JsonObjectBuilder,
        name: String,
        field: SerialDescriptor,
        readers: Set<Dialect>,
    ) {
        out.put("name", name)
        out.put("kind", kinds[field.kind] ?: "table")
        if (field.kind == SerialKind.ENUM) {
            out.putJsonArray("words") { for (w in 0 until field.elementsCount) add(field.getElementName(w)) }
        }
        out.putJsonArray("dialects") { readers.forEach { add(DialectWires.name(it)) } }
    }
}
