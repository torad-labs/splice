// NEW: V4-298 — a sign-in is a credential write, and the primary file it writes is often the vendor
// CLI's own (auth.file = ~/.codex/auth.json and the like). LoginIo wrote the token body whole, so every
// field splice does not write was dropped: the SH-10 class the provider refreshes already merge against.
package splice.oauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.TerminalOutput
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class LoginIoTest {

    @Test
    fun `a sign-in keeps the fields a vendor CLI stores and replaces the tokens whole - V4-298`(@TempDir tmp: Path) {
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
    fun `a primary file that does not parse is said and replaced by the sign-in - V4-298`(@TempDir tmp: Path) {
        val primary = tmp.resolve("auth.json")
        Files.writeString(primary, "{ not json")

        val (signedIn, out) = signIn(primary, """{"access_token":"new"}""")

        assertTrue(signedIn, out)
        assertTrue("$primary could not be read" in out && "replaces it whole" in out, out)
        assertEquals(JsonObject(mapOf("access_token" to Json.parseToJsonElement("\"new\""))), read(primary))
    }

    private fun signIn(primary: Path, authJson: String): Pair<Boolean, String> {
        val out = StringBuilder()
        return LoginIo(TerminalOutput { out.appendLine(it) }).persistIfSignedIn(primary, authJson) to out.toString()
    }

    private fun read(path: Path) = Json.parseToJsonElement(Files.readString(path)).jsonObject
}
