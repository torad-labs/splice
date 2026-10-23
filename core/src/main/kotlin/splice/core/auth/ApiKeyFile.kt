// NEW: PR #247's sixth review. The ONE reading of an api-key file's text, shared by the provider that
// authenticates with it (ApiKeyAuthProvider) and the share renewal that asks whether that file shadows the
// key store (RemoteHeadAdd). The renewal had judged the file by its existence alone, so an empty file, or JSON
// with no `api_key`, which the provider passes over for the store, read as shadowing a token that was in use.
package splice.core.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import splice.core.util.JsonScalars

public object ApiKeyFile {

    /** The key a file holding [text] supplies, or null when it holds none: a bare line, or `{"api_key": ...}`.
     *  Malformed JSON throws, so the reader that logs a failed read logs it too. */
    public fun key(text: String): String? {
        val trimmed = text.trim()
        return if (trimmed.startsWith("{")) {
            JsonScalars.str(Json.parseToJsonElement(trimmed).jsonObject, "api_key")
        } else {
            trimmed.takeIf { it.isNotEmpty() }
        }
    }
}
