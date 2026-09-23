// NEW: V4-132 — DELETE and PATCH /api/auth/{head}/accounts/{label} (FEATURES.md §6) reach
// OAuthAccountFiles.remove/relabel. These tests exercise the filesystem contract directly: the
// primary is refused for both, a rename re-embeds splice_account_label so validatedLabel's
// filename/label-match invariant survives the move, and a retained quota file moves with its
// credential.
package splice.oauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.AuthKind
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path

class OAuthAccountFilesMutationTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val kind = AuthKind.ChatgptOAuth

    private fun credential(label: String): JsonObject = buildJsonObject {
        put("access_token", JsonPrimitive("secret-$label"))
        put("splice_auth_kind", JsonPrimitive(kind.wire))
        put("splice_account_label", JsonPrimitive(label))
    }

    private fun write(dir: Path, label: String, quota: Boolean = false) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("$label.json"), json.encodeToString(JsonObject.serializer(), credential(label)))
        if (quota) Files.writeString(dir.resolve("$label-quota.json"), "{}")
    }

    @Test
    fun `remove refuses the primary label`(@TempDir tmp: Path) {
        val files = OAuthAccountFiles()
        val primary = tmp.resolve("chatgpt.json")

        val thrown = assertThrows<OAuthAccountRefused> { files.remove(kind, primary, "primary") }
        assertTrue(thrown.reason.contains("primary account cannot be removed"))
    }

    @Test
    fun `remove deletes an existing labeled credential and its retained quota, reporting true`(@TempDir tmp: Path) {
        val files = OAuthAccountFiles()
        val primary = tmp.resolve("chatgpt.json")
        val dir = files.poolDir(kind, primary)
        write(dir, "plus-a", quota = true)

        val removed = files.remove(kind, primary, "plus-a")

        assertTrue(removed)
        assertFalse(Files.exists(dir.resolve("plus-a.json")))
        assertFalse(Files.exists(dir.resolve("plus-a-quota.json")))
    }

    @Test
    fun `remove of a label with no credential reports false, never throws`(@TempDir tmp: Path) {
        val files = OAuthAccountFiles()
        val primary = tmp.resolve("chatgpt.json")

        assertFalse(files.remove(kind, primary, "never-logged-in"))
    }

    @Test
    fun `relabel refuses the primary label`(@TempDir tmp: Path) {
        val files = OAuthAccountFiles()
        val primary = tmp.resolve("chatgpt.json")

        val thrown = assertThrows<OAuthAccountRefused> { files.relabel(kind, primary, "primary", "new-name") }
        assertTrue(thrown.reason.contains("primary account cannot be relabeled"))
    }

    @Test
    fun `relabel refuses an unknown label`(@TempDir tmp: Path) {
        val files = OAuthAccountFiles()
        val primary = tmp.resolve("chatgpt.json")

        val thrown = assertThrows<OAuthAccountRefused> { files.relabel(kind, primary, "plus-a", "plus-b") }
        assertTrue(thrown.reason.contains("no OAuth account labeled 'plus-a'"))
    }

    @Test
    fun `relabel refuses a collision with an already-labeled destination`(@TempDir tmp: Path) {
        val files = OAuthAccountFiles()
        val primary = tmp.resolve("chatgpt.json")
        val dir = files.poolDir(kind, primary)
        write(dir, "plus-a")
        write(dir, "plus-b")

        val thrown = assertThrows<OAuthAccountRefused> { files.relabel(kind, primary, "plus-a", "plus-b") }
        assertTrue(thrown.reason.contains("already labeled 'plus-b'"))
    }

    @Test
    fun `relabel moves the credential and re-embeds the new label, passing validatedLabel's own check`(
        @TempDir tmp: Path,
    ) {
        val files = OAuthAccountFiles()
        val primary = tmp.resolve("chatgpt.json")
        val dir = files.poolDir(kind, primary)
        write(dir, "plus-a", quota = true)

        val destination = files.relabel(kind, primary, "plus-a", "plus-b")

        assertEquals(dir.resolve("plus-b.json"), destination)
        assertFalse(Files.exists(dir.resolve("plus-a.json")), "the old credential file must not remain")
        assertFalse(Files.exists(dir.resolve("plus-a-quota.json")), "the old quota file must move with it")
        assertTrue(Files.exists(dir.resolve("plus-b-quota.json")))
        val discovered = files.discover(kind, primary, LogSink {}).single { it.label == "plus-b" }
        assertTrue(discovered.credentialPresent)
    }

    @Test
    fun `relabel to the same label is a no-op move that still succeeds`(@TempDir tmp: Path) {
        val files = OAuthAccountFiles()
        val primary = tmp.resolve("chatgpt.json")
        val dir = files.poolDir(kind, primary)
        write(dir, "plus-a")

        val destination = files.relabel(kind, primary, "plus-a", "plus-a")

        assertEquals(dir.resolve("plus-a.json"), destination)
        assertTrue(Files.exists(dir.resolve("plus-a.json")))
    }
}
