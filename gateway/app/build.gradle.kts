import com.github.jk1.license.render.JsonReportRenderer
import com.github.jk1.license.render.ReportRenderer
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.cyclonedx.model.Component
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
    application
    id("com.gradleup.shadow") version "9.6.1"
    id("org.cyclonedx.bom") version "3.3.0"
    id("com.github.jk1.dependency-license-report") version "3.1.4"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":provider-spi"))
    implementation(project(":dialect-openai-responses"))
    implementation(project(":dialect-openai-chat"))
    implementation(project(":provider-codex"))
    implementation(project(":provider-grok"))
    implementation(project(":provider-kimi"))
    implementation(project(":provider-openai"))
    implementation(project(":gateway"))
    implementation(project(":control"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktoml.core)
    implementation(libs.ktor.client.java)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.mock)
    testImplementation(testFixtures(project(":gateway")))
}

application {
    applicationName = "splice"
    mainClass.set("splice.app.MainKt")
}

val repositoryRoot = rootProject.layout.projectDirectory.dir("..")
val rawBomDir = layout.buildDirectory.dir("reports/cyclonedx")
val rawLicenseDir = layout.buildDirectory.dir("reports/licenses")
val complianceDir = layout.buildDirectory.dir("reports/compliance")
val rawBom = rawBomDir.map { it.file("bom.cdx.json") }
val rawLicenses = rawLicenseDir.map { it.file("dependency-licenses.json") }
val bom = complianceDir.map { it.file("bom.cdx.json") }
val licenses = complianceDir.map { it.file("dependency-licenses.json") }
val thirdPartyLicenses = complianceDir.map { it.file("THIRD_PARTY_LICENSES.txt") }
val thirdPartyNotices = repositoryRoot.file("THIRD_PARTY_NOTICES.md")
val dashboard = repositoryRoot.file("webui/dist/index.html")
val allowedReleaseLicenses = setOf(
    "Apache License, Version 2.0",
    "Apache Software License - Version 2.0",
    "Apache-2.0",
    "Eclipse Public License - Version 1.0",
    "MIT",
    "MIT License",
    "The Apache Software License, Version 2.0",
)

tasks.cyclonedxDirectBom {
    includeConfigs = listOf("runtimeClasspath")
    projectType = Component.Type.APPLICATION
    componentName = "splice"
    componentVersion = project.version.toString()
    includeBomSerialNumber = false
    includeBuildEnvironment = false
    includeBuildSystem = false
    jsonOutput.set(rawBom)
    xmlOutput.unsetConvention()
}

licenseReport {
    outputDir = rawLicenseDir.get().asFile.absolutePath
    projects = arrayOf(project)
    configurations = arrayOf("runtimeClasspath")
    renderers = arrayOf<ReportRenderer>(JsonReportRenderer("dependency-licenses.json", false))
}

