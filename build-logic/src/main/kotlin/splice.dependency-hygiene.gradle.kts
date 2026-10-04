// DEPENDENCY HYGIENE, applied at the ROOT project by splice.gate-ladder (restructure PR 6, §4.2):
//
//   catalogMetadataSync — gradle/libs.versions.toml never outruns gradle/verification-metadata.xml.
//                         checks/catalog-metadata-sync.ts and checks/catalog-metadata-selftest.sh
//                         until PR 6; the logic is splice.hygiene.CatalogMetadata and its red proofs
//                         are build-logic's own tests (CatalogMetadataTest), which the gate runs.
//
// The catalog is read through Gradle's VersionCatalogsExtension at configuration time — the build's
// own reading of the file, so no second TOML parser can disagree with it — and captured as plain
// data for the task's action. The Dependabot Kotlin-scope guard did NOT come here: it reads YAML,
// which the plugin classpath cannot parse without a new pinned dependency, so it runs in-process in
// `bun tools/gate`'s config guard beside the other config guards (tools/gate/src/lib/dependabot.ts).
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.VersionConstraint
import splice.hygiene.Catalog
import splice.hygiene.CatalogLibrary
import splice.hygiene.CatalogMetadata
import splice.hygiene.CatalogPlugin

/** A rich version (strictly / prefer / reject) carries no single literal to pin — refuse, never skip. */
fun VersionConstraint.literal(where: String): String? {
    val rich = strictVersion.isNotEmpty() || preferredVersion.isNotEmpty() || rejectedVersions.isNotEmpty()
    check(!rich) { "$where: rich version $this — extend splice.hygiene.CatalogMetadata, do not skip" }
    return requiredVersion.ifEmpty { null }
}

fun readCatalog(): Catalog {
    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    val versions = libs.versionAliases.sorted().associateWith { alias ->
        checkNotNull(libs.findVersion(alias).get().literal("[versions] $alias")) { "[versions] $alias: no version" }
    }
    val libraries = libs.libraryAliases.sorted().map { alias ->
        val dependency = libs.findLibrary(alias).get().get()
        CatalogLibrary(
            alias,
            dependency.module.group,
            dependency.module.name,
            dependency.versionConstraint.literal("libraries.$alias"),
        )
    }
    val plugins = libs.pluginAliases.sorted().map { alias ->
        val plugin = libs.findPlugin(alias).get().get()
        CatalogPlugin(alias, plugin.pluginId, plugin.version.literal("plugins.$alias"))
    }
    return Catalog(versions, libraries, plugins)
}

tasks.register("catalogMetadataSync") {
    group = "gate"
    description =
        "Every version gradle/libs.versions.toml declares is pinned in gradle/verification-metadata.xml " +
            "(libraries as components, plugins as markers, floors by presence) — the Dependabot bump that " +
            "forgot the regeneration, caught in a second instead of six minutes into the gradle leg."
    // A verdict, not an artifact: never up-to-date, the same rule as every ladder leg.
    outputs.upToDateWhen { false }
    val metadata = layout.projectDirectory.file("gradle/verification-metadata.xml")
    val catalog = provider { readCatalog() }
    doLast {
        val problems = CatalogMetadata.problems(catalog.get(), CatalogMetadata.components(metadata.asFile.readText()))
        check(problems.isEmpty()) { CatalogMetadata.report(problems) }
        println("catalogMetadataSync: every catalog version is pinned in gradle/verification-metadata.xml")
    }
}
