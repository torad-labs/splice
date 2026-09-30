// V4-254: a status poll reads the same missing credential every few seconds; the journal gets the change, not the repetition.
package splice.oauth

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.RefreshCall
import splice.core.auth.RefreshableAuthProvider
import splice.core.util.LogSink
import splice.provider.codex.CodexAuthProvider
import splice.provider.grok.GrokAuthProvider
import splice.provider.kimi.KimiAuthProvider
import java.nio.file.Files
import java.nio.file.Path

class CredentialWarningOnceTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `a missing credential warns once per state change, not on every read`() = runTest {
        val paths = listOf(dir.resolve("codex-once.json"), dir.resolve("grok-once.json"), dir.resolve("kimi-once.json"))
        val logs = mutableListOf<String>()
        val log = LogSink { logs.add(it) }
        val providers = listOf<RefreshableAuthProvider>(
            CodexAuthProvider(paths[0], 0L, refreshCall = RefreshCall { error("unused") }, log = log),
            GrokAuthProvider(paths[1], refreshCall = RefreshCall { error("unused") }, authCacheMs = 30_000L, log = log),
            KimiAuthProvider(paths[2], refreshCall = RefreshCall { error("unused") }, authCacheMs = 30_000L, log = log),
        )
        val warnings = { logs.count { it.contains("invalid_grant latch check skipped") } }

        repeat(3) { providers.forEach { it.describe() } }
        assertEquals(providers.size, warnings(), "reads of the same missing credential warned again: $logs")

        // Present, then missing again: the credential changed state twice, so it is news twice.
        paths.forEach { Files.writeString(it, "{}") }
        providers.forEach { it.describe() }
        assertEquals(providers.size, warnings(), "a present credential warned: $logs")
        paths.forEach { Files.delete(it) }
        repeat(2) { providers.forEach { it.describe() } }
        assertEquals(providers.size * 2, warnings(), "missing again is a new state and warns once more: $logs")
    }
}