val normalizeReleaseBom = tasks.register("normalizeReleaseBom") {
    dependsOn(tasks.cyclonedxDirectBom)
    inputs.file(rawBom)
    outputs.file(bom)
    doLast {
        val bomJson = JsonSlurper().parse(rawBom.get().asFile) as Map<*, *>
        val stableBom = LinkedHashMap(bomJson)
        val metadata = LinkedHashMap(bomJson["metadata"] as Map<*, *>)
        metadata.remove("timestamp")
        val component = LinkedHashMap(metadata["component"] as Map<*, *>)
        component.remove("externalReferences")
        metadata["component"] = component
        stableBom["metadata"] = metadata

        // CycloneDX currently reports Gradle project dependencies as "unspecified" even though
        // every project has the release version. Normalize both component identities and the
        // dependency graph refs so the published SBOM is internally consistent and usable.
        val refReplacements = LinkedHashMap<String, String>()
        val components = (bomJson["components"] as? List<*>).orEmpty().map { raw ->
            val entry = LinkedHashMap(raw as Map<*, *>)
            if (entry["group"] == rootProject.name && entry["version"] == "unspecified") {
                entry["version"] = project.version.toString()
                listOf("bom-ref", "purl").forEach { key ->
                    val old = entry[key]?.toString() ?: return@forEach
                    val updated = old.replace("@unspecified", "@${project.version}")
                    entry[key] = updated
                    if (key == "bom-ref") refReplacements[old] = updated
                }
            }
            entry
        }
        stableBom["components"] = components
        stableBom["dependencies"] = (bomJson["dependencies"] as? List<*>).orEmpty().map { raw ->
            val entry = LinkedHashMap(raw as Map<*, *>)
            entry["ref"] = refReplacements[entry["ref"]?.toString()] ?: entry["ref"]
            entry["dependsOn"] = (entry["dependsOn"] as? List<*>).orEmpty().map { ref ->
                refReplacements[ref.toString()] ?: ref
            }
            entry
        }

        // actions/attest's CycloneDX detection requires serialNumber (bomFormat + specVersion
        // alone are rejected at publish time), but a RANDOM serial would break the byte-identical
        // rebuild verification this task exists for. Derive it from the normalized content:
        // same inputs → same BOM → same serial, unique across genuinely different BOMs.
        val canonical = JsonOutput.toJson(stableBom)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        val serialHex = digest.take(16).joinToString("") { byte -> "%02x".format(byte) }
        val serial = listOf(
            serialHex.substring(0, 8),
            serialHex.substring(8, 12),
            serialHex.substring(12, 16),
            serialHex.substring(16, 20),
            serialHex.substring(20, 32),
        ).joinToString("-")
        stableBom["serialNumber"] = "urn:uuid:$serial"

        val output = bom.get().asFile
        output.parentFile.mkdirs()
        output.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(stableBom)) + "\n")
    }
}

