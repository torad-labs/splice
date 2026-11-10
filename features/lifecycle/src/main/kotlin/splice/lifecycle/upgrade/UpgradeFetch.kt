// NEW: v0.4.0 FEATURES.md §5 — the download seam `splice upgrade` fetches release assets through
// (https, or file:// for an acceptance fixture), and its JDK implementation. Split from
// UpgradeRelease.kt (concentration, 2026-09-13).
// 2026-11-10: a release asset streams to a part file beside its destination and lands only when its sha256 matches
// ([UpgradeFetch.download]); the network side is UpgradeTransfer.kt, bounded by stalls instead of a whole-request limit.
package splice.lifecycle.upgrade

import splice.core.util.Cancellables
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths

/** GET a URL (https or file://): the bytes, null when the asset is ABSENT (404, no such file), and
 *  [UpgradeFetchFailed] for everything else — a transport failure or a refusing status is not a
 *  missing asset and is reported as what it is (review 2026-09-14). */
internal fun interface UpgradeFetch {
    operator fun invoke(url: String): ByteArray?

    /** Fetches [url] into [target], which it lands at only when the bytes' sha256 is [expected]: [Downloaded.Absent]
     *  when there is no such asset, [Downloaded.Mismatch] when the bytes are not the published ones, and
     *  [UpgradeFetchFailed] as [invoke]. Whatever did not land is removed. This default reads [invoke]'s bytes, for a
     *  seam that serves a few; [JdkUpgradeFetch] streams, so a release jar is never held in memory. */
    fun download(url: String, target: Path, expected: String, progress: DownloadProgress): Downloaded {
        val bytes = invoke(url) ?: return Downloaded.Absent
        return PartFile(target).use { part ->
            part.write(bytes, bytes.size)
            part.land(expected)
        }
    }
}

/** What a download left: the asset at its target, no such asset, or bytes whose sha256 is [Mismatch.actual]. */
internal sealed class Downloaded {
    data object Landed : Downloaded()

    data object Absent : Downloaded()

    data class Mismatch(val actual: String) : Downloaded()
}

/** What a download tells the person waiting on it. */
internal interface DownloadProgress {
    /** [done] bytes are on disk, of [total] when the server said how many. */
    fun received(done: Long, total: Long?)

    /** One sentence about the download: a resume, a fresh start. */
    fun note(line: String)
}

/** The progress of a file nobody watches: the small files a fetch reads whole. */
internal class QuietDownload : DownloadProgress {
    override fun received(done: Long, total: Long?) = Unit

    override fun note(line: String) = Unit
}

/** [why] is authored here (a status class, an exception class), never response bytes. */
internal class UpgradeFetchFailed(val why: String) : IOException(why)

internal class JdkUpgradeFetch(
    private val pacing: DownloadPacing = DownloadPacing(),
    // NORMAL, not ALWAYS: a release redirect that stepped down from HTTPS to HTTP would carry the
    // jar AND the sums it is checked against over the same downgraded hop (review 2026-09-14).
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(pacing.connect)
        .build(),
) : UpgradeFetch {
    override fun invoke(url: String): ByteArray? {
        if (url.startsWith("file:")) return local(url)
        val sink = MemorySink()
        return if (HttpTransfer(client, pacing, url, sink, QuietDownload()).run()) sink.bytes() else null
    }

    override fun download(url: String, target: Path, expected: String, progress: DownloadProgress): Downloaded =
        PartFile(target).use { part ->
            val found = if (url.startsWith("file:")) {
                copied(url, part)
            } else {
                HttpTransfer(client, pacing, url, part, progress).run()
            }
            if (found) part.land(expected) else Downloaded.Absent
        }

    private fun local(url: String): ByteArray? =
        Cancellables.runCatchingCancellable { Files.readAllBytes(Paths.get(URI(url))) }.getOrElse { e ->
            if (e is NoSuchFileException) null else throw UpgradeFetchFailed("the file could not be read")
        }

    /** A file:// asset, streamed into [part] like a remote one; false when there is no such file. */
    private fun copied(url: String, part: PartFile): Boolean = try {
        Files.newInputStream(Paths.get(URI(url))).use { input ->
            val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
            var read = input.read(buffer)
            while (read >= 0) {
                part.write(buffer, read)
                read = input.read(buffer)
            }
        }
        true
    } catch (_: NoSuchFileException) {
        false
    }
}
