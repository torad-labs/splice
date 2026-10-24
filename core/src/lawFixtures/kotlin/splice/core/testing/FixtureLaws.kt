// Fixture laws for LawReadGuardTest. They are never run by a Test task: the launcher test selects this class explicitly.
package splice.core.testing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

class FixtureLaws {

    private val set =
        LawReadSet(Path.of("/nonexistent-root"), Path.of(System.getProperty("splice.lawReadGuardFixtureList")))

    /** A law that catches the refusal and then passes every assertion it makes. */
    @Test
    @DisplayName("swallows the refusal and passes")
    fun swallowsTheRefusalAndPasses() {
        val read = runCatching { set.readText(Path.of("/nonexistent-root/tools/undeclared.sh")) }.getOrNull()
        assertEquals(null, read)
    }

    @Test
    @DisplayName("reads nothing and passes")
    fun readsNothingAndPasses() {
        assertTrue(set.files().isEmpty())
    }
}
