package splice.core.topology

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalProviderTest {
    private fun provider(url: String, dialect: Dialect = Dialect.OPENAI_CHAT, local: Boolean? = null) =
        ProviderConfig(dialect = dialect, baseUrl = url, auth = AuthConfig(kind = "none"), local = local)

    @Test
    fun `an openai-chat provider on a loopback address is local unless the operator says otherwise`() {
        assertTrue(provider("http://localhost:11434/v1").isLocal)
        assertTrue(provider("http://127.0.0.1:1234/v1").isLocal)
        assertFalse(provider("https://openrouter.ai/api/v1").isLocal)
        assertFalse(provider("http://localhost:8000/v1", dialect = Dialect.OPENAI_RESPONSES).isLocal)
        assertFalse(provider("http://localhost:11434/v1", local = false).isLocal)
        assertTrue(provider("http://gpu-box.lan:8000/v1", local = true).isLocal)
        assertFalse(LocalProviderRule().isLoopback("not a url"))
    }
}
