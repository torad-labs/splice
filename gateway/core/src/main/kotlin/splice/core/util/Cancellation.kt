// NEW: the codebase's best-effort-operation combinator. Config reads, symlink writes, frame parsing
// and auth-file loads are all "try, fall back if it fails" operations, and their real failure modes
// are I/O errors and malformed data — NOT arbitrary Throwables. Catching those concrete types (never
// a broad `Exception`/`Throwable`) is what keeps the code honest under detekt's TooGenericExceptionCaught
// AND structured concurrency: CancellationException is an IllegalStateException, which is deliberately
// NOT caught here, so a cancelled coroutine propagates instead of turning into a zombie stream.
package splice.core.util

import kotlinx.serialization.SerializationException
import java.io.IOException

/**
 * Run [block] as a best-effort local operation, capturing its expected failure modes — I/O and
 * (de)serialization — as [Result]. Anything else (including coroutine cancellation) propagates.
 * Compose at the call site with `.getOrNull()` / `.getOrDefault(x)` / `.getOrElse { e -> … }`.
 */
public inline fun <R> runCatchingCancellable(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (e: IOException) {
        Result.failure(e)
    } catch (e: SerializationException) {
        Result.failure(e)
    } catch (e: IllegalArgumentException) {
        Result.failure(e)
    }

/**
 * The ONLY sanctioned way to drop a [Result] on the floor. [why] is not read at runtime — it exists
 * so the call site states the justification and the discard is greppable/wall-checkable. Anything
 * that cannot articulate a one-line reason should be handling the failure instead (the
 * swallow-into-null incidents of 2026-07-18 are what this fences off; `-Xreturn-value-checker`
 * flags the bare form).
 */
public fun Result<*>.discard(why: String) {
    require(why.isNotBlank()) { "discard() requires a stated reason" }
}
