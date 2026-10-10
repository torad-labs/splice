// NEW: Oct 10, 2026 — the first account of an OAuth command is renamed and removed like the accounts added after it:
// a rename stores a name beside the pool and never touches the credential, a remove deletes splice's own file, and a
// file the provider config shares with the vendor's CLI keeps its refusal.
package splice.oauth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.core.config.UserHome
import splice.core.topology.AuthKind
import java.nio.file.Files
import java.nio.file.Path

class OAuthPrimaryAccountTest {
    private val kind = AuthKind.ChatgptOAuth
    private val first = OAuthPrimaryAccount()

    /** splice's own first file for [kind] under [home]: the default the provider config falls back to. */
    private fun own(home: Path): Path = home.resolve(".config/splice/auth/codex.json").also {
        Files.createDirectories(it.parent)
        Files.writeString(it, """{"access_token":"secret"}""")
    }

    @Test
    fun `a rename names the first account and leaves its credential byte for byte`(@TempDir home: Path) {
        UserHome.within(home) {
            val file = own(home)
            assertNull(first.name(kind, file))
            first.rename(kind, file, "work")
            assertEquals("work", first.name(kind, file))
            assertEquals("""{"access_token":"secret"}""", Files.readString(file))
        }
    }

    @Test
    fun `a rename to an added account's name or an unsafe name is refused`(@TempDir home: Path) {
        UserHome.within(home) {
            val file = own(home)
            val pool = OAuthAccountFiles().poolDir(kind, file)
            Files.createDirectories(pool)
            Files.writeString(pool.resolve("backup.json"), "{}")
            assertThrows<OAuthAccountRefused> { first.rename(kind, file, "backup") }
            assertThrows<OAuthAccountRefused> { first.rename(kind, file, "../x") }
            assertNull(first.name(kind, file))
        }
    }

    @Test
    fun `removing splice's own first account deletes its file and its name`(@TempDir home: Path) {
        UserHome.within(home) {
            val file = own(home)
            first.rename(kind, file, "work")
            assertFalse(first.shared(kind, file))
            assertTrue(first.remove(kind, file))
            assertFalse(Files.exists(file))
            assertNull(first.name(kind, file))
        }
    }

    @Test
    fun `a first file the config shares with the vendor's CLI is refused and kept`(@TempDir home: Path) {
        UserHome.within(home) {
            val shared = home.resolve(".codex/auth.json")
            Files.createDirectories(shared.parent)
            Files.writeString(shared, """{"tokens":{}}""")
            assertTrue(first.shared(kind, shared))
            assertThrows<OAuthAccountRefused> { first.remove(kind, shared) }
            assertEquals("""{"tokens":{}}""", Files.readString(shared))
            first.rename(kind, shared, "personal")
            assertEquals("personal", first.name(kind, shared), "a shared first account can still be named")
            assertEquals("""{"tokens":{}}""", Files.readString(shared))
        }
    }
}
