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
import java.nio.file.attribute.PosixFilePermissions

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

    // DR-57: the same access-indeterminate absence as MgmtKey (DR-56). The operator's key file sits
    // behind a symlink whose target parent loses read (a permissions blip); the old exists() pre-gate
    // read false there, so readKeyFile returned null WITHOUT logging — the failure you most want to
    // read (why did auth silently stop?) never reached the log. The direct read reaches the
    // AccessDenied and getOrElse logs it; NOFOLLOW is only the post-NoSuch dangling disambiguator.
    @Test
    fun `an inaccessible-target key-file symlink logs the read failure, not silent absence - DR-57`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val externalDir = Files.createDirectories(tmp.resolve("external"))
        val target = Files.writeString(externalDir.resolve("api.key"), "sk-from-file")
        val link = tmp.resolve("key").also { Files.createSymbolicLink(it, target) }
        Files.setPosixFilePermissions(externalDir, PosixFilePermissions.fromString("---------"))
        val logs = mutableListOf<String>()
        try {
            val p = ApiKeyAuthProvider(
                envVar = "OPENROUTER_API_KEY",
                keyFile = link,
                envReader = { null },
                keyStore = KeyStore(tmp.resolve("keys.toml")),
                log = logs::add,
            )
            assertNull(p.credentials(), "an unreadable key file is not a resolved credential")
            assertEquals(
                1,
                logs.size,
                "an inaccessible key file must log the read failure, not read as silent absence: $logs",
            )
            assertTrue(logs[0].contains("[api-key-auth] failed to read"), "names the failure: ${logs[0]}")
        } finally {
            Files.setPosixFilePermissions(externalDir, PosixFilePermissions.fromString("rwx------"))
        }
    }

    private fun loggingProvider(keyFile: Path, store: KeyStore, logs: MutableList<String>) = ApiKeyAuthProvider(
        envVar = "OPENROUTER_API_KEY",
        keyFile = keyFile,
        envReader = { null },
        keyStore = store,
        log = logs::add,
    )

    // DR-57 (codex class law): the key file sits DIRECTLY under a dir whose search bit is gone — no
    // symlink anywhere. Files.exists(file, NOFOLLOW) still reads false here (it cannot stat through
    // an untraversable parent), so even a NOFOLLOW pre-gate returns silent absence. Only a direct
    // read reaches the AccessDenied and logs it.
    @Test
    fun `an inaccessible-parent key file logs the read failure - DR-57`(@TempDir tmp: Path) = runBlocking {
        val externalDir = Files.createDirectories(tmp.resolve("external"))
        val file = Files.writeString(externalDir.resolve("api.key"), "sk-from-file")
        Files.setPosixFilePermissions(externalDir, PosixFilePermissions.fromString("---------"))
        val logs = mutableListOf<String>()
        try {
            val p = loggingProvider(file, KeyStore(tmp.resolve("keys.toml")), logs)
            assertNull(p.credentials())
            assertEquals(1, logs.size, "an untraversable parent must log, not read as absence: $logs")
            assertTrue(logs[0].contains("[api-key-auth] failed to read"), logs[0])
        } finally {
            Files.setPosixFilePermissions(externalDir, PosixFilePermissions.fromString("rwx------"))
        }
    }

    // DR-57 (codex class law): a DANGLING key-file symlink throws NoSuchFile on read, but the path
    // entry exists — the operator configured a link that broke, which is not "no key configured".
    // exists(NOFOLLOW) disambiguates the caught NoSuch; it is never a pre-gate.
    @Test
    fun `a dangling key-file symlink logs the read failure, not silent absence - DR-57`(@TempDir tmp: Path) =
        runBlocking {
            val link = tmp.resolve("key").also { Files.createSymbolicLink(it, tmp.resolve("never-created")) }
            val logs = mutableListOf<String>()
            val p = loggingProvider(link, KeyStore(tmp.resolve("keys.toml")), logs)
            assertNull(p.credentials())
            assertEquals(1, logs.size, "a dangling key link is present-but-broken, not quiet absence: $logs")
            assertTrue(logs[0].contains("[api-key-auth] failed to read"), logs[0])
        }

    // DR-57 companion (NEVER-BELOW-STATUS-QUO): a configured-but-never-created key file is the one
    // genuine absence — NoSuch AND no path entry — and must stay a QUIET no-key fallthrough.
    @Test
    fun `a configured but absent key file stays a quiet no-key`(@TempDir tmp: Path) = runBlocking {
        val logs = mutableListOf<String>()
        val p = loggingProvider(tmp.resolve("absent.key"), KeyStore(tmp.resolve("keys.toml")), logs)
        assertNull(p.credentials())
        assertTrue(logs.isEmpty(), "genuine absence must not warn: $logs")
    }
}

// DR-73 (invariant audit): the key-file read failure logged the raw parse throwable, whose
// "JSON input:" excerpt quotes the credential file's bytes into daemon.log + /mgmt/logs.
class ApiKeyDiagnosticsTest {

    @Test
    fun `diagnostics never quote key-file bytes from a malformed key file - DR-73`(@TempDir tmp: Path) = runBlocking {
        val sentinel = "sk-SENTINEL-KEYFILE"
        val file = tmp.resolve("key.json")
        Files.writeString(file, """{"api_key":"$sentinel""")
        val log = mutableListOf<String>()
        val p = ApiKeyAuthProvider(
            envVar = "OPENROUTER_API_KEY",
            keyFile = file,
            envReader = { null },
            keyStore = KeyStore(tmp.resolve("keys.toml")),
            log = splice.core.util.LogSink { log += it },
        )
        assertNull(p.credentials())
        val joined = log.joinToString("\n")
        assertTrue(!joined.contains(sentinel), "key bytes must never surface: $joined")
        assertTrue(log.any { it.contains("failed to read") }, "the read failure must log: $joined")
    }
}
