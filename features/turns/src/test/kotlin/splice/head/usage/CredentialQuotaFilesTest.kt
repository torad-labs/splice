// NEW: a command-local credential gets only its own timestamped successful quota observations.
package splice.head.usage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.CredentialKey
import splice.core.usage.ModelQuota
import splice.core.usage.QuotaJson
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path

class CredentialQuotaFilesTest {
    @TempDir
    lateinit var directory: Path

    private val first = requireNotNull(CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer first")))
    private val second = requireNotNull(CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer second")))

    private fun snapshot(used: Double, at: Long) = QuotaSnapshot(
        fiveHour = QuotaWindow(used, 20_000L, 18_000L),
        sevenDay = QuotaWindow(used + 1, 600_000L, 604_800L),
        updatedAt = at,
    )

    @Test
    fun `the head aggregate never substitutes for either command's own observation`() {
        val base = directory.resolve("native-quota.json")
        Files.writeString(base, QuotaJson().encode(snapshot(99.0, 1_000L)))
        val files = CredentialQuotaFiles(base, LogSink {})
        assertNull(files.read(first))
        assertNull(files.read(second))
        files.observed(first, snapshot(12.0, 1_000L))
        files.observed(second, snapshot(85.0, 2_000L))
        val reopened = CredentialQuotaFiles(base, LogSink {})
        assertEquals(snapshot(12.0, 1_000L), reopened.read(first))
        assertEquals(snapshot(85.0, 2_000L), reopened.read(second))
        assertEquals(snapshot(99.0, 1_000L), QuotaJson().decode(Files.readString(base)))
    }

    @Test
    fun `a turn's header reading keeps the model weeks the usage probe stored for the same week`() {
        val files = CredentialQuotaFiles(directory.resolve("native-quota.json"), LogSink {})
        val fable = listOf(ModelQuota("Fable", 40.0, 600_000L))
        files.observed(first, snapshot(10.0, 1_000L).copy(models = fable))
        files.observed(first, snapshot(11.0, 2_000L))
        assertEquals(snapshot(11.0, 2_000L).copy(models = fable), files.read(first))
    }

    @Test
    fun `latest timestamp wins without retaining a process-wide credential cache`() {
        val files = CredentialQuotaFiles(directory.resolve("native-quota.json"), LogSink {})
        files.observed(first, snapshot(10.0, 2_000L))
        files.observed(first, snapshot(80.0, 1_000L))
        assertEquals(snapshot(10.0, 2_000L), files.read(first))
        files.observed(first, snapshot(20.0, 3_000L))
        assertEquals(snapshot(20.0, 3_000L), files.read(first))
        assertThrows<IllegalArgumentException> { files.read("../other") }
    }

    @Test
    fun `corruption stays unknown without printing the private key or file bytes`() {
        val logs = mutableListOf<String>()
        val base = directory.resolve("native-quota.json")
        val file = directory.resolve("native-quota-$first.json")
        Files.writeString(file, "embedded-sensitive-bytes")
        val files = CredentialQuotaFiles(base, LogSink(logs::add))
        assertNull(files.read(first))
        assertFalse(logs.isEmpty())
        assertFalse(logs.any { it.contains(first) || it.contains("embedded-sensitive-bytes") })
    }
}
