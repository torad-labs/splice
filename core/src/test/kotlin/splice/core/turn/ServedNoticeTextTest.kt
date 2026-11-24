// #791: the notice a server listener hands its head crosses one HTTP header, in any language the operator wrote.
package splice.core.turn

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServedNoticeTextTest {
    private val text = ServedNoticeText()

    @Test
    fun `any notice is sent as visible ASCII and read back as it was written`() {
        for (notice in listOf("Atualize o cliente — versão 0.9.3.", "更新してください", "Run `splice upgrade`.\nThen go on.")) {
            val sent = text.encode(notice)
            assertTrue(sent.all { it.code in 0x21..0x7E }, sent)
            assertEquals(notice, text.decode(sent))
        }
    }

    @Test
    fun `a plain value from an older listener reads as it is, and broken escapes read as none`() {
        assertEquals("This laptop runs splice 0.9.0.", text.decode("This laptop runs splice 0.9.0."))
        assertNull(text.decode("UTF-8''vers%C"))
        assertNull(text.decode("UTF-8''vers%ZZo"))
    }
}
