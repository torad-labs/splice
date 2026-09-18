// NEW: public SSE transport failures. Split from SseReader.kt so the
// line/event walk is not billed for the two error types (concentration
// HIGH, 2026-08-19). JVM names stay splice.spi.Sse* — same-package move.
package splice.spi

import java.io.IOException

// V4-114 disposition: an upstream protocol violation of our own safety limit, thrown mid-walk by
// SseReader.kt:59 / SseDecode.kt:106 with no return channel to ride. TurnFailures.kt:35 /
// TurnConnEnd.kt:30 log it and emit an API_ERROR terminal; no caller continues the round.
// ast-grep-ignore: kt-no-exception-as-outcome -- 2026-09-17: an upstream frame violation, never a branch
public class SseFrameTooLargeException(kind: String, limit: Int) :
    RuntimeException("$kind exceeds the $limit-character safety limit")

/** UP-005: the exhaustion end of [DecodeScratch.readChunk]'s spurious-wakeup bound — a half-open
 *  channel that kept CLAIMING content ([io.ktor.utils.io.ByteReadChannel.awaitContent] = true)
 *  without ever DELIVERING any byte, [limit] times running. Previously indistinguishable from a
 *  genuine clean end of stream (both returned -1); an IOException instead so every existing
 *  "stream read error" handler (the translators' catch lists, TurnDriver's tear-aware reissue)
 *  classifies it through the SAME honest-failure path other transport errors already use — never
 *  a crash, just no longer silently identical to EOF. The bound itself (MAX_SPURIOUS_WAKEUPS) is
 *  unchanged. */
public class SseSpuriousWakeupException(limit: Int) : IOException(
    "SSE channel claimed content $limit times running without delivering a byte — treating the stream as torn",
)
