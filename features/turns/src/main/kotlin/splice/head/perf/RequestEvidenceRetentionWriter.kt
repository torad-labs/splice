package splice.head.perf

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import splice.core.util.FileTightening
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** File-lane publication remembers only successful durable writes and verifies the file before reusing one. */
internal class RequestEvidenceRetentionWriter(private val file: Path, private val maxBytes: Long) {
    private var published: RequestEvidenceRetention? = null

    fun write(snapshot: RequestEvidenceRetention) {
        val encoded = Json.encodeToString(snapshot)
        // Expiry changes still reach disk before segment deletion. Ordinary appends only verify the manifest.
        if (snapshot == published && unchanged(encoded)) return
        SecureFile.writeAtomic0600(file, encoded)
        published = snapshot
    }

    private fun unchanged(encoded: String): Boolean = try {
        Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) &&
            Files.size(file) <= maxBytes &&
            Files.readString(file) == encoded &&
            SecureFile.ownerOnlyFile(file) !is FileTightening.Open
    } catch (_: IOException) {
        false
    }
}
