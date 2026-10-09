// The runtime dependency-license inventory a release publishes, read from the CycloneDX BOM the build already made.
package splice.release

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** One entry per BOM component: `{moduleName: group:artifact, moduleVersion, moduleUrls, moduleLicenses: [{moduleLicense, moduleLicenseUrl}]}`,
 *  sorted by name and version. The license name is the BOM's SPDX id, else its name, else its expression, exactly as the POM declared it. */
@CacheableTask
abstract class LicenseInventory : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bom: RegularFileProperty

    /** The group of this build's own modules: they declare no third-party license and are not listed. */
    @get:Input
    abstract val firstPartyGroup: Property<String>

    @get:OutputFile
    abstract val inventory: RegularFileProperty

    @TaskAction
    fun write() {
        val document = JsonSlurper().parse(bom.get().asFile) as? Map<*, *> ?: error("${bom.get().asFile}: not a JSON object")
        val own = firstPartyGroup.get()
        val entries = (document["components"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
            .filter { it["group"] != own }.map { entry(it) }
            .sortedWith(compareBy({ it.getValue("moduleName").toString() }, { it.getValue("moduleVersion").toString() }))
        val output = inventory.get().asFile
        output.parentFile.mkdirs()
        output.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("dependencies" to entries))) + "\n")
    }

    private fun entry(component: Map<*, *>): Map<String, Any> {
        val group = component["group"]?.toString().orEmpty()
        val name = component["name"]?.toString().orEmpty()
        val licenses = (component["licenses"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { license(it) }
        val urls = (component["externalReferences"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
            .filter { it["type"] == "website" }.mapNotNull { it["url"]?.toString() }
        return linkedMapOf(
            "moduleName" to if (group.isEmpty()) name else "$group:$name",
            "moduleVersion" to component["version"]?.toString().orEmpty(),
            "moduleUrls" to urls,
            "moduleLicenses" to licenses,
        )
    }

    private fun license(choice: Map<*, *>): Map<String, Any>? {
        val declared = choice["license"] as? Map<*, *>
        val named = declared?.get("id") ?: declared?.get("name") ?: choice["expression"] ?: return null
        val result = linkedMapOf<String, Any>("moduleLicense" to named.toString())
        declared?.get("url")?.let { result["moduleLicenseUrl"] = it.toString() }
        return result
    }
}
