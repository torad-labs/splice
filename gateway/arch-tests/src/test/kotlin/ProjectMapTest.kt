// NEW: the project map's own laws (restructure P0). [ProjectMap] is the channel every architecture
// law now reads its subject through, so the channel itself needs the treatment DR-165 gave the
// slot-header law: the live tree cannot red any of these — the map is complete today — which is
// exactly why each one is proven against SYNTHETIC input under a temp dir. A guard the tree cannot
// falsify is indistinguishable from a deleted one.
//
// The four cases are the four ways the map can stop describing the build: the channel is absent,
// the channel is malformed, a module in the map has no build file (the old resolver's
// `return emptySet()`), and a directory on disk ships production Kotlin that no module claims. A
// fifth, from the review of fdc71fad: the census channel — the sweep skips exactly the names the
// build's census input excludes, so the build cannot leave the sweep UP-TO-DATE over a tree it
// never fingerprinted.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val NESTED_FIXTURE_SOURCE = "// NEW: synthetic fixture for the nested-module proof.\n"

private const val NESTED_MODULE = ":provider-x"

private const val NESTED_DIR = "providers/x"

private const val MISSING_BUILD_FILE_EXPECTED = "the project map places :provider-x at providers/x, which ships no build.gradle.kts — a module whose build file cannot be read is UNGRADED, not compliant. Fix the module's directory or restore its build file."

private const val ABSENT_CHANNEL_EXPECTED = "no -Dsplice.projectMap: the laws grade the modules the BUILD declares, and an absent map grades nothing while every law still reports green. gateway/arch-tests/build.gradle.kts is what supplies it."

private const val MALFORMED_ENTRY_EXPECTED = "splice.projectMap entry 'core=core' is not ':<gradle path>=<directory relative to the repository root>' — a half-read map is a map that drops modules silently."

private const val EMPTY_DIRECTORY_EXPECTED = "splice.projectMap entry ':core=' is not ':<gradle path>=<directory relative to the repository root>' — a half-read map is a map that drops modules silently."

private const val DUPLICATE_ENTRY_EXPECTED = "splice.projectMap maps :core twice ('core' and 'elsewhere') — the build cannot place one module in two directories; the channel is corrupt."

private const val UNMAPPED_DIR_EXPECTED = "providers/x ships production Kotlin under src/main/kotlin and the project map claims no module there — include it in settings.gradle.kts so every law grades it, or delete the sources; a module the build never included is silently ungoverned."

private const val STALE_NAME_EXPECTED = "the project map has no :ghost — the laws grade the modules the build declares, so a name this file asks for and the build does not include is a stale entry, not a gap."

private const val ABSENT_CENSUS_EXPECTED = "no -Dsplice.censusNotSwept: the unmapped-source sweep skips exactly the directory names the build's census input excludes, and a sweep that cannot see that list would walk trees the build never fingerprints. gateway/arch-tests/build.gradle.kts is what supplies it."

