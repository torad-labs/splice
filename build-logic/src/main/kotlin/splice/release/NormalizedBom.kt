// Makes the CycloneDX report reproducible before it becomes the release's published BOM.
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

    // CycloneDX 3.4.1 records no license for jmdns 3.6.4, though its POM declares one in <licenses>: Apache License,
    // Version 2.0, the license its jar's manifest names too (Bundle-License).
    private val jmdns = "org.jmdns:jmdns:3.6.4"

    private val jmdnsLicenses = listOf(
        linkedMapOf(
            "license" to linkedMapOf("id" to "Apache-2.0", "url" to "https://www.apache.org/licenses/LICENSE-2.0.txt"),
        ),
    )

    @TaskAction
    fun normalize() {
        val input = source.get().asFile
        val bomJson = objectOrError(JsonSlurper().parse(input), "$input: root")
        val stableBom = LinkedHashMap(bomJson)
        stableBom["metadata"] = normalizedMetadata(bomJson, input)
        val refReplacements = LinkedHashMap<String, String>()
        stableBom["components"] = normalizedComponents(bomJson, input, refReplacements)
        stableBom["dependencies"] = normalizedDependencies(bomJson, input, refReplacements)
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
        return (bom["components"] as? List<*>).orEmpty().map { raw ->
            LinkedHashMap(objectOrError(raw, "$input: component")).also { entry ->
                normalizeComponentIdentity(entry, group, version, replacements)
                addDeclaredLicenses(entry)
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
        listOf("bom-ref", "purl").forEach { key ->
            val old = entry[key]?.toString() ?: return@forEach
            val updated = old.replace("@unspecified", "@$version")
            entry[key] = updated
            if (key == "bom-ref") replacements[old] = updated
        }
    }

    /** The licenses a component declares where CycloneDX recorded none: the GraalJS isolates' and jmdns's. */
    private fun addDeclaredLicenses(entry: MutableMap<Any?, Any?>) {
        val coordinate = "${entry["group"]}:${entry["name"]}:${entry["version"]}"
        if (!(entry["licenses"] as? List<*>).isNullOrEmpty()) return
        if (coordinate in graalJsCommunityIsolates) entry["licenses"] = graalJsCommunityIsolateLicenses
        if (coordinate == jmdns) entry["licenses"] = jmdnsLicenses
    }

    private fun normalizedDependencies(
        bom: Map<*, *>,
        input: File,
        replacements: Map<String, String>,
    ): List<LinkedHashMap<Any?, Any?>> =
        (bom["dependencies"] as? List<*>).orEmpty().map { raw ->
            LinkedHashMap(objectOrError(raw, "$input: dependency")).also { entry ->
                entry["ref"] = replacements[entry["ref"]?.toString()] ?: entry["ref"]
                entry["dependsOn"] = (entry["dependsOn"] as? List<*>).orEmpty().map { ref ->
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
