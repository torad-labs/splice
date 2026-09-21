// NEW: v0.4.0 FEATURES.md §11 — labeled credential validation and safe stray-file diagnostics.
// Split from OAuthAccountFiles so discovery and persistence share one label policy.
package splice.app.auth

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import splice.core.topology.AuthKind
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.upstream.credentials.AccountLabelPolicy
import java.nio.file.Files
import java.nio.file.Path

internal class OAuthAccountValidation(private val json: Json, private val log: LogSink) {
    fun validatedLabel(kind: AuthKind.OAuth, path: Path): String? {
        val label = path.fileName.toString().removeSuffix(".json")
        val raw = readCredential(kind, path)
        val declaredKind = raw?.get(FIELD_KIND)
        // A stray JSON document is not an account. Once it declares a kind, the existing kind,
        // filename and label refusals still apply: a mislabeled credential must never be accepted.
        if (declaredKind == null || declaredKind is JsonNull) {
            val filename = if (AccountLabelPolicy.isSafe(label)) "$label.json" else "<unsafe filename>"
            log("splice: skipped OAuth pool file $filename (not a credential)\n")
            return null
        }
        requireLabel(label)
        require(JsonScalars.str(raw, FIELD_KIND) == kind.wire) { "pooled OAuth account file has the wrong auth kind" }
        require(JsonScalars.str(raw, FIELD_LABEL) == label) { "pooled OAuth account file does not match its filename" }
        return label
    }

    fun requireLabel(label: String) {
        val reason = when {
            !AccountLabelPolicy.isSafe(label) -> "invalid OAuth account label"
            label == PRIMARY -> "OAuth account label primary is reserved for the legacy credential"
            label == AUTO -> "OAuth account label auto is reserved for derived labels"
            label.endsWith("-quota") -> "OAuth account labels must not end in -quota"
            else -> null
        }
        if (reason != null) throw OAuthAccountRefused(reason)
    }

    private fun readCredential(kind: AuthKind.OAuth, path: Path): JsonObject? {
        val text = Cancellables.runCatchingCancellable { Files.readString(path) }
            .getOrElse { throw IllegalArgumentException("unreadable ${kind.wire} OAuth account file", it) }
        return try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
    }
}
