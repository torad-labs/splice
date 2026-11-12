// NEW: a renewed pair whose write failed is never dropped by a reader that judged an older one. The endpoint has
// already spent the refresh token the pair replaced, so losing it signs the login out.
package splice.upstream.credentials

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.CredentialFileDigest
import splice.core.auth.CredentialFileIdentity
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class RetainedCredentialWriteTest {
    @TempDir
    lateinit var folder: Path

    @Test
    fun `a write that fails while a reader judges an older waiting pair keeps the renewed pair`() {
        val file = folder.resolve("auth.json")
        Files.writeString(file, "on-disk")
        // Runs once, inside the next read of the file's state: the moment a reader holds the waiting document it is
        // about to judge, and has not judged it yet.
        var meanwhile: (() -> Unit)? = null
        val retained = RetainedCredentialWrite(
            file,
            "test",
            LogSink { },
            CredentialFileState { path ->
                meanwhile?.also { meanwhile = null }?.invoke()
                CredentialFileIdentity(
                    Files.getLastModifiedTime(path).toMillis(),
                    Files.size(path),
                    CredentialFileDigest.of(path),
                )
            },
        )
        assertNotNull(refusedWrite { retained.write("renewed-1") }, "the first renewal's write fails")
        // A peer replaced the file since, so the first renewed pair no longer stands over it.
        Files.writeString(file, "peer")

        // While a reader judges renewed-1, the next renewal is granted and its write fails too.
        meanwhile = { assertNotNull(refusedWrite { retained.write("renewed-2") }) }
        val read = retained.read()

        assertEquals("renewed-2", read, "the renewed pair is served in the file's place")
        assertEquals("renewed-2", retained.read(), "and still waits to be written")
        retained.flush()
        assertEquals("renewed-2", Files.readString(file), "the next flush writes it")
    }

    @Test
    fun `a write that lands releases the pair it wrote`() {
        val file = folder.resolve("auth.json")
        Files.writeString(file, "on-disk")
        val retained = RetainedCredentialWrite(file, "test", LogSink { })
        refusedWrite { retained.write("renewed") }

        retained.flush()
        Files.writeString(file, "re-login")

        assertEquals("re-login", retained.read(), "nothing waits once the renewed pair is on disk")
    }

    /** Runs [write] with the folder read-only, so the atomic write's temporary file cannot be created. */
    private fun <T> refusedWrite(write: () -> T): T {
        val writable = Files.getPosixFilePermissions(folder)
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"))
        return try {
            write()
        } finally {
            Files.setPosixFilePermissions(folder, writable)
        }
    }
}
