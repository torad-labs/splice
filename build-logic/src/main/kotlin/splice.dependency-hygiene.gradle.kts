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
import splice.hygiene.CatalogMetadata
import splice.hygiene.CatalogReader
import splice.hygiene.MetadataRead

// Resolved here, in the project script: inside the task block `extensions` is the TASK's container, which holds no catalogs.
private val catalogReader = CatalogReader(extensions.getByType<VersionCatalogsExtension>().named("libs"))

tasks.register("catalogMetadataSync") {
    group = "gate"
    description =
        "Every version gradle/libs.versions.toml declares is pinned in gradle/verification-metadata.xml " +
        "(libraries as components, plugins as markers, floors by presence): the Dependabot bump that " +
        "forgot the regeneration, caught in a second instead of six minutes into the gradle leg."
    // A verdict, not an artifact: never up-to-date, the same rule as every ladder leg.
    outputs.upToDateWhen { false }
    val metadata = layout.projectDirectory.file("gradle/verification-metadata.xml")
    val catalog = provider { catalogReader.read() }
    doLast {
        val read = CatalogMetadata.read(metadata.asFile.readText())
        val pinned = when (read) {
            is MetadataRead.Pinned -> read
            is MetadataRead.Unreadable -> throw GradleException(read.reason)
        }
        val problems = CatalogMetadata.problems(catalog.get(), pinned.components)
        check(problems.isEmpty()) { CatalogMetadata.report(problems) }
        logger.lifecycle("catalogMetadataSync: every catalog version is pinned in gradle/verification-metadata.xml")
    }
}
