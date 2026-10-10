import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.jengelman.gradle.plugins.shadow.transformers.IncludeResourceTransformer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.cyclonedx.model.Component
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.api.tasks.PathSensitivity
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.zip.ZipFile

// TRANSITIVE CVE FLOOR — Jackson (2026-10-05). cyclonedx-core-java 13.1.0 brings
// core/databind 2.22.1 onto this plugin classpath. GHSA-7hhh-6rmp-j9qf and
// GHSA-p6pp-m3f8-5c89 are fixed in 2.22.3; subproject dependencies cannot floor this classpath.
buildscript {
    dependencies {
        constraints {
            classpath("com.fasterxml.jackson.core:jackson-core:${libs.versions.jackson.get()}")
            classpath("com.fasterxml.jackson.core:jackson-databind:${libs.versions.jackson.get()}")
        }
    }
}

plugins {
    id("splice.kotlin-common")
    id("splice.law-suite")
    id("splice.module-law")
    application
    id("com.gradleup.shadow") version "9.6.1"
    id("org.cyclonedx.bom") version "3.4.1"
}

// :integrations-codemode's compiled runtime suite, rerun by codeModePackagedTest against the fat jar.
val codeModeRuntimeTests: Configuration = configurations.create("codeModeRuntimeTests") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation(project(":core"))
    implementation(project(":integrations-claude-code"))
    implementation(project(":integrations-mcp"))
    implementation(project(":integrations-topology"))
    implementation(project(":integrations-codemode"))
    implementation(project(":integrations-oauth"))
    implementation(project(":integrations-terminal"))
    implementation(project(":integrations-daemon-client"))
    implementation(project(":integrations-upstream"))
    implementation(project(":integrations-dialects-openai-responses"))
    implementation(project(":integrations-dialects-openai-chat"))
    implementation(project(":integrations-dialects-anthropic"))
    implementation(project(":integrations-providers-codex"))
    implementation(project(":integrations-providers-grok"))
    implementation(project(":integrations-providers-kimi"))
    implementation(project(":integrations-providers-muse"))
    implementation(project(":integrations-providers-openai"))
    implementation(project(":features-turns"))
    implementation(project(":features-sessions"))
    implementation(project(":features-usage"))
    implementation(project(":features-models"))
    implementation(project(":features-accounts"))
    implementation(project(":features-heads"))
    implementation(project(":features-lifecycle"))
    implementation(project(":features-diagnostics"))
    implementation(project(":features-launch"))
    implementation(project(":features-configuration"))
    implementation(project(":features-events"))
    implementation(project(":integrations-http"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jackson.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.java)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    // Ktor logs through slf4j-api. With no provider on the classpath SLF4J prints three warning lines to
    // stderr on first use, which is a user's terminal during every `splice add` sign-in. splice reports
    // its own failures through its own log (RouteFailure), so the provider is the no-op one.
    runtimeOnly(libs.slf4j.nop)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.server.test.host) {
        exclude(group = "io.ktor", module = "ktor-client-apache5")
    }
    testImplementation(testFixtures(project(":features-turns")))
    // TestPorts: a port a test must know before anything binds it, reserved below the ephemeral range.
    testImplementation(testFixtures(project(":core")))
    testImplementation(testFixtures(project(":integrations-codemode")))
    testImplementation(testFixtures(project(":features-lifecycle")))
    testImplementation(testFixtures(project(":integrations-mcp")))
    testImplementation(testFixtures(project(":integrations-oauth")))
    testImplementation(testFixtures(project(":integrations-dialects-openai-responses")))
    testImplementation(testFixtures(project(":integrations-dialects-anthropic")))
    codeModeRuntimeTests(project(path = ":integrations-codemode", configuration = "packagedRuntimeTests"))
}

application {
    applicationName = "splice"
    mainClass.set("splice.app.MainKt")
}

