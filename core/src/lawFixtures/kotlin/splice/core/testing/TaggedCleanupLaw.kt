// Fixture law: a class whose only undeclared read happens in @AfterAll, caught, after every test passed. LawReadGuardTest
// launches it and asserts the class fails through the guard's class-scope callback.
package splice.core.testing

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Path

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaggedCleanupLaw {

    private val set =
        LawReadSet(Path.of("/nonexistent-root"), Path.of(System.getProperty("splice.lawReadGuardFixtureList")))

    @Test
    fun `passes`() {
        set.files()
    }

    @AfterAll
    fun `reads undeclared in cleanup and swallows the refusal`() {
        val refusal = runCatching {
            set.readText(
                Path.of("/nonexistent-root/tools/cleanup-undeclared.sh"),
            )
        }.exceptionOrNull()
        check(refusal != null) { "the undeclared read was not refused" }
    }
}
