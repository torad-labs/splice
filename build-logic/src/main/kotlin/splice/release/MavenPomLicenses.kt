package splice.release

import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Resolves declared POM license inheritance; absent evidence still fails release compliance. */
object MavenPomLicenses {
    private data class Coordinate(val group: String, val name: String, val version: String) {
        val key: String get() = "$group:$name:$version"
    }
    private data class Pom(
        val coordinate: Coordinate,
        val parent: Coordinate?,
        val licenses: List<Map<String, Map<String, String>>>,
    )

    fun resolve(
        dependencies: DependencyHandler,
        configurations: ConfigurationContainer,
        modules: Collection<ModuleComponentIdentifier>,
    ): List<File> {
        val files = linkedSetOf<File>()
        val seen = mutableSetOf<Coordinate>()
        var pending = modules.map { Coordinate(it.group, it.module, it.version) }.toSet()
        while (pending.isNotEmpty()) {
            val requested = pending.map { dependencies.create("${it.group}:${it.name}:${it.version}@pom") }
            val metadata = configurations.detachedConfiguration()
            metadata.dependencies.addAll(requested)
            metadata.isTransitive = false
            val batch = metadata.resolve().toList()
            files += batch
            seen += pending
            pending = batch.mapNotNull { parse(it).parent }.filterNot { it in seen }.toSet()
        }
        return files.toList()
    }

    fun inherited(files: Collection<File>): Map<String, List<Map<String, Map<String, String>>>> {
        val poms = files.map(::parse).associateBy { it.coordinate }
        return poms.values.associate { pom -> pom.coordinate.key to effective(pom, poms, emptySet()) }
    }

    private fun effective(
        pom: Pom,
        poms: Map<Coordinate, Pom>,
        seen: Set<Coordinate>,
    ): List<Map<String, Map<String, String>>> {
        check(pom.coordinate !in seen) { "Cyclic Maven POM license inheritance" }
        return pom.licenses.ifEmpty {
            poms[pom.parent]?.let { effective(it, poms, seen + pom.coordinate) }.orEmpty()
        }
    }

    private fun parse(file: File): Pom {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.isExpandEntityReferences = false
        val project = file.inputStream().use { factory.newDocumentBuilder().parse(it).documentElement }
        val parent = child(project, "parent")?.let { coordinate(it, null) }
        val identity = coordinate(project, parent)
        val licenses = child(project, "licenses")?.let { declared ->
            children(declared, "license").mapNotNull { license ->
                val name = child(license, "name")?.textContent?.trim().orEmpty()
                name.takeIf(String::isNotEmpty)?.let {
                    val values = linkedMapOf("name" to it)
                    child(license, "url")?.textContent?.trim()?.let { url -> values["url"] = url }
                    mapOf("license" to values)
                }
            }
        }.orEmpty()
        return Pom(identity, parent, licenses)
    }

    private fun coordinate(element: Element, inherited: Coordinate?): Coordinate = Coordinate(
        child(element, "groupId")?.textContent?.trim() ?: inherited?.group.orEmpty(),
        child(element, "artifactId")?.textContent?.trim().orEmpty(),
        child(element, "version")?.textContent?.trim() ?: inherited?.version.orEmpty(),
    )

    private fun child(element: Element, name: String): Element? = children(element, name).singleOrNull()

    private fun children(element: Element, name: String): List<Element> = (0 until element.childNodes.length)
        .mapNotNull { element.childNodes.item(it) as? Element }.filter { it.tagName == name }
}