// The example topology is a main RESOURCE (src/main/resources/splice.example.toml), so it is on
// :app:test's classpath and an input to the task by itself: touch it, the tests that read it re-run.
// (Before restructure PR 6 it lived at config/ and the tests walked up to it, which Gradle could
// not see — caught 2026-07-26 when editing only the example left :app:test UP-TO-DATE.)
tasks.test {
    systemProperty(
        "junit.jupiter.tempdir.deletion.strategy.default",
        "splice.head.HeadFileWriteCleanup",
    )
    jvmArgumentProviders.add(
        splice.testing.MachineLocalProperties(
            provider {
                mapOf("codeMode.testClasspath" to sourceSets.test.get().runtimeClasspath.asPath)
            },
        ),
    )

    // The arms that enter at a production call site (DR-97 login(), DR-99 runCli()) redirect
    // `user.home` to a @TempDir, but TopologyLoader.configPath() consults SPLICE_CONFIG and
    // XDG_CONFIG_HOME BEFORE user.home — so an ambient value in the runner's environment aims
    // the verb at the operator's own config and the arm fails on its own PREMISE assertion.
    // That is not hypothetical: green on a machine with neither set, red on CI with
    // XDG_CONFIG_HOME set, reproduced locally by exporting it (expected /tmp/junit-.../.config,
    // was ~/.config). Removed here rather than asserted around, because the hermetic JVM covers
    // the whole CLASS - every future arm that redirects user.home - not just the two that
    // happen to assert the premise today.
    environment = environment.filterKeys { it != "XDG_CONFIG_HOME" && it != "SPLICE_CONFIG" }

    // THE APP TESTS RUN IN FOUR JVMs AT ONCE, each with a home of its own. They write config and Graal worker files under
    // their home, so forks sharing one would race on those files. The wrapper java gives every JVM
    // a fresh `fork-<pid>` home under build/fork-homes, and the doFirst below clears the previous run's.
    maxParallelForks = 4
    val forkHomes = layout.buildDirectory.dir("fork-homes").get().asFile
    val forkJava = rootProject.layout.projectDirectory.file("gradle/fork-home/bin/java").asFile
    executable = forkJava.absolutePath
    doFirst {
        forkHomes.deleteRecursively()
        forkHomes.mkdirs()
        environment("SPLICE_FORK_HOME_BASE", forkHomes.absolutePath)
    }
}

// THE PUBLIC-SOURCE LAW reads every tree a public repository ships, by name and as text (PublicSourceNamesNoHostToolTest.kt).
// The build computes the files in those roots once, by git's own rule (tracked, or untracked and not ignored), fingerprints
// exactly that list, and hands it to the test, which scans nothing else.
splice.lawsuite.ReadSet.declare(
    project,
    tasks.named<Test>("lawTest"),
    listOf(
        "app", "core", "features", "integrations", "quality/architecture", "quality/compiler-plugin", "build-logic",
        "quality/detekt", "gradle", "settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradlew",
        "gradlew.bat", "install.sh", ".gitignore",
    ),
)

