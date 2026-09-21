// NEW: V4-176 — public source states what splice REQUIRES of its host and never who supplies it.
//
// WHY A WALL AND NOT A CLEANUP. The names came in one at a time, each in a comment written while
// the fact was fresh and true on this box, and every one of them was correct about this machine —
// that is precisely why review never stopped them. A sweep fixes the twenty-five that existed; the
// twenty-sixth arrives in the next comment someone writes at a keyboard on the same box. Only a
// check that runs makes the drift not compile.
//
// WHAT IS ACTUALLY WRONG WITH THE NAME, since "it is just a comment" is the obvious objection: a
// comment is where the next reader learns what is required of them. "the unit belongs to <private
// tool>" tells a reader packaging splice for their own box nothing they can act on, and quietly
// says the requirement is someone else's problem. "a unit that starts this process and restarts it
// when it exits" is the same sentence with the requirement in it. The names are also a private
// infrastructure inventory, and this repo ships publicly (ReleaseReadinessLawTest).
//
// AND NOTE WHERE THAT SENTENCE HAD TO BE REWRITTEN: its first draft quoted the real name as the
// example of what not to write, and this wall failed on its own file at that line. A check that
// cannot be tripped by its own explanation is not covering its own directory — the same shape the
// secret-scan allowlist hit when an exemption entry became a finding.
//
// THE ROOTS ARE THE ROW'S, and deliberately not the whole repo: checks/gradle-slot.sh and
// checks/e2e/docker/run.sh reach for this box's build wrapper BY NAME on purpose — they are the
// local integration point, guarded by `command -v`, and a box without it runs the plain gradle
// path. Naming it there is the opposite of the problem this wall is about. Widening the roots
// would mean adding those two as exemptions, which is a worse contract than a scope that says
// what it covers.
//
// The SCAN is parameterised on its roots so the arms below can watch it fail — a wall that has only
// ever been pointed at a clean tree is a wall nobody has checked.
package splice.app.cli.doctor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.AdminSupport
import splice.core.config.Knob
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo

/** The host tools this box happens to run, which public source must not name. Assembled from parts
 *  so this file does not itself carry the literals it bans — the same reason the secret-scan
 *  allowlist splits its fixtures. */
private val BANNED: List<String> = listOf("host" + "shield", "build" + "gate")

/** Generated output and dependency trees are not source and are not ours to phrase. */
private val SKIP_DIRS = setOf("build", "node_modules", ".git", "dist", ".gradle")

/** Generated output, images and archives are not prose and carry no requirement to state. */
private val SKIP_EXTENSIONS = setOf("jar", "png", "ico", "woff2")

private fun findingsIn(root: Path, file: Path): List<String> {
    if (file.extension in SKIP_EXTENSIONS) return emptyList()
    val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
    val relative = file.relativeTo(root).toString()
    return text.lineSequence().withIndex().flatMap { (index, line) ->
        BANNED.filter { it in line }.map { "$relative:${index + 1} names '$it'" }
    }.toList()
}

private fun scan(root: Path, roots: List<Path>): List<String> =
    roots.filter { Files.exists(it) }
        .flatMap { start -> Files.walk(start).use { walk -> walk.toList() } }
        .filter { it.isRegularFile() && it.none { part -> part.name in SKIP_DIRS } }
        .flatMap { findingsIn(root, it) }
        .sorted()

class PublicSourceNamesNoHostToolTest {

    private val repo: Path = run {
        // The roots this row owns are named relative to the repository root, found by walking up.
        var dir = Path.of("").toAbsolutePath()
        while (!Files.exists(dir.resolve("install.sh")) && dir.parent != null) dir = dir.parent
        dir
    }

    @Test
    fun `no public source names a host tool`() {
        assertTrue(Files.exists(repo.resolve("install.sh")), "repo root not found from ${Path.of("").toAbsolutePath()}")

        // PR 2 moved the Gradle build root out of gateway/ to the repository root. These are the
        // same files this row already owned, under their new paths — a wall whose roots stay
        // spelled `gateway` after the move would quietly stop reading the build files.
        val roots = listOf(
            "gateway",
            "build-logic",
            "quality/detekt",
            "gradle",
            "settings.gradle.kts",
            "build.gradle.kts",
            "gradle.properties",
            "gradlew",
            "gradlew.bat",
            "install.sh",
            ".gitignore",
        ).map { repo.resolve(it) }
        val findings = scan(repo, roots)

        assertEquals(
            emptyList<String>(),
            findings,
            "public source must state what splice REQUIRES of its host, never which tool supplies " +
                "it here. Say the requirement (a unit that restarts this process; a slice with a " +
                "memory ceiling) and let the host be whatever the reader runs.",
        )
    }

    // The mutant: a comment that names the tool. Without this arm the test above is green on a clean
    // tree whether or not the scan works at all — which is how the twenty-five got in under a
    // review that was reading the same tree.
    @Test
    fun `the scan reports a planted name by file and line`(@TempDir dir: Path) {
        val file = dir.resolve("Planted.kt")
        Files.writeString(file, "// the unit is ${"host" + "shield"}'s own\nclass Planted\n")

        val findings = scan(dir, listOf(dir))

        assertEquals(listOf("Planted.kt:1 names '${"host" + "shield"}'"), findings)
    }

    // Both names, not just the one the row was opened for: the second is the build wrapper, and it
    // reached the tree through the same door.
    @Test
    fun `every banned name is reported, not only the first`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("A.kt"), "// ${"build" + "gate"} caps the build\n")

        assertEquals(listOf("A.kt:1 names '${"build" + "gate"}'"), scan(dir, listOf(dir)))
    }
}

/**
 * The other half of V4-176: the supervision requirement is a NAME THE OPERATOR SUPPLIES. splice
 * restarts into a systemd user unit it does not own, and hardcoding `splice.service` made a box
 * whose packager called the unit something else look permanently unsupervised — which is not a
 * cosmetic misreading: `POST /api/daemon/restart` REFUSES to drain an unsupervised daemon, because
 * draining one that nothing brings back is a stop button wearing a restart label.
 */
class SupervisorUnitIsConfiguredTest {

    @Test
    fun `the declared default is this repo's own unit name`() {
        assertEquals("splice.service", Knob.SUPERVISOR_UNIT.default)
        assertEquals("app-mcp.slice", Knob.MCP_SLICE.default)
    }

    // Mutant: pass a blank value through. `systemctl --user is-active ""` is not a question about
    // this install, and the answer would be read as "nothing supervises me" on a box that is
    // perfectly well supervised — a knob left empty must fall back, never degrade.
    @Test
    fun `a blank unit name falls back to the default rather than asking about nothing`() {
        val unit = AdminSupport.supervisorUnit(EnvReader { name -> if (name == "SPLICE_SUPERVISOR_UNIT") "" else null })

        assertEquals("splice.service", unit)
    }

    @Test
    fun `an environment-supplied unit name is what splice uses`() {
        val unit = AdminSupport.supervisorUnit(
            EnvReader { name -> if (name == "SPLICE_SUPERVISOR_UNIT") "my-splice.service" else null },
        )

        assertEquals("my-splice.service", unit)
    }
}
