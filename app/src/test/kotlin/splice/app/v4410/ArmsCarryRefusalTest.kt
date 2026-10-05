// NEW: V4-410 — every place the app lists a pool's account files or builds a wired account carries the
// refusal onward. RefusedAccountWiringTest drives the Codex arm; Grok, Muse and Kimi build their accounts by
// their own code, and one that forgot the field would show a refused link as a plain missing credential, with
// the renewal the writer refuses. The denominator comes from the sources: every main file that calls
// `accountFiles.discover(` or constructs a `WiredAccount(`, found by scan, so a fifth arm that forgets fails
// BY NAME instead of passing because nobody listed it.
package splice.app.v4410

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

private const val SOURCE_ROOT = "app/src/main/kotlin"

class ArmsCarryRefusalTest {
    private val comments = Regex("""(?sm)/\*.*?\*/|^\s*//[^\n]*| //[^\n]*""")
    private val listsPoolFiles = Regex("""accountFiles\.discover\(""")
    private val buildsWiredAccount = Regex("""(?<!class )WiredAccount\(""")
    private val carriesRefusal = Regex("""refusal\s*=\s*(file|account|view)\.refusal""")

    /** Every main source of this module, comments stripped, by file name. */
    private fun sources(): Map<String, String> {
        var dir = Path.of("").toAbsolutePath()
        while (!Files.isDirectory(dir.resolve(SOURCE_ROOT)) && dir.parent != null) dir = dir.parent
        return Files.walk(dir.resolve(SOURCE_ROOT)).use { files ->
            files.filter { it.toString().endsWith(".kt") }.toList()
                .associate { it.fileName.toString() to comments.replace(Files.readString(it), "") }
        }
    }

    @Test
    fun `native view refusal is a carried reading but absence and an invented sentence are not`() {
        assertTrue(carriesRefusal.containsMatchIn("WiredAccount(refusal = view.refusal)"))
        assertFalse(carriesRefusal.containsMatchIn("WiredAccount(credentialPresent = view.credentialPresent)"))
        assertFalse(carriesRefusal.containsMatchIn("""WiredAccount(refusal = "invented")"""))
    }

    @Test
    fun `every arm that lists pool files or builds a wired account carries the refusal`() {
        val sources = sources()
        val arms = sources.filterValues { listsPoolFiles.containsMatchIn(it) || buildsWiredAccount.containsMatchIn(it) }

        assertTrue(arms.isNotEmpty(), "the scan found no arm: its patterns no longer match the sources")
        val forgot = arms.filterValues { !carriesRefusal.containsMatchIn(it) }.keys.sorted()
        assertEquals(emptyList<String>(), forgot, "these build accounts from pool files and drop the refusal")
    }
}
