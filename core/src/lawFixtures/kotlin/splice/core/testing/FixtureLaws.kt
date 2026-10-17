// Fixture laws for LawReadGuardTest. They are never run by a Test task: the launcher test selects this class explicitly.
package splice.core.testing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class FixtureLaws {

    private val set =
        LawReadSet(Path.of("/nonexistent-root"), Path.of(System.getProperty("splice.lawReadGuardFixtureList")))

    /** A law that catches the refusal and then passes every assertion it makes. */
    @Test
    fun `swallows the refusal and passes`() {
        val read = runCatching { set.readText(Path.of("/nonexistent-root/tools/undeclared.sh")) }.getOrNull()
        assertEquals(null, read)
    }

    @Test
    fun `reads nothing and passes`() {
        assertTrue(set.files().isEmpty())
    }
}