val releaseVersion = project.version.toString()
val releaseGroup = rootProject.name
val repositoryRoot = rootProject.layout.projectDirectory
val rawBomDir = layout.buildDirectory.dir("reports/cyclonedx")
val complianceDir = layout.buildDirectory.dir("reports/compliance")
val rawBom = rawBomDir.map { it.file("bom.cdx.json") }
val bom = complianceDir.map { it.file("bom.cdx.json") }
val licenses = complianceDir.map { it.file("dependency-licenses.json") }
val thirdPartyLicenses = complianceDir.map { it.file("THIRD_PARTY_LICENSES.txt") }
val thirdPartyNoticesSource = repositoryRoot.file("THIRD_PARTY_NOTICES.md")
val thirdPartyNotices = complianceDir.map { it.file("THIRD_PARTY_NOTICES.md") }
// Preserve each bundled dependency's own texts, including notices and shaded-library licenses.
val runtimeLegalArtifacts = configurations.runtimeClasspath.get().incoming.artifactView {
    componentFilter { it is ModuleComponentIdentifier }
}.artifacts
val legalResourceName = Regex("(?i)(?:.*[-_])?(LICENSE|NOTICE)(?:\\.(txt|md))?")
val runtimeLegalTexts = run {
    val artifacts = runtimeLegalArtifacts
    val legalName = legalResourceName
    providers.provider {
        artifacts.artifacts.filter { it.file.extension == "jar" }
            .sortedBy { it.id.displayName }.flatMap { artifact ->
                ZipFile(artifact.file).use { archive ->
                    archive.entries().asSequence().filter { entry ->
                        !entry.isDirectory && legalName.matches(entry.name.substringAfterLast('/'))
                    }.sortedBy { it.name }.map { entry ->
                        Triple(
                            artifact.id.componentIdentifier.displayName,
                            entry.name,
                            archive.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() },
                        )
                    }.toList()
                }
            }
    }
}
val icuLicense = repositoryRoot.file("tools/release/licenses/icu-LICENSE.txt")
val licenseFile = repositoryRoot.file("LICENSE")
// PR 6: PROVENANCE.md lives under docs/ — the repository root keeps only the files GitHub itself
// reads. Both consumers (the jar's META-INF copy below and stageRelease) read it from HERE.
val provenance = repositoryRoot.file("docs/PROVENANCE.md")
// PR 6: the launch shim ships from the application's dist layout, not from bin/ — one path, read by
// stageRelease here and by `bun tools/release accept`/`verify` through tools/release/src/lib/shim.ts.
val launchShim = layout.projectDirectory.file("src/main/dist/bin/splice-launch")
val installScript = repositoryRoot.file("install.sh")
val packageJson = repositoryRoot.file("package.json")
val bunLock = repositoryRoot.file("bun.lock")
val distDir = repositoryRoot.dir("dist")
// The set was written 2026-07-20 when every dependency was Apache-2.0/MIT/EPL; BSD was never
// considered rather than rejected. BSD 2-Clause is strictly MORE permissive than Apache-2.0, which
// is already allowed — no patent clause, no NOTICE obligation, no copyleft, OSI-approved — and the
// attribution it does require is already emitted by generateThirdPartyLicenses. Added 2026-08-11
// for com.github.luben:zstd-jni, the canonical JVM zstd binding (Kafka/Spark/Netty use it); the
// alternatives cannot compress (aircompressor is decompress-only) or shell out to it anyway.
val allowedReleaseLicenses = setOf(
    "Apache License, Version 2.0",
    "BSD 2-Clause License",
    "BSD-2-Clause",
    "Apache Software License - Version 2.0",
    "Apache-2.0",
    "Eclipse Public License - Version 1.0",
    "EPL-1.0",
    "MIT",
    "MIT License",
    "The Apache Software License, Version 2.0",
    // Bundled GraalJS community libraries: permissive copyright/patent grant with notice retention.
    "Universal Permissive License, Version 1.0",
    "UPL-1.0",
    // ICU's permissive grant requires the included copyright and third-party notices.
    "Unicode/ICU License",
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

val normalizeReleaseBom = tasks.register("normalizeReleaseBom") {
    val rawBom = rawBom
    val bom = bom
    val releaseGroup = releaseGroup
    val releaseVersion = releaseVersion
    dependsOn(tasks.cyclonedxDirectBom)
    inputs.file(rawBom).withPathSensitivity(PathSensitivity.NONE)
    outputs.file(bom)
    outputs.cacheIf("a pure function of the raw BOM") { true }
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
            if (entry["group"] == releaseGroup && entry["version"] == "unspecified") {
                entry["version"] = releaseVersion
                listOf("bom-ref", "purl").forEach { key ->
                    val old = entry[key]?.toString() ?: return@forEach
                    val updated = old.replace("@unspecified", "@$releaseVersion")
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

val normalizedBom = bom
val copyReleaseLicenses = tasks.register<splice.release.LicenseInventory>("copyReleaseLicenses") {
    dependsOn(normalizeReleaseBom)
    bom.set(normalizedBom)
    inventory.set(licenses)
    firstPartyGroup.set(releaseGroup)
}

val generateThirdPartyNotices = tasks.register("generateThirdPartyNotices") {
    val noticesSource = thirdPartyNoticesSource
    val notices = thirdPartyNotices
    val legalTexts = runtimeLegalTexts
    inputs.file(thirdPartyNoticesSource).withPathSensitivity(PathSensitivity.NONE)
    inputs.files(runtimeLegalArtifacts.artifactFiles).withNormalizer(ClasspathNormalizer::class.java)
    outputs.file(thirdPartyNotices)
    outputs.cacheIf("a pure function of the notices source and the runtime jars") { true }
    doLast {
        val output = notices.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            buildString {
                append(noticesSource.asFile.readText())
                legalTexts.get().filter { (_, name, _) -> "NOTICE" in name.uppercase() }
                    .forEach { (coordinate, name, text) ->
                        appendLine()
                        appendLine("## $coordinate / $name")
                        appendLine()
                        append(text)
                        appendLine()
                    }
            },
        )
    }
}

val generateThirdPartyLicenses = tasks.register("generateThirdPartyLicenses") {
    val icu = icuLicense
    val bundle = thirdPartyLicenses
    val legalTexts = runtimeLegalTexts
    inputs.file(icuLicense).withPathSensitivity(PathSensitivity.NONE)
    inputs.property("cyclonedxLicenseTexts", "3.4.1")
    inputs.files(runtimeLegalArtifacts.artifactFiles).withNormalizer(ClasspathNormalizer::class.java)
    outputs.file(thirdPartyLicenses)
    outputs.cacheIf("a pure function of the license texts and the runtime jars") { true }
    doLast {
        val sections = linkedMapOf(
            "Apache-2.0" to "Apache License 2.0",
            "MIT" to "MIT License",
            "EPL-1.0" to "Eclipse Public License 1.0",
            "UPL-1.0" to "Universal Permissive License 1.0",
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
            appendLine()
            appendLine("================================================================================")
            appendLine("ICU license and bundled third-party notices")
            appendLine("================================================================================")
            appendLine(icu.asFile.readText().trimEnd())
            legalTexts.get().filter { (_, name, _) -> "LICENSE" in name.uppercase() }
                .forEach { (coordinate, name, text) ->
                    appendLine()
                    appendLine("================================================================================")
                    appendLine("$coordinate / $name")
                    appendLine("================================================================================")
                    appendLine()
                    append(text)
                    appendLine()
                }
        }
        val output = bundle.get().asFile
        output.parentFile.mkdirs()
        output.writeText(text)
    }
}

val verifyReleaseCompliance = tasks.register("verifyReleaseCompliance") {
    val bom = bom
    val licenses = licenses
    val thirdPartyLicenses = thirdPartyLicenses
    val thirdPartyNotices = thirdPartyNotices
    val legalTexts = runtimeLegalTexts
    val releaseVersion = releaseVersion
    val releaseGroup = releaseGroup
    val allowedReleaseLicenses = allowedReleaseLicenses
    val runtimeCoordinates = configurations.runtimeClasspath.get().incoming.resolutionResult.rootComponent.map { root ->
        val seen = LinkedHashSet<ResolvedComponentResult>()
        val pending = ArrayDeque(listOf(root))
        while (pending.isNotEmpty()) {
            val component = pending.removeFirst()
            if (!seen.add(component)) continue
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { pending.addLast(it.selected) }
        }
        seen.mapNotNull { component -> component.moduleVersion?.let { "${it.group}:${it.name}:${it.version}" } }
            .filterNot { it.startsWith("$releaseGroup:") }
            .toSet()
    }
    dependsOn(normalizeReleaseBom, copyReleaseLicenses, generateThirdPartyLicenses, generateThirdPartyNotices)
    inputs.files(bom, licenses, thirdPartyLicenses, thirdPartyNotices)
    inputs.property("runtimeCoordinates", runtimeCoordinates)
    doLast {
        val bomJson = JsonSlurper().parse(bom.get().asFile) as Map<*, *>
        val metadata = bomJson["metadata"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val rootComponent = metadata["component"] as? Map<*, *> ?: emptyMap<Any, Any>()
        check(rootComponent["name"] == "splice") { "release SBOM root component is not splice" }
        check(rootComponent["version"] == releaseVersion) {
            "release SBOM version ${rootComponent["version"]} does not match $releaseVersion"
        }
        val components = bomJson["components"] as? List<*> ?: emptyList<Any>()
        check(components.isNotEmpty()) { "release SBOM has no runtime components" }
        val unversionedFirstParty = components.filter { component ->
            val entry = component as? Map<*, *> ?: return@filter false
            entry["group"] == releaseGroup && entry["version"] == "unspecified"
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
        val missingLicenses = runtimeCoordinates.get() - licensedCoordinates
        check(missingLicenses.isEmpty()) { "runtime dependencies missing from license inventory: $missingLicenses" }

        val licenseTexts = thirdPartyLicenses.get().asFile.readText()
        listOf(
            "Apache License\nVersion 2.0",
            "MIT License",
            "Eclipse Public License - v 1.0",
            "Universal Permissive License",
            "UNICODE LICENSE V3",
            "ICU License - ICU 1.8.1 to ICU 57.1",
            "Chinese/Japanese Word Break Dictionary Data",
        ).forEach { marker -> check(marker in licenseTexts) { "third-party license bundle missing $marker" } }
        val notices = thirdPartyNotices.get().asFile.readText()
        legalTexts.get().forEach { (coordinate, name, text) ->
            val bundle = if ("NOTICE" in name.uppercase()) notices else licenseTexts
            check(text.isNotBlank() && text in bundle) { "release legal bundle missing $coordinate / $name" }
        }
    }
}

// ── STAGING THE RELEASE BUNDLE (checks/release/stage.sh until PR 6) ──────────────────────────────
//
// DR-25: ONE asset list, and it is `releaseAssets` below. The task stages exactly these names and
// writes dist/sha256sums.txt over them IN THIS ORDER; `bun tools/release accept` reads the asset set
// back out of that manifest, and `bun tools/release verify` checks release.yml's `files:` list
// against the same staged manifest. Nothing carries a second hand copy — three hand-authored lists
// cross-checking each other is the shape DR-25 was opened against, and a list that checks itself
// cannot fail for what it omits (§24).
//
// Every rule and every message of stage.sh is kept, in its order: the lockfile agreement, the
// SemVer + tag/version equality gate (a real pushed tag wins over SPLICE_RELEASE_TAG through
// GITHUB_REF_TYPE — DR-19, so the promotion path is gated too), the fat jar's own `version` output,
// and the compliance reports' presence. The jar and the reports are declared INPUTS rather than
// probed by path, so `:app:stageRelease` builds what it stages; the "expected fat jar missing"
// refusal stays as the floor for a dist staged against a deleted artifact.
val releaseAssets = listOf(
    "splice.jar", "splice-launch", "install.sh",
    "LICENSE", "THIRD_PARTY_NOTICES.md", "THIRD_PARTY_LICENSES.txt", "PROVENANCE.md",
    "bom.cdx.json", "dependency-licenses.json",
)

/** SemVer with no build metadata, the exact grammar stage.sh's `[[ =~ ]]` spelled. */
val releaseTagPattern =
    Regex(
        "^v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
            "(-((0|[1-9][0-9]*)|([0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))" +
            "(\\.((0|[1-9][0-9]*)|([0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)))*)?$",
    )

val releaseJar = tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile }
// A real pushed tag is the authority; SPLICE_RELEASE_TAG is how the promotion path threads the
// resolved version in (release.yml), and an empty value means "no tag gate", as in the script.
val releaseTag =
    providers.environmentVariable("GITHUB_REF_TYPE").orElse("").zip(
        providers.environmentVariable("GITHUB_REF_NAME").orElse(""),
    ) { refType, refName -> if (refType == "tag") refName else "" }
        .zip(providers.environmentVariable("SPLICE_RELEASE_TAG").orElse("")) { fromRef, fromEnv ->
            fromRef.ifEmpty { fromEnv }
        }
val stagingLauncher = javaToolchains.launcherFor(java.toolchain)

tasks.register("stageRelease") {
    val releaseTagPattern = releaseTagPattern
    val releaseJar = releaseJar
    val bom = bom
    val licenses = licenses
    val distDir = distDir
    val launchShim = launchShim
    val installScript = installScript
    val licenseFile = licenseFile
    val thirdPartyNotices = thirdPartyNotices
    val thirdPartyLicenses = thirdPartyLicenses
    val provenance = provenance
    val releaseAssets = releaseAssets
    group = "release"
    description =
        "Stages dist/: the published asset set and sha256sums.txt over it (checks/release/stage.sh until PR 6)."
    inputs.file(releaseJar).withPropertyName("fatJar")
    inputs.files(bom, licenses, thirdPartyLicenses).withPropertyName("complianceReports")
    inputs.files(licenseFile, thirdPartyNotices, provenance, launchShim, installScript)
        .withPropertyName("publishedRepositoryFiles")
    inputs.files(packageJson, bunLock).withPropertyName("versionAndLockfile")
    inputs.property("releaseTag", releaseTag)
    outputs.dir(distDir)
    val version = releaseVersion
    val launcher = stagingLauncher
    val tagProvider = releaseTag
    val repositoryDir = repositoryRoot.asFile
    doLast {
        // bun.lock records no root version, so the package-lock version cross-check has no successor;
        // what a release needs is a lockfile that agrees with package.json, and --frozen-lockfile
        // refuses any drift.
        // stdout to /dev/null, stderr THROUGH: the script kept bun's own explanation of the drift
        // on the terminal, and the refusal below only says that there was some.
        val install = ProcessBuilder("bun", "install", "--frozen-lockfile")
            .directory(repositoryDir)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        check(install.waitFor() == 0) {
            "release stage: bun.lock does not agree with package.json (bun install --frozen-lockfile refused)"
        }

        val tag = tagProvider.get()
        if (tag.isNotEmpty()) {
            check(releaseTagPattern.matches(tag)) {
                "release stage: tag must be valid SemVer without build metadata, got $tag"
            }
            check(tag == "v$version") {
                "release stage: tag $tag does not match package version $version"
            }
        }

        val jar = releaseJar.get().asFile
        check(jar.isFile) { "release stage: expected fat jar missing at $jar" }
        listOf(bom, licenses).forEach { report ->
            val file = report.get().asFile
            check(file.isFile) { "release stage: compliance report missing at $file" }
        }
        val javaBin = launcher.get().executablePath.asFile.absolutePath
        val versionProcess = ProcessBuilder(javaBin, "-jar", jar.absolutePath, "version")
            .redirectErrorStream(false)
            .start()
        val jarVersion = versionProcess.inputStream.bufferedReader().use { it.readText() }.trim()
        versionProcess.waitFor()
        check(jarVersion == "splice $version") {
            "release stage: package version $version does not match '$jarVersion'"
        }

        val dist = distDir.asFile
        dist.deleteRecursively()
        dist.mkdirs()
        val sources = mapOf(
            "splice.jar" to jar,
            "splice-launch" to launchShim.asFile,
            "install.sh" to installScript.asFile,
            "LICENSE" to licenseFile.asFile,
            "THIRD_PARTY_NOTICES.md" to thirdPartyNotices.get().asFile,
            "THIRD_PARTY_LICENSES.txt" to thirdPartyLicenses.get().asFile,
            "PROVENANCE.md" to provenance.asFile,
            "bom.cdx.json" to bom.get().asFile,
            "dependency-licenses.json" to licenses.get().asFile,
        )
        // `install -m 0755` for the two the operator executes, `install -m 0644` for the rest —
        // set outright rather than inherited from the source or from this process's umask.
        val executable = setOf("splice-launch", "install.sh")
        check(sources.keys.toList() == releaseAssets) {
            "release stage: the staged sources do not spell the asset list — ${sources.keys} vs $releaseAssets"
        }
        val sums = releaseAssets.joinToString("") { asset ->
            val staged = dist.resolve(asset)
            Files.copy(sources.getValue(asset).toPath(), staged.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Files.setPosixFilePermissions(
                staged.toPath(),
                PosixFilePermissions.fromString(if (asset in executable) "rwxr-xr-x" else "rw-r--r--"),
            )
            val digest = MessageDigest.getInstance("SHA-256").digest(staged.readBytes())
                .joinToString("") { byte -> "%02x".format(byte) }
            "$digest  $asset\n"
        }
        dist.resolve("sha256sums.txt").writeText(sums)
        // QUIET, not LIFECYCLE: the rehearsal and the release workflow both run gradle with `-q`,
        // and stage.sh's closing line printed there too.
        logger.quiet("release stage: $dist")
    }
}

// A classpath test cannot catch lost service registrations in the shipped fat JAR: the Truffle
// languages, and the SLF4J provider whose absence prints warnings on a user's terminal.
val codeModePackagedTest = tasks.register<Test>("codeModePackagedTest") {
    dependsOn(tasks.named("shadowJar"))
    testClassesDirs = sourceSets.test.get().output.classesDirs + codeModeRuntimeTests
    classpath = sourceSets.test.get().runtimeClasspath + codeModeRuntimeTests
    filter {
        includeTestsMatching("CodeModeLanguagesTest")
        includeTestsMatching("CodeModeRuntimeTest")
        includeTestsMatching("CodeModeStatementStreamingTest")
        includeTestsMatching("CodeModeStatementBoundaryTest")
        includeTestsMatching("CodeModeStatementCompilerTest")
        includeTestsMatching("CodeModeScopeSealTest")
        includeTestsMatching("CodeModeSharedHostTest")
        includeTestsMatching("CodeModeWorkerBootTest")
        includeTestsMatching("CodeModeBridgeRuntimeTest")
        includeTestsMatching("Slf4jProviderTest")
    }
    val packagedJar = tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile }
    inputs.file(packagedJar)
    doFirst {
        val artifact = packagedJar.get().asFile
        val digest = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }
        logger.lifecycle("Code-mode packaged worker SHA-256: $digest")
        systemProperty("codeMode.testClasspath", artifact.absolutePath)
    }
}
tasks.named("check") { dependsOn(codeModePackagedTest) }

tasks.withType<ShadowJar>().configureEach {
    // JS and regex are separate Truffle languages; both registrations must survive the single-JAR build.
    mergeServiceFiles()
    filesMatching(listOf("META-INF/services/**", "META-INF/*.kotlin_module")) {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    // Graal's community selectors are POM-only; keep their transitive runtime JARs, not ZIP inputs.
    dependencies {
        exclude(dependency("org.graalvm.js:js-community:.*"))
        exclude(dependency("org.graalvm.js:js:.*"))
        exclude(dependency("org.graalvm.polyglot:js-community:.*"))
    }
    archiveFileName.set("app-all.jar")
    val licenseBytes = licenseFile.asFile
    dependsOn(verifyReleaseCompliance)
    // Copy order cannot select a dependency's license: discard every input at this path,
    // then emit splice's authoritative bytes once, after Shadow has processed the inputs.
    exclude("META-INF/LICENSE")
    transform<IncludeResourceTransformer> {
        file.set(licenseFile)
        resource.set("META-INF/LICENSE")
    }
    // Dependency notices may share this path. The generated sidecar also retains each full text.
    filesMatching("META-INF/NOTICE") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    append("META-INF/NOTICE")
    doLast {
        ZipFile(archiveFile.get().asFile).use { archive ->
            val entries = archive.entries().asSequence().filter { it.name == "META-INF/LICENSE" }.toList()
            check(entries.size == 1) { "splice jar must carry exactly one META-INF/LICENSE" }
            val packaged = archive.getInputStream(entries.single()).use { it.readBytes() }
            check(packaged.contentEquals(licenseBytes.readBytes())) {
                "splice LICENSE differs from META-INF/LICENSE in the built jar"
            }
        }
    }
    from(thirdPartyNotices) { into("META-INF") }
    from(thirdPartyLicenses) { into("META-INF") }
    from(provenance) { into("META-INF") }
    from(bom) { into("META-INF") }
    from(licenses) { into("META-INF") }
}
