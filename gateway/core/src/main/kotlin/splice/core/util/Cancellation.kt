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
