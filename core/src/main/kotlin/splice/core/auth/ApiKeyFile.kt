// NEW: PR #247's sixth review. The ONE reading of an api-key file's text, by the provider that authenticates
// with it (ApiKeyAuthProvider): an empty file, or JSON with no `api_key`, supplies no key, and the store is read next.
package splice.core.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import splice.core.util.JsonScalars

public object ApiKeyFile {

    /** The key a file holding [text] supplies, or null when it holds none: a bare line, or `{"api_key": ...}`. A
     *  blank key in either form is none, so the key store is read next (PR #247's seventh review). Malformed JSON
     *  throws, so the reader that logs a failed read logs it too. */
    public fun key(text: String): String? {
        val trimmed = text.trim()
        return if (trimmed.startsWith("{")) {
            JsonScalars.str(Json.parseToJsonElement(trimmed).jsonObject, "api_key")?.takeIf { it.isNotBlank() }
        } else {
            trimmed.takeIf { it.isNotEmpty() }
        }
    }
}
