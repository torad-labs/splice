// The version catalog as plain data, read through Gradle's own VersionCatalog (the build's reading of
// libs.versions.toml, so no second TOML parser can disagree with it). It left splice.dependency-hygiene as
// top-level functions in the script, where the production rules (no extension function, no top-level function)
// could not see it until they selected scripts.
package splice.hygiene

import org.gradle.api.GradleException
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionConstraint

internal class CatalogReader(private val libs: VersionCatalog) {
    fun read(): Catalog {
        val versions = libs.versionAliases.sorted().associateWith { alias ->
            checkNotNull(
                literal(libs.findVersion(alias).get(), "[versions] $alias"),
            ) { "[versions] $alias: no version" }
        }
        val libraries = libs.libraryAliases.sorted().map { alias ->
            val dependency = libs.findLibrary(alias).get().get()
            CatalogLibrary(
                alias,
                dependency.module.group,
                dependency.module.name,
                literal(dependency.versionConstraint, "libraries.$alias"),
            )
        }
        val plugins = libs.pluginAliases.sorted().map { alias ->
            val plugin = libs.findPlugin(alias).get().get()
            CatalogPlugin(alias, plugin.pluginId, literal(plugin.version, "plugins.$alias"))
        }
        return Catalog(versions, libraries, plugins)
    }

    /** The required version of [alias], loud on a missing alias: it must not fall back to a literal, or the skew a
     *  catalog exists to prevent returns wearing the fix's clothes. */
    fun version(alias: String): String =
        libs.findVersion(alias).orElseThrow {
            GradleException("version catalog has no `$alias`: libs.versions.toml and this convention plugin disagree")
        }.requiredVersion

    /** A rich version (strictly / prefer / reject) carries no single literal to pin: refuse, never skip. */
    private fun literal(constraint: VersionConstraint, where: String): String? {
        val rich = constraint.strictVersion.isNotEmpty() ||
            constraint.preferredVersion.isNotEmpty() ||
            constraint.rejectedVersions.isNotEmpty()
        check(!rich) { "$where: rich version $constraint, extend splice.hygiene.CatalogMetadata, do not skip" }
        return constraint.requiredVersion.ifEmpty { null }
    }
}
