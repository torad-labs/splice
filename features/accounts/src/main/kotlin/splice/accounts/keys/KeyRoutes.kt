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
//
// Split three ways for concentration: KeyWrites validates and writes, KeyReaders asks the heads which
// key they read and from where, and this file answers HTTP.
package splice.accounts.keys

import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.accounts.AccountHead
import splice.accounts.AccountReplies
import splice.core.config.KeyStore
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.http.JsonBody

/** The daemon's ONE key store, read at CALL time: the console wiring assigns it after the server is
 *  constructed, and null answers every key route with a named 503, never an empty list. */
public fun interface KeyStoreSource {
    public operator fun invoke(): KeyStore?
}

public class KeyRoutes(
    heads: Map<String, AccountHead>,
    private val store: KeyStoreSource,
    private val log: LogSink,
) {
    private val jsonBody = JsonBody()
    private val readers = KeyReaders(heads)

    /** GET /api/keys: `{path, keys: [{name, stored, heads: [{head, source}]}]}`, sorted by name. */
    public suspend fun list(call: ApplicationCall) {
        val keys = store() ?: return AccountReplies.respondUnwired(call, KEYS_PORT)
        val byName = readers.byName()
        val stored = keys.names()
        val body = buildJsonObject {
            put("path", keys.path.toString())
            putJsonArray("keys") {
                (stored + byName.keys).sorted().forEach { name ->
                    add(readers.json(name, name in stored, byName[name].orEmpty()))
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
        answer(call, keys, KeyWrites(keys).stored(call.parameters[NAME_PARAM].orEmpty(), value))
    }

    /** DELETE /api/keys/{ENV}: removes the stored key. 200 with the applied state; 404 when the store
     *  did not hold it; 409 when the store refuses the write. */
    public suspend fun unset(call: ApplicationCall) {
        val keys = store() ?: return AccountReplies.respondUnwired(call, KEYS_PORT)
        answer(call, keys, KeyWrites(keys).removed(call.parameters[NAME_PARAM].orEmpty()))
    }

    // The applied state is RE-READ, never assumed from the write having returned.
    private suspend fun answer(call: ApplicationCall, keys: KeyStore, outcome: KeyOutcome) {
        when (outcome) {
            is KeyOutcome.Refused -> AccountReplies.respondError(call, outcome.text, outcome.status)
            is KeyOutcome.Applied -> {
                log("[control] keys: ${LogSafe.str(outcome.verb)} ${LogSafe.str(outcome.name)}\n")
                val name = outcome.name
                val applied = readers.json(name, name in keys.names(), readers.byName()[name].orEmpty())
                AccountReplies.respond(call, applied.toString())
            }
        }
    }
}

private const val KEYS_PORT = "key store"
private const val NAME_PARAM = "name"
