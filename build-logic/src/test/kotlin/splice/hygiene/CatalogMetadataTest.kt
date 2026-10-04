// The red-green proof for CatalogMetadata (checks/catalog-metadata-selftest.sh until PR 6): the
// check guards the catalog <-> verification-metadata seam, this guards the CHECK, so a reader that
// starts silently skipping entries or a namespace regression fails the gate instead of waving drift
// through. Fixtures are synthetic but carry the REAL metadata xmlns — a non-namespace-aware reader
// must fail HERE, not on the real file.
package splice.hygiene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CatalogMetadataTest {
    // One of each catalog shape the check must cover: version.ref library, inline-version library,
    // versionless BOM rider (must be skipped), plugin (checked as its marker artifact), and an
    // UNREFERENCED floor version (netty-style: no library uses it, but a bump must still trip the
    // check via version presence).
    private val catalog = Catalog(
        versions = mapOf("ktor" to "3.5.2", "floor" to "9.9.9.Final"),
        libraries = listOf(
            CatalogLibrary("ktor.server.core", "io.ktor", "ktor-server-core", "3.5.2"),
            CatalogLibrary("zstd", "com.example", "zstd", "1.0"),
            CatalogLibrary("bom.rider", "org.junit.jupiter", "junit-jupiter", null),
        ),
        plugins = listOf(CatalogPlugin("kover", "org.example.kover", "0.9.9")),
    )

    private fun metadata(vararg components: Triple<String, String, String>): String =
        """<?xml version="1.0" encoding="UTF-8"?>
        |<verification-metadata xmlns="${CatalogMetadata.NAMESPACE}" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
        |   <configuration>
        |      <verify-metadata>true</verify-metadata>
        |   </configuration>
        |   <components>
        |${components.joinToString("") { (group, name, version) -> component(group, name, version) }}
        |   </components>
        |</verification-metadata>
        |""".trimMargin()

    private fun component(group: String, name: String, version: String) =
        "      <component group=\"$group\" name=\"$name\" version=\"$version\">" +
            "<artifact name=\"x.jar\"><sha256 value=\"0\" origin=\"test\"/></artifact></component>\n"

    private val ktor = Triple("io.ktor", "ktor-server-core", "3.5.2")
    private val zstd = Triple("com.example", "zstd", "1.0")
    private val koverMarker = Triple("org.example.kover", "org.example.kover.gradle.plugin", "0.9.9")
    private val floor = Triple("io.example", "transitive-floor", "9.9.9.Final")

    private fun problems(vararg components: Triple<String, String, String>) =
        CatalogMetadata.problems(catalog, CatalogMetadata.components(metadata(*components)))

    @Test
    fun `the compliant fixture is green`() {
        assertEquals(emptyList<String>(), problems(ktor, zstd, koverMarker, floor))
    }

    @Test
    fun `a library pinned at the old version is red by coordinate`() {
        val found = problems(Triple("io.ktor", "ktor-server-core", "3.5.1"), zstd, koverMarker, floor)
        assertEquals(1, found.size, found.toString())
        assertTrue("io.ktor:ktor-server-core:3.5.2" in found.single()) { found.single() }
    }

    @Test
    fun `a plugin marker pinned at the old version is red by marker`() {
        val found = problems(ktor, zstd, Triple("org.example.kover", "org.example.kover.gradle.plugin", "0.9.8"), floor)
        assertEquals(1, found.size, found.toString())
        assertTrue("org.example.kover.gradle.plugin:0.9.9" in found.single()) { found.single() }
    }

    @Test
    fun `an absent floor version is red by version`() {
        val found = problems(ktor, zstd, koverMarker)
        assertEquals(1, found.size, found.toString())
        assertTrue("9.9.9.Final" in found.single()) { found.single() }
    }

    @Test
    fun `a markerless plugin whose version is pinned elsewhere is green - the build-logic pattern`() {
        // Marker absent but the plugin's version pinned via another component: a plugin applied by
        // bare id inside a precompiled script never resolves its marker, so regeneration can never
        // add it. The obligation degrades to version presence.
        assertEquals(emptyList<String>(), problems(ktor, zstd, Triple("org.example", "kover-gradle-plugin-impl", "0.9.9"), floor))
    }

    @Test
    fun `the report carries every problem and the remedy`() {
        val report = CatalogMetadata.report(listOf("a: x", "b: y"))
        assertTrue(report.startsWith("  a: x\n  b: y\n")) { report }
        assertTrue("--write-verification-metadata sha256" in report) { report }
    }

    @Test
    fun `the reader refuses a document it cannot read as the expected shape`() {
        val noNamespace = metadata(ktor).replace("xmlns=\"${CatalogMetadata.NAMESPACE}\" ", "")
        assertTrue("namespace" in assertThrows(UnreadableMetadata::class.java) { CatalogMetadata.components(noNamespace) }.message!!)
        assertTrue("no <component>" in assertThrows(UnreadableMetadata::class.java) { CatalogMetadata.components(metadata()) }.message!!)
        val attributeless = metadata(ktor).replace(" version=\"3.5.2\"", "")
        assertTrue("group/name/version" in assertThrows(UnreadableMetadata::class.java) { CatalogMetadata.components(attributeless) }.message!!)
    }
}
