// The two reports a fat jar carries about its own contents, cut to one platform's jar (PlatformJar): the CycloneDX
// bill of materials and the dependency-license list. The jar of all four names every platform's isolate library and
// native host, and a platform's jar holds one of each, so a report copied whole would list six things the jar lacks.
package splice.release

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** The key both reports keep their list under: the bill of materials its dependency graph, the license list its entries. */
private const val LISTED = "dependencies"

/** The reports without [dropped], the components a platform's jar does not hold, each as `group:name`. */
class PlatformReports(private val dropped: Set<String>) {
    /** The bill of materials [text] without the dropped components and every reference to them. Its serial number is
     *  made the way NormalizedBom makes one, from the document it now is, so two cuts never share one. */
    fun bom(text: String): String {
        val document = LinkedHashMap(parsed(text))
        val (gone, kept) = objects(document["components"]).partition { named(it) in dropped }
        val missing = dropped - gone.map(::named).toSet()
        check(missing.isEmpty()) { "platform jar: the bill of materials names no ${missing.joinToString()}" }
        val refs = gone.map { it["bom-ref"].toString() }.toSet()
        document["components"] = kept
        document[LISTED] = objects(document[LISTED]).filter { it["ref"].toString() !in refs }
            .map { entry ->
                LinkedHashMap(entry).also { held ->
                    held["dependsOn"] = (entry["dependsOn"] as? List<*>).orEmpty().filter { it.toString() !in refs }
                }
            }
        val unnumbered = LinkedHashMap(document).also { it.remove("serialNumber") }
        val digest = MessageDigest.getInstance("SHA-256").digest(JsonOutput.toJson(unnumbered).toByteArray())
        val halves = ByteBuffer.wrap(digest)
        document["serialNumber"] = "urn:uuid:${UUID(halves.long, halves.long)}"
        return JsonOutput.prettyPrint(JsonOutput.toJson(document)) + "\n"
    }

    /** The license list [text] without the dropped components, in the order it was in. */
    fun licenses(text: String): String {
        val entries = objects(parsed(text)[LISTED]).filter { it["moduleName"].toString() !in dropped }
        return JsonOutput.prettyPrint(JsonOutput.toJson(mapOf(LISTED to entries))) + "\n"
    }

    /** A component as the license list and [dropped] name it. */
    private fun named(component: Map<*, *>): String = "${component["group"]}:${component["name"]}"

    private fun parsed(text: String): Map<*, *> =
        JsonSlurper().parseText(text) as? Map<*, *> ?: error("platform jar: a report is not a JSON object")

    private fun <T> objects(list: T?): List<Map<*, *>> = (list as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
}
