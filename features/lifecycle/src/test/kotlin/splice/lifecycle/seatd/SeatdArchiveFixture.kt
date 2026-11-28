package splice.lifecycle.seatd

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

internal class SeatdArchiveFixture(binary: ByteArray) {
    val license = "Stand-in license"
    val notices = "Stand-in third-party notices"
    private val regular = mapOf(
        "seatd-pipe" to binary,
        "LICENSE" to license.toByteArray(),
        "THIRD_PARTY_NOTICES.md" to notices.toByteArray(),
    )

    fun bytes(kind: String = "valid"): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(GZIPOutputStream(bytes)).use { tar ->
            for ((name, content) in files(kind)) {
                val entry = TarArchiveEntry(name, true).apply { size = content.size.toLong() }
                if (kind == "pax metadata") entry.addPaxHeader("comment", "unexpected extended header")
                tar.putArchiveEntry(entry)
                tar.write(content)
                tar.closeArchiveEntry()
            }
            special(kind)?.let {
                tar.putArchiveEntry(it)
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun files(kind: String): Map<String, ByteArray> = when (kind) {
        "path traversal" -> regular + ("../x" to byteArrayOf(1))
        "absolute path" -> regular + ("/x" to byteArrayOf(1))
        "extra file" -> regular + ("EXTRA" to byteArrayOf(1))
        "missing program" -> regular - "seatd-pipe"
        "symlink", "device" -> regular - "LICENSE"
        else -> regular
    }

    private fun special(kind: String): TarArchiveEntry? = when (kind) {
        "symlink" -> TarArchiveEntry("LICENSE", TarConstants.LF_SYMLINK).apply { linkName = "seatd-pipe" }
        "device" -> TarArchiveEntry("LICENSE", TarConstants.LF_CHR)
        else -> null
    }
}
