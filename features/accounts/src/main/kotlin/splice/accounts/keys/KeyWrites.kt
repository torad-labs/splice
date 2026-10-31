// NEW: V4-220 item 3 — the key routes' write half, split from KeyRoutes.kt (concentration): a name and
// a value validated, handed to KeyStore, and what came of it named. It answers no HTTP and reads no
// head. The value goes to KeyStore.write and nowhere else: no refusal quotes it, and the store's own
// refusals are texts it builds from the name alone.
package splice.accounts.keys

import io.ktor.http.HttpStatusCode
import splice.core.config.KeyStore
import splice.core.config.envNameRegex
import splice.core.util.Cancellables
import splice.core.util.LogSafe
import splice.core.util.SafeFailureText

/** What a write came to: applied (then re-read for the answer), or refused with its text and status. */
internal sealed class KeyOutcome {
    class Applied(val name: String, val verb: String) : KeyOutcome()

    class Refused(val text: String, val status: HttpStatusCode) : KeyOutcome()
}

internal class KeyWrites(private val keys: KeyStore) {

    /** PUT: a valid name and a one-line, non-blank value replace the stored key. */
    fun stored(name: String, value: String?): KeyOutcome {
        val invalid = invalidName(name) ?: when {
            value.isNullOrBlank() -> "the body must carry a non-empty '$VALUE_FIELD'"
            '\n' in value || '\r' in value -> "the key for $name contains a line break; the key store cannot hold it"
            else -> null
        }
        if (invalid != null || value == null) return KeyOutcome.Refused(invalid.orEmpty(), HttpStatusCode.BadRequest)
        val failure = Cancellables.runCatchingCleanup { keys.write(name, value) }.exceptionOrNull()
            ?: return KeyOutcome.Applied(name, "stored")
        return KeyOutcome.Refused(refusalText(failure), HttpStatusCode.Conflict)
    }

    /** DELETE: a stored key is removed; one the store does not hold is a 404, not a quiet success. */
    fun removed(name: String): KeyOutcome {
        invalidName(name)?.let { return KeyOutcome.Refused(it, HttpStatusCode.BadRequest) }
        val attempt = Cancellables.runCatchingCleanup { keys.unset(name) }
        val failure = attempt.exceptionOrNull()
        return when {
            failure != null -> KeyOutcome.Refused(refusalText(failure), HttpStatusCode.Conflict)
            attempt.getOrThrow() -> KeyOutcome.Applied(name, "removed")
            else -> KeyOutcome.Refused("$name was not stored", HttpStatusCode.NotFound)
        }
    }

    private fun invalidName(name: String): String? =
        if (name.matches(envNameRegex)) {
            null
        } else {
            "'${LogSafe.str(name)}' is not an environment variable name (want $envNameRegex)"
        }

    // The store REFUSES with check() — an IllegalStateException (unreadable, locked by a peer) that
    // runCatchingCancellable lets escape by design, so a refusal became the guard's 500 with its text
    // withheld. runCatchingCleanup's set is exactly this one: I/O, (de)serialization, IllegalArgument
    // and IllegalState, with cancellation still propagating. Those check() texts are built from the name
    // and SafeFailureText alone, so they are shown as written; anything else is rendered through
    // SafeFailureText, which withholds a message that could quote bytes.
    private fun refusalText(failure: Throwable): String =
        (failure as? IllegalStateException)?.message ?: SafeFailureText.render(failure)
}

/** The PUT body's one field. */
internal const val VALUE_FIELD = "value"
