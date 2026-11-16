// The ledger a server cannot write (docs/specs/server-mode.md, F5): it says why, keeps the row it could not write,
// and writes that row as soon as its file takes rows again, so a report read after a restart still counts it.
package splice.server.ledger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

internal class UsageLedgerTest {
    @TempDir lateinit var folder: Path

    private fun row(person: String) = LedgerRow(
        at = "2026-11-16T14:00:00Z",
        person = person,
        device = "spk_test",
        command = "claude-team",
        route = ROUTE_MESSAGES,
        status = 200,
        outcome = "ok",
        promptTokens = 1200,
        durationMs = 40,
    )

    private fun mode(path: Path, mode: String) {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
    }

    @Test
    fun `a row the file refuses is kept and written once the file takes rows again`() = runBlocking {
        val file = folder.resolve("ledger.jsonl")
        val ledger = UsageLedger(file, Dispatchers.IO)
        ledger.add(row("ann@example.com"))
        assertNull(ledger.unwritable())

        mode(file, "r--r--r--")
        mode(folder, "r-xr-xr-x")
        try {
            assertThrows<IOException> { runBlocking { ledger.add(row("bob@example.com")) } }
            assertNotNull(ledger.unwritable())
        } finally {
            mode(folder, "rwxr-xr-x")
            mode(file, "rw-r--r--")
        }

        assertNull(ledger.unwritable())
        val reread = UsageLedger(file, Dispatchers.IO).all().map { it.person }
        assertEquals(listOf("ann@example.com", "bob@example.com"), reread)
    }
}
