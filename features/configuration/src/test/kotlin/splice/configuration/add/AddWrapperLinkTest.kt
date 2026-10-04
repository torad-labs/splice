// NEW: V4-255 — a link failure reads as the install seam renders it. The seam that knows its refusals
// (app's LinkerInstall) names them; a seam that does not keeps SafeFailureText's withheld literal.
package splice.configuration.add

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.util.EnvReader

class AddWrapperLinkTest {

    private val env = EnvReader { null }

    @Test
    fun `a refusal the seam names is the reason the add prints`() {
        val install = object : WrapperInstall {
            override fun invoke(key: String, env: EnvReader): Boolean = error("the linker's sentence")

            override fun refusalText(failure: Throwable): String = "named: ${failure.message}"
        }
        assertEquals(AddLinked.NotLinked("named: the linker's sentence"), AddWrapperLink(install).link("fw", env))
    }

    @Test
    fun `a seam that names nothing leaves the failure withheld`() {
        val install = WrapperInstall { _, _ -> error("token=sk-quoted-from-a-file") }
        assertEquals(
            AddLinked.NotLinked("failure (message withheld: it may quote file bytes)"),
            AddWrapperLink(install).link("fw", env),
        )
    }
}
