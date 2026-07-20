package splice.app.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import java.nio.file.Paths

class LoginCommandTest {

    @Test
    fun `oauth login writes to the provider configured auth file`() {
        val provider = ProviderConfig(
            dialect = Dialect.OPENAI_RESPONSES,
            baseUrl = "https://example.invalid",
            auth = AuthConfig("chatgpt-oauth", file = "/tmp/splice-custom-auth.json"),
        )
        assertEquals(Paths.get("/tmp/splice-custom-auth.json"), oauthAuthPath(provider, "~/.codex/auth.json"))
    }
}
