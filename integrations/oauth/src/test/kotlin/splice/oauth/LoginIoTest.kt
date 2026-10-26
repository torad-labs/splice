// NEW: V4-298 — a sign-in is a credential write, and the primary file it writes is often the vendor
// CLI's own (auth.file = ~/.codex/auth.json and the like). LoginIo wrote the token body whole, so every
// field splice does not write was dropped: the SH-10 class the provider refreshes already merge against.
package splice.oauth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.Credentials
import splice.core.auth.RefreshAttempt
import splice.core.auth.RefreshCall
import splice.core.terminal.TerminalOutput
import splice.core.topology.AuthKind
import splice.provider.codex.RefreshedTokens
import splice.provider.codex.auth.CodexAuthProvider
import splice.provider.codex.auth.CodexOAuth
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class LoginIoTest {

    @Test
    fun `a sign-in keeps the fields a vendor CLI stores and replaces the tokens whole`(@TempDir tmp: Path) {
        val primary = tmp.resolve("auth.json")
        Files.writeString(
            primary,
            """{"OPENAI_API_KEY":"sk-foreign","tokens":{"access_token":"old","id_token":"old-identity"},""" +
                """"last_refresh":"then"}""",
        )

        val body = """{"tokens":{"access_token":"new","refresh_token":"r"},"last_refresh":"now"}"""
        val (signedIn, _) = signIn(primary, body)

        val onDisk = read(primary)
        assertTrue(signedIn)
        assertEquals("sk-foreign", onDisk["OPENAI_API_KEY"]?.jsonPrimitive?.content, "the vendor's field: $onDisk")
        assertEquals(
            Json.parseToJsonElement("""{"access_token":"new","refresh_token":"r"}"""),
            onDisk["tokens"],
            "the new identity's tokens replace the old ones whole, so no old id_token rides along",
        )
        assertEquals("now", onDisk["last_refresh"]?.jsonPrimitive?.content)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(primary)))
    }

    @Test
    fun `a primary file that does not parse is said and replaced by the sign-in`(@TempDir tmp: Path) {
        val primary = tmp.resolve("auth.json")
        Files.writeString(primary, "{ not json")

        val (signedIn, out) = signIn(primary, """{"access_token":"new"}""")

        assertTrue(signedIn, out)
        assertTrue("$primary could not be read" in out && "replaces it whole" in out, out)
        assertEquals(JsonObject(mapOf("access_token" to Json.parseToJsonElement("\"new\""))), read(primary))
    }

    // PR #247 round 6, finding 4: the sign-in wrote the primary file outside the lock the daemon's refresh holds, so
    // a refresh of the old account granted during the sign-in wrote that account back after "signed in" was printed.
    @Test
    fun `a sign-in landing while the old account's refresh posts is what the head serves after both`(
        @TempDir tmp: Path,
    ) = runTest {
        val primary = tmp.resolve("codex.json")
        Files.writeString(primary, """{"tokens":{"access_token":"old-access","refresh_token":"old-refresh"}}""")
        val posting = CompletableDeferred<Unit>()
        val granted = CompletableDeferred<Unit>()
        val head = CodexAuthProvider(
            primary,
            authCacheMs = 0L,
            refreshCall = RefreshCall {
                posting.complete(Unit)
                granted.await()
                RefreshAttempt.Granted(RefreshedTokens("old-access-2", "old-refresh-2", idToken = null))
            },
        )
        val refreshing = async { head.refresh() }
        posting.await()

        val signIn = CodexOAuth().authJsonFromTokens(
            idToken = null,
            accessToken = "new-access",
            refreshToken = "new-refresh",
            apiKey = null,
            nowIso = "2026-10-26T00:00:00Z",
        )
        val account = OAuthAccountFiles().loginAccount(AuthKind.ChatgptOAuth, primary, requestedLabel = null)
        val signedIn = async(start = CoroutineStart.UNDISPATCHED) {
            LoginIo(TerminalOutput {}).persistIfSignedIn(primary, signIn.toString(), account)
        }
        granted.complete(Unit)
        refreshing.await()

        assertTrue(signedIn.await())
        assertEquals("new-access", (head.credentials() as? Credentials.Bearer)?.token, "the head serves the sign-in")
        assertEquals(signIn["tokens"], read(primary)["tokens"], "the file holds the sign-in's tokens, refresh too")
    }

    // The lock file sits beside the credential, so a first sign-in makes the folder before it locks.
    @Test
    fun `a first sign-in into a folder that does not exist yet still signs in`(@TempDir tmp: Path) {
        val primary = tmp.resolve("auth").resolve("codex.json")

        val (signedIn, out) = signIn(primary, """{"access_token":"new"}""")

        assertTrue(signedIn, out)
        assertEquals("new", read(primary)["access_token"]?.jsonPrimitive?.content)
    }

    private fun signIn(primary: Path, authJson: String): Pair<Boolean, String> = runBlocking {
        val out = StringBuilder()
        LoginIo(TerminalOutput { out.appendLine(it) }).persistIfSignedIn(primary, authJson) to out.toString()
    }

    private fun read(path: Path) = Json.parseToJsonElement(Files.readString(path)).jsonObject
}
