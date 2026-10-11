// NEW: a ledger splice cannot read is kept, never overwritten (Marcos, Oct 11).
package splice.accounts.keys

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class KeyLedgerTest {
    private val garbage = "{ not a ledger \u0000 but the only record of two other keys"

    private fun ledgerAt(file: Path, logs: MutableList<String>) =
        KeyLedger(file, LogSink { logs += it }, WallClock { 1_700_000_000_000L })

    private fun asidesOf(dir: Path): List<Path> =
        Files.list(dir).use { names ->
            names.filter { it.fileName.toString().startsWith("key-ledger.json.unreadable-") }.toList()
        }

    @Test
    fun `an unreadable ledger is kept beside the new one, byte for byte, and a new ledger starts`(@TempDir dir: Path) {
        val file = dir.resolve("key-ledger.json")
        Files.writeString(file, garbage)
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        val logs = mutableListOf<String>()

        val history = ledgerAt(file, logs).observe("OPENAI_API_KEY", "fp-new")

        assertEquals(1_700_000_000L, history.current?.firstSeen)
        val aside = asidesOf(dir).single()
        assertEquals("key-ledger.json.unreadable-1700000000", aside.fileName.toString())
        assertEquals(garbage, Files.readString(aside))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(aside)))
        assertTrue(Files.readString(file).contains("fp-new"))
        assertTrue(logs.single().contains(aside.toString()))
        // A second read of the fresh ledger moves nothing more.
        ledgerAt(file, logs).observe("OPENAI_API_KEY", "fp-new")
        assertEquals(1, asidesOf(dir).size)
    }

    @Test
    fun `a ledger that cannot be moved aside is left untouched and new keys show no day`(@TempDir dir: Path) {
        val folder = Files.createDirectory(dir.resolve("config"))
        val file = folder.resolve("key-ledger.json")
        Files.writeString(file, garbage)
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-x------"))
        val logs = mutableListOf<String>()
        try {
            val history = ledgerAt(file, logs).observe("OPENAI_API_KEY", "fp-new")

            assertNotNull(history.current)
            assertNull(history.current?.firstSeen)
            assertEquals(garbage, Files.readString(file))
            assertTrue(asidesOf(folder).isEmpty())
            assertTrue(logs.any { it.contains("could not be moved aside") })
        } finally {
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"))
        }
    }
}
