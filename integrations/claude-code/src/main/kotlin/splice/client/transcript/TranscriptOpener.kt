// NEW: V4-427 — the positioned I/O seam for cached send and activity scans (V4-444), so tests count
// actual bytes read without the readers knowing they are being watched.
package splice.client.transcript

import java.io.InputStream
import java.nio.file.Path

/** Opens a transcript positioned at [offset], without reading its prefix. Tests can count the bytes read. */
public fun interface TranscriptOpener {
    public fun open(file: Path, offset: Long): InputStream
}

/** Collects only redacted hand-offs from a transcript line into the shared send index. */
internal fun interface SentTextCollector {
    operator fun invoke(bytes: ByteArray, found: MutableMap<String, String>)
}
