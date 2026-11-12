// NEW: V4-114 (2026-10-08) — the upgrade steps answer with a value instead of throwing UpgradeRefused (kt-no-exception-as-outcome).
// What an upgrade step answers: its value, or the refusal that stops the upgrade before anything is activated. A refusal is an
// answer, not a failure: [Refused.reason] is text this command authored (a version, a path, a verdict), never bytes of a file it
// read, so it is printed as-is and not through SafeFailureText. It replaced an exception that every step threw and one place caught.
package splice.lifecycle.upgrade

internal sealed class Upgraded<out T> {
    class Ok<out T>(val value: T) : Upgraded<T>()

    class Refused(val reason: String) : Upgraded<Nothing>()

    /** Runs [next] on the value, or passes the refusal on untouched. */
    inline fun <R> then(next: (T) -> Upgraded<R>): Upgraded<R> = when (this) {
        is Ok -> next(value)
        is Refused -> this
    }
}
