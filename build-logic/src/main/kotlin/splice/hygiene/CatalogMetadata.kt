// THE CATALOG <-> VERIFICATION-METADATA SEAM (checks/catalog-metadata-sync.ts and its selftest until
// restructure PR 6; the task that runs it is splice.dependency-hygiene.gradle.kts).
//
// WHY THIS EXISTS (PR #91, and every gradle Dependabot PR before it): Dependabot can edit
// gradle/libs.versions.toml but cannot run the metadata regeneration, so every catalog bump arrives
// with gradle/verification-metadata.xml still pinning the OLD versions. With verify-metadata=true
// that is a guaranteed red — but it surfaces six minutes into the gradle leg of the gate, as a wall
// of "Dependency verification failed" noise. This states the same fact statically, in under a
// second, with the remedy attached.
//
// What is checked, per catalog table:
//   [libraries]  every entry carrying a version must appear in the metadata as a component
//                (group, name, version). Versionless entries (BOM riders) are skipped.
//   [plugins]    checked as their marker artifact: (id, id + ".gradle.plugin", version). A plugin
//                applied by bare id inside build-logic never resolves its marker, so the obligation
//                degrades to version presence (kotlin-serialization is that case today).
//   [versions]   keys no library or plugin resolves to are FLOOR pins (netty-style: declared so the
//                resolver and Dependabot have a line to hold/bump, materialised only as
//                transitives). No (group, name) is derivable statically, so the obligation is
//                presence: at least one metadata component at that version. A floor that matches
//                nothing is either an unregenerated bump or an inert floor that should be dropped —
//                both worth a red.
//
// THE CATALOG ARRIVES THROUGH GRADLE'S OWN VersionCatalogsExtension (see the plugin), never a second
// TOML parser. Two consequences, both deliberate: aliases arrive normalised (`ktor.server.core`), and
// a `version.ref` is indistinguishable from an inline version, so the checker's "referenced by
// neither table" reads here as "no library or plugin resolves to that version" — the same floors on
// this catalog, and a floor a library happens to share is covered by that library's own row. A rich
// version (strictly / prefer / reject) is a loud refusal in the plugin, never a skip.
//
// THE METADATA IS READ WITH THE JDK'S NAMESPACE-AWARE DOM, AND IT DIES WHERE A LENIENT READER WOULD
// BE SILENT: a document without the dependency-verification namespace, one yielding no <component>
// at all, or a component missing one of its three attributes is an [UnreadableMetadata], never an
// empty set. An empty set would read every entry as "not pinned" — a wall of false reds — and a
// partly read document would hide exactly the drift this exists to find.
package splice.hygiene

import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** One `(group, name, version)` the metadata pins. */
data class Component(val group: String, val name: String, val version: String)

/** A `[libraries]` entry as the build resolves it; [version] is null for a BOM rider. */
data class CatalogLibrary(val alias: String, val group: String, val name: String, val version: String?)

/** A `[plugins]` entry as the build resolves it. */
data class CatalogPlugin(val alias: String, val id: String, val version: String?)

/** The three catalog tables this check reads. */
data class Catalog(
    val versions: Map<String, String>,
    val libraries: List<CatalogLibrary>,
    val plugins: List<CatalogPlugin>,
)

/** A metadata document this check refuses to read as "empty" — see the header. */
class UnreadableMetadata(message: String) : IllegalStateException(message)

object CatalogMetadata {
    const val NAMESPACE = "https://schema.gradle.org/dependency-verification"

    val REMEDY = """
        |catalog-metadata-sync: gradle/libs.versions.toml declares versions that
        |gradle/verification-metadata.xml does not pin. Regenerate from the repository root —
        |BOTH passes, the shadowJar license pass fetches poms that `check` alone never resolves:
        |
        |    ./gradlew --write-verification-metadata sha256 clean check
        |    ./gradlew --write-verification-metadata sha256 :app:shadowJar --no-daemon --no-parallel
        |
        |then commit the regenerated gradle/verification-metadata.xml (precedent: PR #91).
    """.trimMargin()

    /** Every component the metadata pins, or an [UnreadableMetadata] naming what could not be read. */
    fun components(xml: String, where: String = "gradle/verification-metadata.xml"): Set<Component> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val document = factory.newDocumentBuilder().parse(xml.byteInputStream())
        if (document.documentElement.namespaceURI != NAMESPACE) {
            throw UnreadableMetadata("$where: no dependency-verification namespace declaration — unreadable metadata shape")
        }
        val tags = document.getElementsByTagNameNS(NAMESPACE, "component")
        if (tags.length == 0) {
            throw UnreadableMetadata("$where: no <component> elements found — an unread metadata file must not read as empty")
        }
        return (0 until tags.length).map { index -> component(tags.item(index) as Element, where) }.toSet()
    }

    private fun component(element: Element, where: String): Component {
        val attributes = listOf("group", "name", "version")
        if (attributes.any { !element.hasAttribute(it) }) {
            throw UnreadableMetadata("$where: a <component> carries no group/name/version: <component ${describe(element)}>")
        }
        return Component(element.getAttribute("group"), element.getAttribute("name"), element.getAttribute("version"))
    }

    private fun describe(element: Element): String =
        (0 until element.attributes.length).joinToString(" ") { index ->
            val attribute = element.attributes.item(index)
            "${attribute.nodeName}=\"${attribute.nodeValue}\""
        }

    /** The catalog entries the metadata does not pin, one line each, sorted; empty is green. */
    fun problems(catalog: Catalog, components: Set<Component>): List<String> {
        val pinnedVersions = components.map { it.version }.toSet()
        val missing = mutableListOf<String>()
        for (library in catalog.libraries) {
            // BOM rider: the version is supplied at resolution time, so there is nothing to pin.
            val version = library.version ?: continue
            if (Component(library.group, library.name, version) !in components) {
                missing += "libraries.${library.alias}: ${library.group}:${library.name}:$version not pinned in metadata"
            }
        }
        for (plugin in catalog.plugins) {
            val version = plugin.version ?: continue
            val marker = Component(plugin.id, "${plugin.id}.gradle.plugin", version)
            // A plugin requested through the plugins DSL resolves its marker; one applied by bare id
            // inside build-logic (implementation jar on that classpath) never does, so regeneration
            // cannot pin a marker for it. Degrade to version presence — an unregenerated bump still
            // has no component at the new version and stays red.
            if (marker !in components && version !in pinnedVersions) {
                missing += "plugins.${plugin.alias}: ${plugin.id}:${plugin.id}.gradle.plugin:$version (marker) not pinned, " +
                    "and no component at $version"
            }
        }
        val resolvedTo = (catalog.libraries.mapNotNull { it.version } + catalog.plugins.mapNotNull { it.version }).toSet()
        for ((key, version) in catalog.versions) {
            if (version in resolvedTo || version in pinnedVersions) continue
            missing += "[versions] $key = \"$version\": floor version matches no pinned component " +
                "(unregenerated bump, or an inert floor to drop)"
        }
        return missing.sorted()
    }

    /** The failure text: every problem indented, then the remedy — the checker's exact shape. */
    fun report(problems: List<String>): String = problems.joinToString("") { "  $it\n" } + REMEDY
}
