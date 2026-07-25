// NEW: ApiKeyAuthProvider precedence — env > auth.file > KeyStore — and the launch-time peek.
// The KeyStore fallback is what makes `splice key set` / `<head> login` / token capture durable:
// a daemon started WITHOUT the env var still authenticates, from any shell, after one store.
package openai

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.KeyStore
import splice.provider.openai.ApiKeyAuthProvider
import java.nio.file.Files
import java.nio.file.Path

class ApiKeyAuthProviderTest {

    private fun provider(
        env: Map<String, String> = emptyMap(),
        keyFile: Path? = null,
        store: KeyStore,
    ) = ApiKeyAuthProvider(
        envVar = "OPENROUTER_API_KEY",
        keyFile = keyFile,
        envReader = { k -> env[k] },
        keyStore = store,
    )

    @Test
    fun `env wins over file and store`(@TempDir tmp: Path) = runBlocking {
        val store = KeyStore(tmp.resolve("keys.toml")).apply { write("OPENROUTER_API_KEY", "sk-store") }
        val file = tmp.resolve("key").also { Files.writeString(it, "sk-file") }
        val p = provider(mapOf("OPENROUTER_API_KEY" to "sk-env"), file, store)
        assertEquals("sk-env", (p.credentials() as splice.core.auth.Credentials.ApiKey).key)
    }

    @Test
    fun `auth file beats store when env is absent`(@TempDir tmp: Path) = runBlocking {
        val store = KeyStore(tmp.resolve("keys.toml")).apply { write("OPENROUTER_API_KEY", "sk-store") }
        val file = tmp.resolve("key").also { Files.writeString(it, "sk-file") }
        val p = provider(emptyMap(), file, store)
        assertEquals("sk-file", (p.credentials() as splice.core.auth.Credentials.ApiKey).key)
    }

    @Test
    fun `store is the durable fallback when env and file are absent`(@TempDir tmp: Path) = runBlocking {
        val store = KeyStore(tmp.resolve("keys.toml")).apply { write("OPENROUTER_API_KEY", "sk-store") }
        val p = provider(emptyMap(), null, store)
        assertEquals("sk-store", (p.credentials() as splice.core.auth.Credentials.ApiKey).key)
        assertTrue(p.hasKeyNow())
    }

    @Test
    fun `blank env value falls through to the store`(@TempDir tmp: Path) = runBlocking {
        val store = KeyStore(tmp.resolve("keys.toml")).apply { write("OPENROUTER_API_KEY", "sk-store") }
        val p = provider(mapOf("OPENROUTER_API_KEY" to ""), null, store)
        assertEquals("sk-store", (p.credentials() as splice.core.auth.Credentials.ApiKey).key)
    }

    @Test
    fun `nothing configured reads as absent`(@TempDir tmp: Path) = runBlocking {
        val p = provider(emptyMap(), null, KeyStore(tmp.resolve("keys.toml")))
        assertNull(p.credentials())
        assertFalse(p.hasKeyNow())
        assertFalse(p.describe().present)
    }

    @Test
    fun `store is re-read per call so a later key set lands without restart`(@TempDir tmp: Path) = runBlocking {
        val store = KeyStore(tmp.resolve("keys.toml"))
        val p = provider(emptyMap(), null, store)
        assertNull(p.credentials())
        store.write("OPENROUTER_API_KEY", "sk-late")
        assertEquals("sk-late", (p.credentials() as splice.core.auth.Credentials.ApiKey).key)
    }
}
