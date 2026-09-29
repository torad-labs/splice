// NEW: V4-427 — the one seam through which the send scan opens a transcript, so a test can count the bytes
// a call reads (a warm call reads none) without the reader knowing it is being watched.
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
