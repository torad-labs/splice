// Makes the CycloneDX report reproducible before it becomes the release's published BOM.
package splice.release

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

@CacheableTask
abstract class NormalizedBom : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: RegularFileProperty

    @get:Input
    abstract val releaseGroup: Property<String>

    @get:Input
    abstract val releaseVersion: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val nativeManifests: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val licensePoms: ConfigurableFileCollection

    @get:OutputFile
    abstract val output: RegularFileProperty

    private val graalJsCommunityIsolates = setOf(
        "org.graalvm.js:js-isolate-darwin-aarch64-community:25.4.4.1.1",
        "org.graalvm.js:js-isolate-linux-aarch64-community:25.4.4.1.1",
        "org.graalvm.js:js-isolate-windows-amd64-community:25.4.4.1.1",
    )

    private val graalJsCommunityIsolateLicenses = listOf(
        linkedMapOf("license" to linkedMapOf("name" to "MIT License", "url" to "http://opensource.org/licenses/MIT")),
        linkedMapOf(
            "license" to linkedMapOf(
                "name" to "Universal Permissive License, Version 1.0",
                "url" to "http://opensource.org/licenses/UPL",
            ),
        ),
    )

    @TaskAction
    fun normalize() {
        val input = source.get().asFile
        val bomJson = objectOrError(JsonSlurper().parse(input), "$input: root")
        val stableBom = LinkedHashMap(bomJson)
        val metadata = normalizedMetadata(bomJson, input)
        stableBom["metadata"] = metadata
        val refReplacements = LinkedHashMap<String, String>()
        val native = NativeCodeModeInventory.components(nativeManifests.files)
        stableBom["components"] = normalizedComponents(bomJson, input, refReplacements) + native
        val dependencies = normalizedDependencies(bomJson, input, refReplacements)
        if (native.isNotEmpty()) {
            val component = objectOrError(metadata["component"], "$input: root component")
            val root = dependencies.firstOrNull { it["ref"] == component[BOM_REFERENCE_FIELD] }
                ?: error("Native runtime BOM has no root dependency entry")
            val prior = (root[BOM_DEPENDS_ON_FIELD] as? List<*>).orEmpty()
            root[BOM_DEPENDS_ON_FIELD] = prior + native.map { it[BOM_REFERENCE_FIELD] }
        }
        stableBom["dependencies"] = dependencies + native.map {
            linkedMapOf("ref" to it[BOM_REFERENCE_FIELD], BOM_DEPENDS_ON_FIELD to emptyList<String>())
        }
        stableBom["serialNumber"] = serialNumber(stableBom)
        write(stableBom)
    }

    private fun normalizedMetadata(bom: Map<*, *>, input: File): Map<Any?, Any?> {
        val metadata = LinkedHashMap(objectOrError(bom["metadata"], "$input: metadata"))
        metadata.remove("timestamp")
        val component = LinkedHashMap(objectOrError(metadata["component"], "$input: metadata component"))
        component.remove("externalReferences")
        metadata["component"] = component
        return metadata
    }

    private fun normalizedComponents(
        bom: Map<*, *>,
        input: File,
        replacements: MutableMap<String, String>,
    ): List<LinkedHashMap<Any?, Any?>> {
        val group = releaseGroup.get()
        val version = releaseVersion.get()
        val inherited = MavenPomLicenses.inherited(licensePoms.files)
        return (bom["components"] as? List<*>).orEmpty().map { raw ->
            LinkedHashMap(objectOrError(raw, "$input: component")).also { entry ->
                normalizeComponentIdentity(entry, group, version, replacements)
                addIsolateLicenses(entry)
                val key = "${entry["group"]}:${entry["name"]}:${entry["version"]}"
                if ((entry[BOM_LICENSES_FIELD] as? List<*>).isNullOrEmpty() && !inherited[key].isNullOrEmpty()) {
                    entry[BOM_LICENSES_FIELD] = inherited.getValue(key)
                }
            }
        }
    }

    private fun normalizeComponentIdentity(
        entry: MutableMap<Any?, Any?>,
        group: String,
        version: String,
        replacements: MutableMap<String, String>,
    ) {
        if (entry["group"] != group || entry["version"] != "unspecified") return
        entry["version"] = version
        listOf(BOM_REFERENCE_FIELD, "purl").forEach { key ->
            val old = entry[key]?.toString() ?: return@forEach
            val updated = old.replace("@unspecified", "@$version")
            entry[key] = updated
            if (key == BOM_REFERENCE_FIELD) replacements[old] = updated
        }
    }

    private fun addIsolateLicenses(entry: MutableMap<Any?, Any?>) {
        val coordinate = "${entry["group"]}:${entry["name"]}:${entry["version"]}"
        if (coordinate in graalJsCommunityIsolates && (entry[BOM_LICENSES_FIELD] as? List<*>).isNullOrEmpty()) {
            entry[BOM_LICENSES_FIELD] = graalJsCommunityIsolateLicenses
        }
    }

    private fun normalizedDependencies(
        bom: Map<*, *>,
        input: File,
        replacements: Map<String, String>,
    ): List<LinkedHashMap<Any?, Any?>> =
        (bom["dependencies"] as? List<*>).orEmpty().map { raw ->
            LinkedHashMap(objectOrError(raw, "$input: dependency")).also { entry ->
                entry["ref"] = replacements[entry["ref"]?.toString()] ?: entry["ref"]
                entry[BOM_DEPENDS_ON_FIELD] = (entry[BOM_DEPENDS_ON_FIELD] as? List<*>).orEmpty().map { ref ->
                    replacements[ref.toString()] ?: ref
                }
            }
        }

    private fun serialNumber(bom: Map<Any?, Any?>): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(JsonOutput.toJson(bom).toByteArray())
        val bytes = ByteBuffer.wrap(digest)
        return "urn:uuid:${UUID(bytes.long, bytes.long)}"
    }

    private fun write(bom: Map<Any?, Any?>) {
        output.get().asFile.apply { parentFile.mkdirs() }
            .writeText(JsonOutput.prettyPrint(JsonOutput.toJson(bom)) + "\n")
    }

    private fun <T> objectOrError(value: T?, description: String): Map<*, *> =
        value as? Map<*, *> ?: error("$description is not an object")
}

private const val BOM_REFERENCE_FIELD = "bom-ref"
private const val BOM_DEPENDS_ON_FIELD = "dependsOn"
private const val BOM_LICENSES_FIELD = "licenses"