private const val GENERATED_DIR_EXPECTED = "build ships production Kotlin under src/main/kotlin and the project map claims no module there — include it in settings.gradle.kts so every law grades it, or delete the sources; a module the build never included is silently ungoverned."

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

    private val map = ProjectMap.fromSystemProperties()

    @Test
    fun `the live map names every module the build includes, and each one's real directory`() {
        assertTrue(map.modules.size > 1) {
            "the project map yielded ${map.modules.size} module(s) — the channel is broken, and " +
                "every law that counts against it would grade nothing and pass."
        }
        assertEquals(
            emptyList<String>(),
            map.modules.sorted().filterNot { map.dir(it).isDirectory },
            "every module the build declares must live in a directory that exists",
        )
    }

    @Test
    fun `every module in the live map ships a build file`() {
        val buildFiles = map.modules.sorted().map { map.buildFile(it) }
        assertEquals(
            map.modules.size,
            buildFiles.size,
            "a missing build file is a hard failure naming the module — see ProjectMap.buildFile",
        )
    }

    @Test
    fun `the live map claims every directory that ships production Kotlin`() {
        assertEquals(
            emptyList<String>(),
            map.unmappedProductionDirViolations(),
            "a source tree the build never included is a tree every law walks past in silence",
        )
    }

    // P0 RED PROOF: a module whose directory is NESTED resolves through the map. The old readings
    // composed root/<id>: ArchitectureLawsTest.kt:35-43 (listFiles of the root's children) never
    // saw providers/x at all, and ModuleLawsTest.kt:731 looked for provider-x/build.gradle.kts.
    @Test
    fun `a NESTED module resolves through its mapped directory - P0`(@TempDir temp: File) {
        val buildFile = writeFixture(temp, "$NESTED_DIR/build.gradle.kts", "dependencies { }\n")
        val source = writeFixture(temp, "$NESTED_DIR/src/main/kotlin/X.kt", NESTED_FIXTURE_SOURCE)
        val nested = ProjectMap.parse(temp, "$NESTED_MODULE=$NESTED_DIR", fixtureNotSwept)
        assertEquals(setOf(NESTED_MODULE), nested.modules)
        assertEquals(NESTED_DIR, nested.relativeDir(NESTED_MODULE))
        assertEquals(buildFile, nested.buildFile(NESTED_MODULE))
        assertEquals(source.parentFile, nested.mainSources(NESTED_MODULE))
        assertEquals(
            emptyList<String>(),
            nested.unmappedProductionDirViolations(),
            "a nested tree the map DOES claim is not a violation",
        )
    }

    // P0 RED PROOF: a module in the map whose build.gradle.kts does not exist. The resolver this
    // replaces (ModuleLawsTest.kt:732, `if (!buildFile.isFile) return emptySet()`) answered this
    // case with NO EDGES, so the module's whole dependency grading disappeared without a failure.
    @Test
    fun `a module in the map with no build file fails BY NAME - P0`(@TempDir temp: File) {
        val nested = ProjectMap.parse(temp, "$NESTED_MODULE=$NESTED_DIR", fixtureNotSwept)
        val failure = assertThrows<IllegalStateException> { nested.buildFile(NESTED_MODULE) }
        assertEquals(MISSING_BUILD_FILE_EXPECTED, failure.message, "the module must be named")
    }

    // P0 RED PROOF: the channel itself. Absent, blank, malformed and duplicated all fail by name —
    // a map read half-way is a map that drops modules, and dropping a module is the silent loss
    // this whole stage exists to make impossible.
    @Test
    fun `the map channel fails BY NAME when it is absent or malformed - P0`(@TempDir temp: File) {
        assertEquals(
            ABSENT_CHANNEL_EXPECTED,
            assertThrows<IllegalStateException> { ProjectMap.parse(temp, null, fixtureNotSwept) }.message,
            "an absent channel must fail, not grade an empty tree",
        )
        assertEquals(
            ABSENT_CHANNEL_EXPECTED,
            assertThrows<IllegalStateException> { ProjectMap.parse(temp, "   ", fixtureNotSwept) }.message,
            "a blank channel is an absent one wearing a label",
        )
        assertEquals(
            MALFORMED_ENTRY_EXPECTED,
            assertThrows<IllegalStateException> { ProjectMap.parse(temp, "core=core", fixtureNotSwept) }.message,
            "an entry whose key is not a Gradle path must fail BY NAME, quoting the entry",
        )
        assertEquals(
            EMPTY_DIRECTORY_EXPECTED,
            assertThrows<IllegalStateException> { ProjectMap.parse(temp, ":core=", fixtureNotSwept) }.message,
            "a module mapped to no directory is the same half-read map",
        )
        assertEquals(
            DUPLICATE_ENTRY_EXPECTED,
            assertThrows<IllegalStateException> {
                ProjectMap.parse(temp, ":core=core;:core=elsewhere", fixtureNotSwept)
            }.message,
            "one module cannot live in two directories",
        )
    }

    // P0 RED PROOF: a directory on disk that ships production Kotlin and that the map does not
    // list. This is the OTHER silent-loss case — not a module that moved, but a module the build
    // never included, which every law walks past because the laws count the map, not the disk.
    @Test
    fun `a production source tree the map does not claim fails BY NAME - P0`(@TempDir temp: File) {
        val unclaimed = writeFixture(temp, "$NESTED_DIR/src/main/kotlin/splice/provider/x/X.kt", NESTED_FIXTURE_SOURCE)
        val claimed = writeFixture(temp, "core/src/main/kotlin/splice/core/Core.kt", NESTED_FIXTURE_SOURCE)
        assertTrue(unclaimed.isFile && claimed.isFile) {
            "both fixtures must land, or the sweep has nothing to find and the proof is vacuous"
        }
        assertEquals(
            listOf(UNMAPPED_DIR_EXPECTED),
            ProjectMap.parse(temp, ":core=core", fixtureNotSwept).unmappedProductionDirViolations(),
            "the unclaimed tree must be named; the claimed one must not be",
        )
    }

    // P0: a name the build does not declare is a STALE entry in this file, not a silent skip —
    // the mirror of the missing-build-file case, one level up.
    @Test
    fun `a module the map does not declare fails BY NAME - P0`(@TempDir temp: File) {
        val nested = ProjectMap.parse(temp, "$NESTED_MODULE=$NESTED_DIR", fixtureNotSwept)
        assertEquals(
            STALE_NAME_EXPECTED,
            assertThrows<IllegalStateException> { nested.relativeDir(":ghost") }.message,
            "a law asking for a module the build has not got must fail by name",
        )
    }

    // P0, from the review of fdc71fad: THE CENSUS CHANNEL. The sweep skips exactly the directory
    // names the build hands it, and the build fingerprints the same tree as the task's census input,
    // so the two cannot disagree about which directories count. An absent channel fails by name,
    // like the map's: a sweep that invented its own list could walk a tree the build never
    // fingerprints, and new sources there would leave the task UP-TO-DATE with the sweep unrun.
    @Test
    fun `the census channel fails BY NAME when it is absent - P0`() {
        assertEquals(
            ABSENT_CENSUS_EXPECTED,
            assertThrows<IllegalStateException> { ProjectMap.notSweptFrom(null) }.message,
            "an absent census channel must fail, not sweep with a list of its own",
        )
        assertEquals(
            ABSENT_CENSUS_EXPECTED,
            assertThrows<IllegalStateException> { ProjectMap.notSweptFrom("  ") }.message,
            "a blank channel is an absent one wearing a label",
        )
        assertEquals(
            setOf("build", "node_modules"),
            ProjectMap.notSweptFrom("build; node_modules;"),
            "the channel is a name list — trimmed, and never an empty name",
        )
    }

    @Test
    fun `the sweep skips exactly the directory names the build hands it - P0`(@TempDir temp: File) {
        val generated = writeFixture(temp, "build/src/main/kotlin/Generated.kt", NESTED_FIXTURE_SOURCE)
        assertTrue(generated.isFile) { "the fixture must land, or the sweep has nothing to find" }
        assertEquals(
            emptyList<String>(),
            ProjectMap.parse(temp, ":core=core", setOf("build")).unmappedProductionDirViolations(),
            "a name on the list is never entered",
        )
        assertEquals(
            listOf(GENERATED_DIR_EXPECTED),
            ProjectMap.parse(temp, ":core=core", setOf("node_modules")).unmappedProductionDirViolations(),
            "a name off the list is swept — the list is load-bearing, not decoration",
        )
    }

    // The same list bounds the INNER walk: a production tree whose only Kotlin sits under a name on
    // the list (providers/x/src/main/kotlin/build/X.kt) is a tree the build's census never
    // fingerprints, so the sweep must not count it either — or an incremental run stays green while
    // a forced run names providers/x, and the two disagree about the same file.
    @Test
    fun `the list bounds the inner walk below the source root too - P0`(@TempDir temp: File) {
        val nested = writeFixture(temp, "$NESTED_DIR/src/main/kotlin/build/X.kt", NESTED_FIXTURE_SOURCE)
        assertTrue(nested.isFile) { "the fixture must land, or the sweep has nothing to find" }
        assertEquals(
            emptyList<String>(),
            ProjectMap.parse(temp, ":core=core", setOf("build")).unmappedProductionDirViolations(),
            "Kotlin under a name on the list is invisible to the census, so it must be invisible here",
        )
        assertEquals(
            listOf(UNMAPPED_DIR_EXPECTED),
            ProjectMap.parse(temp, ":core=core", setOf("node_modules")).unmappedProductionDirViolations(),
            "with build off the list the same tree is named — the list bounds both walks",
        )
    }
}
