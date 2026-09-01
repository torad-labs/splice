// DR-172 gap (found 2026-09-01 by the verifier, confirmed against the code): persistIfSignedIn read
// access_token at the TOP LEVEL only, while LoginCodex and LoginGrok hand it the ON-DISK shape their
// providers read back — the token nested under "tokens" (CodexAuthJson / GrokAuthJson FIELD_TOKENS).
// So every successful codex and grok exchange was refused as "no access token — NOT signed in".
// DeviceLoginTokenlessTest's control arm used a synthetic FLAT body, which is why the gate stayed
// green over the outage. These arms drive the REAL provider builders through the same call both
// login flows make, so the shape can never drift out from under the check again.
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.LoginIo
import splice.provider.codex.CodexOAuth
import splice.provider.grok.GrokOAuth
import splice.provider.kimi.KimiOAuth
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class LoginPersistShapeTest {

    private fun persist(path: Path, authJson: String): Pair<Boolean, String> {
        val savedOut = System.out
        val out = ByteArrayOutputStream()
        return try {
            System.setOut(PrintStream(out, true))
            LoginIo().persistIfSignedIn(path, authJson) to out.toString()
        } finally {
            System.setOut(savedOut)
        }
    }

    @Test
    fun `the codex on-disk shape, token under tokens, is a sign-in - DR-172`(@TempDir tmp: Path) {
        val authPath = tmp.resolve("auth.json")
        val json = CodexOAuth().authJsonFromTokens(
            idToken = null,
            accessToken = "tok_codex",
            refreshToken = "r",
            apiKey = null,
            nowIso = "2026-09-01T00:00:00Z",
        ).toString()
        val (ok, printed) = persist(authPath, json)
        assertTrue(ok, "the real codex builder's output must count as signed in: $printed")
        assertTrue(Files.readString(authPath).contains("tok_codex"))
    }

    @Test
    fun `the grok on-disk shape, token under tokens, is a sign-in - DR-172`(@TempDir tmp: Path) {
        val authPath = tmp.resolve("auth.json")
        val json = GrokOAuth().grokAuthJsonFromTokenResponse(
            """{"access_token":"tok_grok","refresh_token":"r","expires_in":3600}""",
            fallbackRefresh = null,
            nowMs = 1_000L,
            nowIso = "2026-09-01T00:00:00Z",
        ).toString()
        val (ok, printed) = persist(authPath, json)
        assertTrue(ok, "the real grok builder's output must count as signed in: $printed")
        assertTrue(Files.readString(authPath).contains("tok_grok"))
    }

    @Test
    fun `the kimi shape is a sign-in - DR-172 control`(@TempDir tmp: Path) {
        val authPath = tmp.resolve("auth.json")
        val json = KimiOAuth().kimiAuthJsonFromTokenResponse(
            """{"access_token":"tok_kimi","refresh_token":"r","expires_in":3600}""",
            1_000L,
        ).toString()
        val (ok, printed) = persist(authPath, json)
        assertTrue(ok, printed)
        assertTrue(Files.readString(authPath).contains("tok_kimi"))
    }

    // JsonNull is a JsonPrimitive whose content is the string "null": a null token must not read as one.
    @Test
    fun `a JSON null token under tokens is not a sign-in - DR-172`(@TempDir tmp: Path) {
        val authPath = tmp.resolve("auth.json")
        val (ok, printed) = persist(authPath, """{"tokens":{"access_token":null,"refresh_token":"r"}}""")
        assertFalse(ok, printed)
        assertFalse(Files.exists(authPath), "nothing may be written for a null token: $printed")
    }
}
