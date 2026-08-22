// NEW: the codebase's best-effort-operation combinator. Config reads, symlink writes, frame parsing
// and auth-file loads are all "try, fall back if it fails" operations, and their real failure modes
// are I/O errors and malformed data — NOT arbitrary Throwables. Catching those concrete types (never
// a broad `Exception`/`Throwable`) is what keeps the code honest under detekt's TooGenericExceptionCaught
// AND structured concurrency: CancellationException is an IllegalStateException, which is deliberately
// NOT caught here, so a cancelled coroutine propagates instead of turning into a zombie stream.
// Both members were top-level until the 2026-08-16 style migration (HD-M8); `discard` was an
// extension on kotlin's own `Result`, a foreign receiver that cannot host a member, so the pair
// moved onto this named object (the migration's pattern 5) and the receiver became the first
// argument — `result.discard(why)` reads `Cancellables.discard(result, why)`. Same bodies, same
// names, same caught types; `return@runCatchingCancellable` labels are unaffected because a lambda's
// implicit label is the function's name, not its qualifier.
package splice.core.util

import kotlinx.serialization.SerializationException
import java.io.IOException

public object Cancellables {

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
     * The ONLY sanctioned way to drop a [Result] on the floor. Neither argument is read at runtime:
     * the Result is the thing being dropped (hence the parameter name — nothing here inspects it),
     * and [why] exists so the call site states the justification and the discard is
     * greppable/wall-checkable. Anything that cannot articulate a one-line reason should be handling
     * the failure instead (the swallow-into-null incidents of 2026-07-18 are what this fences off;
     * `-Xreturn-value-checker` flags the bare form).
     */
    public fun discard(ignored: Result<*>, why: String) {
        require(why.isNotBlank()) { "discard() requires a stated reason" }
    }
}
