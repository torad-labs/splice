// NEW: MuseMintPersistence write-guard and non-object root pins.
package splice.provider.muse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

class MuseMintPersistenceTest {

    @Test
    fun `a throwing write guard leaves the file byte-identical`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("muse.json")
        val original = """{"access_token":"account-access","api_key":"old"}"""
        Files.writeString(file, original)
        val ran = AtomicInteger()
        val ok = MuseMintPersistence().persistGranted(
            authPath = file,
            expectedAccessToken = "account-access",
            key = usageKey("new-key"),
            log = LogSink { },
            writeGuard = MuseMintWriteGuard {
                ran.incrementAndGet()
                throw IllegalArgumentException("guard")
            },
        )
        assertFalse(ok)
        assertEquals(1, ran.get())
        assertEquals(original, Files.readString(file))
    }

    @Test
    fun `a non-object root is logged and not overwritten`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("muse.json")
        Files.writeString(file, "[]")
        val logs = mutableListOf<String>()
        val ok = MuseMintPersistence().persistGranted(
            authPath = file,
            expectedAccessToken = "account-access",
            key = usageKey("new-key"),
            log = LogSink { logs += it },
        )
        assertFalse(ok)
        assertEquals("[]", Files.readString(file))
        assertTrue(
            logs.any { it.contains("credential root is not an object") && it.contains("minted key discarded") },
            "$logs",
        )
        assertTrue(logs.none { it.contains("credential file read failed") }, "$logs")
    }

    @Test
    fun `a cancellation from the write guard propagates and leaves the file byte-identical`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("muse.json")
        val original = """{"access_token":"account-access","api_key":"old"}"""
        Files.writeString(file, original)
        val ran = AtomicInteger()
        val thrown = org.junit.jupiter.api.assertThrows<CancellationException> {
            MuseMintPersistence().persistGranted(
                authPath = file,
                expectedAccessToken = "account-access",
                key = usageKey("new-key"),
                log = LogSink { },
                writeGuard = MuseMintWriteGuard {
                    ran.incrementAndGet()
                    throw CancellationException("guard")
                },
            )
        }
        assertEquals("guard", thrown.message)
        assertEquals(1, ran.get())
        assertEquals(original, Files.readString(file))
    }

    private fun usageKey(apiKey: String): MuseSubscriptionKey = MuseSubscriptionKey(
        apiKey = apiKey,
        fields = Json.parseToJsonElement(
            """{"api_key":"$apiKey","is_subs_active":true,"require_payment":false,
                "subs_usage":{"weekly":{"used_percent":1,"resets_at":1}}}""",
        ).jsonObject,
    )
}
