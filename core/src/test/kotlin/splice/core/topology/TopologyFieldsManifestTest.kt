// NEW: V4-312 — every field splice.toml parses, as the checked-in manifest the console's coverage wall
// reads (console/src/shared/coverage/denominator.ts).
//
// WHY A DESCRIPTOR WALK: the wall used to grep six sources for `@SerialName`, which counts only the
// fields whose TOML key differs from the property. A field named by its property (`[projects]`, every
// `[compaction]` key) was never counted, so it could ship with no console answer and the wall stayed
// green. ktoml decodes the file through these same descriptors (TopologyLoader.parse), so the element
// names walked here ARE the file's keys, custom serializers included (ModelRatesToml is TomlRates').
//
// WHAT IT PINS: topology-fields.tsv equals the walk, line for line. A field added to or removed from
// any table type fails here BY NAME, with the exact lines to add or drop.
package splice.core.topology

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalSerializationApi::class)
class TopologyFieldsManifestTest {

    @Test
    fun `the manifest is every field splice toml parses, at its dotted path`() {
        val walked = walked().sorted()
        val manifest = checkNotNull(javaClass.getResource(manifestPath)) { "no $manifestPath on the test classpath" }
            .readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
        val missing = walked - manifest.toSet()
        val extra = manifest - walked.toSet()
        assertTrue(missing.isEmpty() && extra.isEmpty() && manifest == walked) {
            "core/src/test/resources$manifestPath drifted from Topology's serializer.\n" +
                "add:\n${missing.joinToString("\n")}\ndrop:\n${extra.joinToString("\n")}\n" +
                "(sorted order: ${manifest == manifest.sorted()})"
        }
    }

    @Test
    fun `the walk reaches the nested tables and carries an enum's values`() {
        val walked = walked()
        assertTrue("compaction.project[].instructions\t" in walked) { "no [[compaction.project]] row: $walked" }
        assertTrue(walked.any { it.startsWith("heads.*.") }) { "no [heads.KEY] field" }
        assertTrue(walked.any { it.startsWith("providers.*.dialect\t") && "openai-chat" in it }) { "no dialect values" }
    }

    private fun walked(): List<String> =
        mutableListOf<String>().also { walk(Topology.serializer().descriptor, "", it, emptySet()) }

    /** One line per element of [descriptor]'s table: `path<TAB>enum values`, then the element's own table. */
    private fun walk(descriptor: SerialDescriptor, prefix: String, out: MutableList<String>, seen: Set<String>) {
        for (index in 0 until descriptor.elementsCount) {
            val key = descriptor.getElementName(index)
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            val element = descriptor.getElementDescriptor(index)
            out += "$path\t${valuesOf(element).joinToString(",")}"
            descend(element, path, out, seen)
        }
    }

    /** A table recurses; a map's values sit under `.*` and an array's elements under `[]`; a scalar or
     *  enum ends the path. A map whose values are not tables gets its own `.*` line, since each entry is a
     *  field the file carries ([defaults], overrides). Any other kind fails, so a shape the walk cannot
     *  read is never a silent pass. */
    private fun descend(element: SerialDescriptor, path: String, out: MutableList<String>, seen: Set<String>) {
        val kind = element.kind
        val name = element.serialName.removeSuffix("?")
        when {
            kind == StructureKind.MAP -> {
                val value = element.getElementDescriptor(1)
                if (!isTable(value)) out += "$path.*\t${valuesOf(value).joinToString(",")}"
                descend(value, "$path.*", out, seen)
            }
            kind == StructureKind.LIST -> descend(element.getElementDescriptor(0), "$path[]", out, seen)
            isTable(element) -> {
                check(name !in seen) { "$path: $name contains itself; the manifest cannot list a cycle" }
                walk(element, path, out, seen + name)
            }
            isValue(element) -> Unit
            else -> error("$path: a $kind element the manifest cannot walk; teach the walk before the wall trusts it")
        }
    }

    /** The values an enum-typed element takes, through an array around it; none otherwise. A map's
     *  entries carry their own values on their `.*` line. */
    private fun valuesOf(element: SerialDescriptor): List<String> = when (element.kind) {
        SerialKind.ENUM -> (0 until element.elementsCount).map(element::getElementName)
        StructureKind.LIST -> valuesOf(element.getElementDescriptor(0))
        else -> emptyList()
    }

    private fun isTable(element: SerialDescriptor): Boolean = element.kind == StructureKind.CLASS && !element.isInline

    /** A scalar, an enum, an inline wrapper or an object: a value that ends its path. */
    private fun isValue(element: SerialDescriptor): Boolean = element.kind.let { kind ->
        kind is PrimitiveKind || kind == SerialKind.ENUM || element.isInline || kind == StructureKind.OBJECT
    }

    private val manifestPath = "/topology-fields.tsv"
}
