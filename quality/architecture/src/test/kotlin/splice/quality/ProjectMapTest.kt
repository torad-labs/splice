// NEW: the project map's own laws (restructure P0). [ProjectMap] is the channel every architecture
// law reads its subject through, so the channel itself is proven against SYNTHETIC input under a
// temp dir: the live tree cannot red these, and a guard the tree cannot falsify is indistinguishable
// from a deleted one.
package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val FIXTURE_SOURCE = "// NEW: synthetic fixture.\n"

private const val NESTED_MODULE = ":provider-x"

private const val NESTED_DIR = "providers/x"

/** Synthetic fixtures live under a temp dir, so this list is fixture data; the LIVE list is the
 *  build's, read through [ProjectMap.CENSUS_PROPERTY]. */
internal val fixtureNotSwept: Set<String> = setOf("build", ".git", ".gradle", "node_modules")

/** Writes a fixture file and every directory above it, consuming mkdirs()' verdict rather than
 *  discarding it — a fixture that silently failed to land would make the proof below vacuous. */
private fun writeFixture(root: File, path: String, text: String): File {
    val file = File(root, path)
    val parent = file.parentFile
    val created = parent.mkdirs()
    check(created || parent.isDirectory) { "fixture directory $parent was not created" }
    file.writeText(text)
    return file
}

class ProjectMapTest {

    @Test
    fun `the live map is whole - modules exist and no production tree goes unclaimed`() {
        val map = ProjectMap.fromSystemProperties()
        assertTrue(map.modules.size > 1) { "the project map yielded ${map.modules.size} module(s)" }
        assertEquals(emptyList<String>(), map.modules.sorted().filterNot { map.dir(it).isDirectory })
        assertEquals(emptyList<String>(), map.unmappedProductionDirViolations())
    }

    @Test
    fun `a NESTED module resolves through its mapped directory - P0`(@TempDir temp: File) {
        val buildFile = writeFixture(temp, "$NESTED_DIR/build.gradle.kts", "dependencies { }\n")
        val source = writeFixture(temp, "$NESTED_DIR/src/main/kotlin/X.kt", FIXTURE_SOURCE)
        val nested = ProjectMap.parse(temp, "$NESTED_MODULE=$NESTED_DIR", fixtureNotSwept)
        assertEquals(setOf(NESTED_MODULE), nested.modules)
        assertEquals(NESTED_DIR, nested.relativeDir(NESTED_MODULE))
        assertEquals(buildFile, nested.buildFile(NESTED_MODULE))
        assertEquals(source.parentFile, nested.mainSources(NESTED_MODULE))
        assertEquals(emptyList<String>(), nested.unmappedProductionDirViolations())
    }

    @Test
    fun `a module in the map with no build file fails - P0`(@TempDir temp: File) {
        val nested = ProjectMap.parse(temp, "$NESTED_MODULE=$NESTED_DIR", fixtureNotSwept)
        assertThrows<IllegalStateException> { nested.buildFile(NESTED_MODULE) }
    }

    @Test
    fun `a module the map does not declare fails - P0`(@TempDir temp: File) {
        val nested = ProjectMap.parse(temp, "$NESTED_MODULE=$NESTED_DIR", fixtureNotSwept)
        assertThrows<IllegalStateException> { nested.relativeDir(":ghost") }
    }

    @Test
    fun `the map channel fails when it is absent, blank, malformed or duplicated - P0`(@TempDir temp: File) {
        listOf(null, "   ", "core=core", ":core=", ":core=core;:core=elsewhere").forEach { channel ->
            assertThrows<IllegalStateException>("channel <$channel>") {
                ProjectMap.parse(temp, channel, fixtureNotSwept)
            }
        }
    }

    @Test
    fun `a production source tree the map does not claim is named - P0`(@TempDir temp: File) {
        writeFixture(temp, "$NESTED_DIR/src/main/kotlin/splice/provider/x/X.kt", FIXTURE_SOURCE)
        writeFixture(temp, "core/src/main/kotlin/splice/core/Core.kt", FIXTURE_SOURCE)
        val violations = ProjectMap.parse(temp, ":core=core", fixtureNotSwept).unmappedProductionDirViolations()
        assertEquals(1, violations.size, "only the unclaimed tree is a violation: $violations")
        assertTrue(violations.single().contains(NESTED_DIR), "the unclaimed tree must be named: $violations")
    }

    @Test
    fun `the census channel fails when absent and parses a trimmed name list - P0`() {
        assertThrows<IllegalStateException> { ProjectMap.notSweptFrom(null) }
        assertThrows<IllegalStateException> { ProjectMap.notSweptFrom("  ") }
        assertEquals(setOf("build", "node_modules"), ProjectMap.notSweptFrom("build; node_modules;"))
    }

    @Test
    fun `the not-swept list bounds both the outer and the inner walk - P0`(@TempDir temp: File) {
        writeFixture(temp, "build/src/main/kotlin/Generated.kt", FIXTURE_SOURCE)
        writeFixture(temp, "$NESTED_DIR/src/main/kotlin/build/X.kt", FIXTURE_SOURCE)
        assertEquals(
            emptyList<String>(),
            ProjectMap.parse(temp, ":core=core", setOf("build")).unmappedProductionDirViolations(),
            "a name on the list is never entered",
        )
        val swept = ProjectMap.parse(temp, ":core=core", setOf("node_modules")).unmappedProductionDirViolations()
        assertEquals(2, swept.size, "with build off the list both trees are named: $swept")
    }

    @Test
    fun `a nested checkout is another repository's tree, not an unclaimed module - PR2`(@TempDir temp: File) {
        writeFixture(temp, ".git", "gitdir: /nowhere\n")
        writeFixture(temp, "gateway/core/build.gradle.kts", "dependencies { }\n")
        writeFixture(temp, "gateway/core/src/main/kotlin/A.kt", FIXTURE_SOURCE)
        // A worktree's .git is a FILE; a submodule or vendored clone's is a DIRECTORY.
        writeFixture(temp, "wt/.git", "gitdir: /nowhere\n")
        writeFixture(temp, "wt/gateway/core/src/main/kotlin/B.kt", FIXTURE_SOURCE)
        writeFixture(temp, "vendor/clone/gateway/core/src/main/kotlin/C.kt", FIXTURE_SOURCE)
        check(File(temp, "vendor/clone/.git").mkdirs()) { "the .git directory fixture was not created" }
        val map = ProjectMap.parse(temp, ":core=gateway/core", fixtureNotSwept)
        assertEquals(emptyList<String>(), map.unmappedProductionDirViolations())
        writeFixture(temp, "$NESTED_DIR/src/main/kotlin/splice/provider/x/X.kt", FIXTURE_SOURCE)
        assertEquals(
            1,
            map.unmappedProductionDirViolations().size,
            "the root's own .git must not blank the sweep, and a real unclaimed tree is still named",
        )
    }
}
