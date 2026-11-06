package splice.observability.profile

import splice.core.util.PerfWorkBudget
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Both capture backends use the same bounded copy, even when their native chunk limits differ. */
internal object ProfileCopy {
    fun copy(input: InputStream, destination: Path) {
        val lease = PerfWorkBudget.acquire(
            ProfileLimits.COPY_BUFFER_BYTES.toLong(),
        ) ?: throw IOException("Profile copy budget unavailable")
        lease.use {
            Files.newOutputStream(
                destination,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { output ->
                transfer(input, output)
            }
        }
    }

    private fun transfer(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(ProfileLimits.COPY_BUFFER_BYTES)
        var total = 0L
        var read = input.read(buffer)
        while (read >= 0) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Profile copy cancelled")
            total += read
            if (total > ProfileLimits.ARTIFACT_BYTES) throw ProfileOversized()
            output.write(buffer, 0, read)
            read = input.read(buffer)
        }
    }
}
