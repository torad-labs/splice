// Public source states what splice REQUIRES of its host and never who supplies it: the names of this
// box's private host tools must not appear in any file the build declares as this law's input
// (the repo ships publicly, see ReleaseReadinessLawTest). Say the requirement (a unit that restarts this
// process; a slice with a memory ceiling) and let the host be whatever the reader runs.
package splice.app.cli.doctor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import splice.core.testing.LawReadSet
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.relativeTo

/** The host tools this box happens to run, which public source must not name. Assembled from parts
 *  so this file does not itself carry the literals it bans. */
private val BANNED: List<String> = listOf("host" + "shield", "build" + "gate")

/** Generated output, images and archives are not prose and carry no requirement to state. */
private val SKIP_EXTENSIONS = setOf("jar", "png", "ico", "woff2")

@Tag("law")
class PublicSourceNamesNoHostToolTest {

    private val readSet = LawReadSet()
    private val repo: Path = readSet.root

    @Test
    fun `no public source names a host tool`() {
        assertTrue(Files.exists(repo.resolve("install.sh")), "repo root not found at $repo")

        val findings = readSet.files()
            .filter { it.extension !in SKIP_EXTENSIONS }
            .flatMap { file ->
                readSet.readText(file).lineSequence().withIndex().flatMap { (index, line) ->
                    BANNED.filter { it in line }.map { "${file.relativeTo(repo)}:${index + 1} names '$it'" }
                }.toList()
            }
            .sorted()

        assertEquals(
            emptyList<String>(),
            findings,
            "public source must state what splice REQUIRES of its host, never which tool supplies it here.",
        )
    }
}
