// NEW: V4-220 item 3 (2026-09-25) — the console's front door to splice's key store, as `splice key
// set|list|unset` is the CLI's (KeyCommand). GET /api/keys names every key a head reads or the store
// holds, never a value; PUT /api/keys/{ENV} replaces one; DELETE /api/keys/{ENV} removes one.
//
// Every answer is the daemon's applied state, re-read after the write: whether the store holds the
// name, and for each head that reads it which link of its read chain supplies the key now
// (ApiKeyAuthProvider's `key_source`). A key stored while the daemon's environment or a key file sets
// the same name is shadowed, and the answer says `environment` or `file` for that head instead of
// letting the console read a stored-but-unused key as applied.
//
// THE VALUE GOES ONE WAY. It is read from the body, handed to KeyStore.write, and never logged,
// echoed, masked into a reply or put in a refusal: a replace-only route that logged its body would
// leak the secret anyway. The control line names the variable only.
package splice.accounts.keys

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.accounts.AccountHead
import splice.accounts.AccountReplies
import splice.core.config.KeyStore
import splice.core.config.envNameRegex
import splice.core.util.Cancellables
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.http.JsonBody

/** The daemon's ONE key store, read at CALL time: the console wiring assigns it after the server is
 *  constructed, and null answers every key route with a named 503, never an empty list. */
public fun interface KeyStoreSource {
    public operator fun invoke(): KeyStore?
}

/** One head that reads a key, and which link of its read chain supplies it right now. */
private class KeyReader(val head: String, val source: String)

public class KeyRoutes(
    private val heads: Map<String, AccountHead>,
    private val store: KeyStoreSource,
    private val log: LogSink,
) {
    private val jsonBody = JsonBody()

    /** GET /api/keys: `{path, keys: [{name, stored, heads: [{head, source}]}]}`, sorted by name. */
    public suspend fun list(call: ApplicationCall) {
        val keys = store() ?: return AccountReplies.respondUnwired(call, KEYS_PORT)
        val readers = readers()
        val stored = keys.names()
        val body = buildJsonObject {
            put("path", keys.path.toString())
            putJsonArray("keys") {
                (stored + readers.keys).sorted().forEach { name ->
                    add(keyJson(name, name in stored, readers[name].orEmpty()))
                }
            }
        }
        AccountReplies.respond(call, body.toString())
    }

    /** PUT /api/keys/{ENV}, body `{"value": "<key>"}`: replaces the stored key. 200 with the applied
     *  state; 400 for a bad name or value; 409 when the store refuses the write. */
    public suspend fun set(call: ApplicationCall) {
        val keys = store() ?: return AccountReplies.respondUnwired(call, KEYS_PORT)
        val value = AccountReplies.stringField(jsonBody.parse(call), VALUE_FIELD)
        answer(call, keys, written(keys, call.parameters[NAME_PARAM].orEmpty(), value))
    }

    /** DELETE /api/keys/{ENV}: removes the stored key. 200 with the applied state; 404 when the store
     *  did not hold it; 409 when the store refuses the write. */
    public suspend fun unset(call: ApplicationCall) {
        val keys = store() ?: return AccountReplies.respondUnwired(call, KEYS_PORT)
        answer(call, keys, removed(keys, call.parameters[NAME_PARAM].orEmpty()))
    }

    private fun written(keys: KeyStore, name: String, value: String?): KeyOutcome {
        val invalid = invalidName(name) ?: when {
            value.isNullOrBlank() -> "the body must carry a non-empty '$VALUE_FIELD'"
            '\n' in value || '\r' in value -> "the key for $name contains a line break; the key store cannot hold it"
            else -> null
        }
        if (invalid != null || value == null) return KeyOutcome.Refused(invalid.orEmpty(), HttpStatusCode.BadRequest)
        val failure = Cancellables.runCatchingCancellable { keys.write(name, value) }.exceptionOrNull()
            ?: return KeyOutcome.Applied(name, "stored")
        return KeyOutcome.Refused(refusalText(failure), HttpStatusCode.Conflict)
    }

    private fun removed(keys: KeyStore, name: String): KeyOutcome {
        invalidName(name)?.let { return KeyOutcome.Refused(it, HttpStatusCode.BadRequest) }
        val attempt = Cancellables.runCatchingCancellable { keys.unset(name) }
        val failure = attempt.exceptionOrNull()
        return when {
            failure != null -> KeyOutcome.Refused(refusalText(failure), HttpStatusCode.Conflict)
            attempt.getOrThrow() -> KeyOutcome.Applied(name, "removed")
            else -> KeyOutcome.Refused("$name was not stored", HttpStatusCode.NotFound)
        }
    }

    // The applied state is RE-READ, never assumed from the write having returned.
    private suspend fun answer(call: ApplicationCall, keys: KeyStore, outcome: KeyOutcome) {
        when (outcome) {
            is KeyOutcome.Refused -> AccountReplies.respondError(call, outcome.text, outcome.status)
            is KeyOutcome.Applied -> {
                log("[control] keys: ${LogSafe.str(outcome.verb)} ${LogSafe.str(outcome.name)}\n")
                val readers = readers()[outcome.name].orEmpty()
                AccountReplies.respond(call, keyJson(outcome.name, outcome.name in keys.names(), readers).toString())
            }
        }
    }

    private fun invalidName(name: String): String? =
        if (name.matches(envNameRegex)) {
            null
        } else {
            "'${LogSafe.str(name)}' is not an environment variable name (want $envNameRegex)"
        }

    // The store's own refusals are check() texts it builds from the name and SafeFailureText alone
    // (unreadable, locked by a peer), so they are shown as written; anything else is rendered through
    // SafeFailureText, which withholds a message that could quote bytes.
    private fun refusalText(failure: Throwable): String =
        (failure as? IllegalStateException)?.message ?: SafeFailureText.render(failure)

    /** Every head whose auth reads an env-var key, grouped by that variable. */
    private suspend fun readers(): Map<String, List<KeyReader>> =
        heads.values.mapNotNull { head ->
            val fields = head.auth.describe().fields
            fields["env_var"]?.let { name -> name to KeyReader(head.key, fields["key_source"] ?: UNKNOWN_SOURCE) }
        }.groupBy({ it.first }, { it.second })

    private fun keyJson(name: String, stored: Boolean, readers: List<KeyReader>): JsonObject = buildJsonObject {
        put("name", name)
        put("stored", stored)
        putJsonArray("heads") {
            readers.sortedBy { it.head }.forEach { reader ->
                addJsonObject {
                    put("head", reader.head)
                    put("source", reader.source)
                }
            }
        }
    }
}

/** What a write came to: applied (then re-read for the answer), or refused with its text and status. */
private sealed class KeyOutcome {
    class Applied(val name: String, val verb: String) : KeyOutcome()

    class Refused(val text: String, val status: HttpStatusCode) : KeyOutcome()
}

private const val KEYS_PORT = "key store"
private const val NAME_PARAM = "name"
private const val VALUE_FIELD = "value"

// A head whose auth names an env var but predates `key_source`: said, not guessed.
private const val UNKNOWN_SOURCE = "unknown"
