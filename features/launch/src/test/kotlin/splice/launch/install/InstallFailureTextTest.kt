// NEW: V4-255 — the one rule every caller of the install verb shows a failure through: the linker's
// own sentence verbatim, anything else withheld, so a message that could quote file bytes never prints.
package splice.launch.install

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InstallFailureTextTest {

    @Test
    fun `a refusal the linker composed prints as it wrote it`() {
        val sentence = "launch shim not found at /x (run install.sh)"
        assertEquals(sentence, InstallFailureText.render(InstallRefused(sentence)))
    }

    @Test
    fun `any other IllegalStateException stays withheld`() {
        assertEquals(
            "failure (message withheld: it may quote file bytes)",
            InstallFailureText.render(IllegalStateException("token=sk-quoted-from-a-file")),
        )
    }
}
