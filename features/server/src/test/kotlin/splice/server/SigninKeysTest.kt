// A sign-in key's replacement on its device (docs/specs/server-mode.md, F2) outlives a restart: once the newer key
// was used, a daemon that starts again still refuses the older one. The rotation itself is driven over the listener in
// SigninRouteTest.
package splice.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

internal class SigninKeysTest {
    @TempDir lateinit var folder: Path

    private fun store() = ServerKeys(folder.resolve("keys.json"), Dispatchers.IO)

    @Test
    fun `the replacement outlives a restart, so a reread store still refuses the replaced key`() {
        val keys = store()
        val signedIn = { device: String ->
            runBlocking { keys.mint(KeyRequest("dev@team.example", source = SIGNIN_SOURCE, device = device)) }
        }
        val first = signedIn("laptop")
        val second = signedIn("laptop")
        runBlocking { keys.admit(second.reveal()) }

        val reread = store()
        val replaced = assertInstanceOf(KeyVerdict.Revoked::class.java, runBlocking { reread.admit(first.reveal()) })
        assertEquals(second.row.prefix, replaced.key.supersededBy)
        assertInstanceOf(KeyVerdict.Admitted::class.java, runBlocking { reread.admit(second.reveal()) })
    }
}