val copyReleaseLicenses = tasks.register("copyReleaseLicenses") {
    dependsOn(tasks.named("generateLicenseReport"))
    inputs.file(rawLicenses)
    outputs.file(licenses)
    doLast {
        val output = licenses.get().asFile
        output.parentFile.mkdirs()
        Files.copy(rawLicenses.get().asFile.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

val generateThirdPartyLicenses = tasks.register("generateThirdPartyLicenses") {
    outputs.file(thirdPartyLicenses)
    doLast {
        val sections = linkedMapOf(
            "Apache-2.0" to "Apache License 2.0",
            "MIT" to "MIT License",
            "EPL-1.0" to "Eclipse Public License 1.0",
            "OFL-1.1" to "SIL Open Font License 1.1",
        )
        val text = buildString {
            appendLine("Third-party license texts bundled with splice")
            appendLine()
            appendLine("Generated from the SPDX license-text resources in cyclonedx-core-java.")
            sections.forEach { (spdxId, label) ->
                val resource = "/licenses/$spdxId.txt"
                val licenseText = Component::class.java.getResourceAsStream(resource)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: error("CycloneDX SPDX resource missing: $resource")
                appendLine()
                appendLine("================================================================================")
                appendLine("$label ($spdxId)")
                appendLine("================================================================================")
                appendLine()
                append(licenseText.trimEnd())
                appendLine()
            }
        }
        val output = thirdPartyLicenses.get().asFile
        output.parentFile.mkdirs()
        output.writeText(text)
    }
}

val verifyReleaseCompliance = tasks.register("verifyReleaseCompliance") {
    dependsOn(normalizeReleaseBom, copyReleaseLicenses, generateThirdPartyLicenses)
    inputs.files(bom, licenses, thirdPartyLicenses, thirdPartyNotices, dashboard)
    doLast {
        val bomJson = JsonSlurper().parse(bom.get().asFile) as Map<*, *>
        val metadata = bomJson["metadata"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val rootComponent = metadata["component"] as? Map<*, *> ?: emptyMap<Any, Any>()
        check(rootComponent["name"] == "splice") { "release SBOM root component is not splice" }
        check(rootComponent["version"] == project.version.toString()) {
            "release SBOM version ${rootComponent["version"]} does not match ${project.version}"
        }
        val components = bomJson["components"] as? List<*> ?: emptyList<Any>()
        check(components.isNotEmpty()) { "release SBOM has no runtime components" }
        val unversionedFirstParty = components.filter { component ->
            val entry = component as? Map<*, *> ?: return@filter false
            entry["group"] == rootProject.name && entry["version"] == "unspecified"
        }
        check(unversionedFirstParty.isEmpty()) { "release SBOM has unversioned first-party components" }

        val licenseJson = JsonSlurper().parse(licenses.get().asFile) as Map<*, *>
        val dependencies = licenseJson["dependencies"] as? List<*> ?: emptyList<Any>()
        check(dependencies.isNotEmpty()) { "dependency-license inventory is empty" }
        val unresolved = dependencies.filter { dependency ->
            val entry = dependency as? Map<*, *> ?: return@filter true
            val declared = entry["moduleLicenses"] as? List<*> ?: emptyList<Any>()
            declared.isEmpty() || declared.any { license ->
                val name = (license as? Map<*, *>)?.get("moduleLicense")?.toString()?.trim().orEmpty()
                name.isEmpty() || name.equals("unknown", ignoreCase = true)
            }
        }
        check(unresolved.isEmpty()) { "dependencies with unresolved licenses: $unresolved" }
        val disallowed = dependencies.mapNotNull { dependency ->
            val entry = dependency as? Map<*, *> ?: return@mapNotNull dependency.toString()
            val declared = (entry["moduleLicenses"] as? List<*>).orEmpty().mapNotNull { license ->
                (license as? Map<*, *>)?.get("moduleLicense")?.toString()?.trim()
            }
            val rejected = declared.filterNot(allowedReleaseLicenses::contains)
            if (rejected.isEmpty()) {
                null
            } else {
                "${entry["moduleName"]}:${entry["moduleVersion"]} ($rejected)"
            }
        }
        check(disallowed.isEmpty()) {
            "runtime dependencies use licenses outside the release allowlist: $disallowed"
        }

        val licensedCoordinates = dependencies.map { dependency ->
            val entry = dependency as Map<*, *>
            "${entry["moduleName"]}:${entry["moduleVersion"]}"
        }.toSet()
        val runtimeCoordinates = configurations.runtimeClasspath.get().incoming.resolutionResult.allComponents
            .mapNotNull { component -> component.moduleVersion?.let { "${it.group}:${it.name}:${it.version}" } }
            .filterNot { it.startsWith("${rootProject.name}:") }
            .toSet()
        val missingLicenses = runtimeCoordinates - licensedCoordinates
        check(missingLicenses.isEmpty()) { "runtime dependencies missing from license inventory: $missingLicenses" }

        val licenseTexts = thirdPartyLicenses.get().asFile.readText()
        listOf(
            "Apache License\nVersion 2.0",
            "MIT License",
            "Eclipse Public License - v 1.0",
            "SIL OPEN FONT LICENSE",
            "Version 1.1 - 26 February 2007",
        ).forEach { marker -> check(marker in licenseTexts) { "third-party license bundle missing $marker" } }
        val notices = thirdPartyNotices.asFile.readText()
        listOf(
            "Copyright (c) Meta Platforms, Inc. and affiliates.",
            "Copyright (c) 2019 Paul Henschel",
        ).forEach { marker -> check(marker in notices) { "third-party notices missing $marker" } }
        check(dashboard.asFile.length() > 100_000L) { "committed dashboard bundle is missing or unexpectedly small" }
    }
}

tasks.withType<ShadowJar>().configureEach {
    archiveFileName.set("app-all.jar")
    dependsOn(verifyReleaseCompliance)
    from(repositoryRoot.file("LICENSE")) { into("META-INF"); rename { "LICENSE" } }
    from(thirdPartyNotices) { into("META-INF") }
    from(thirdPartyLicenses) { into("META-INF") }
    from(repositoryRoot.file("PROVENANCE.md")) { into("META-INF") }
    from(bom) { into("META-INF") }
    from(licenses) { into("META-INF") }
    from(dashboard) { into("webui") }
}
