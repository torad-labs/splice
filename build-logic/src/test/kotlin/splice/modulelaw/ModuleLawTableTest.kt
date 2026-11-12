// The format of gradle/module-law.txt as ModuleLawTable reads it. The defect it guards: the table lived as Kotlin
// inside a build script, so the law test read it out of script text; one data file with one reader per side cannot drift.
package splice.modulelaw

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModuleLawTableTest {
    @Test
    fun `a module line lists what it may depend on, with comments and blank lines between`() {
        val table = ModuleLawTable.parse(
            """
            # the root
            :core ->

            # a leaf
            :http -> :core
            :turns -> :core :http :sessions
            """.trimIndent(),
            "table",
        )
        assertEquals(
            mapOf(":core" to emptySet(), ":http" to setOf(":core"), ":turns" to setOf(":core", ":http", ":sessions")),
            table,
        )
    }

    @Test
    fun `a malformed line and a repeated module are refused by line number`() {
        val malformed = assertThrows(IllegalStateException::class.java) {
            ModuleLawTable.parse(":core ->\n:http :core\n", "table")
        }
        assertTrue(malformed.message.orEmpty().startsWith("table:2:"), malformed.message)
        val repeated = assertThrows(IllegalStateException::class.java) {
            ModuleLawTable.parse(":core ->\n:core -> :http\n", "table")
        }
        assertTrue(repeated.message.orEmpty().contains("table:2: :core is listed twice"), repeated.message)
    }
}
