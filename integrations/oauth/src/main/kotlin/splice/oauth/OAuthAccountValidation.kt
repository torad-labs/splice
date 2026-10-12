// NEW: v0.4.0 FEATURES.md §11 — labeled credential validation and safe stray-file diagnostics.
// Split from OAuthAccountFiles so discovery and persistence share one label policy.
package splice.oauth

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import splice.core.topology.AuthKind
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.upstream.credentials.AccountLabelPolicy
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal class OAuthAccountValidation(private val json: Json) {
    /** [path] parsed as a JSON object; null when it cannot be read or is not one. */
    fun jsonObjectOrNull(path: Path): JsonObject? = try {
        json.parseToJsonElement(Files.readString(path)) as? JsonObject
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** What the pool file at [path] is to discovery. A refusal is the one login's, with its reason, so a
     *  file signed in for another provider, carrying another label, or unreadable no longer fails the
     *  head's whole pool: the other logins keep serving. A name that cannot be a label is never echoed. */
    fun poolFile(kind: AuthKind.OAuth, path: Path, log: LogSink): PoolFile {
        val label = path.fileName.toString().removeSuffix(".json")
        // An unreadable file is classified, not dropped: its login lists as refused with the reason.
        val entry = Cancellables.runCatchingCancellable { Files.readString(path) }
            .fold(onSuccess = { classify(kind, label, it) }, onFailure = { classify(kind, label, null) })
        when (entry) {
            is PoolFile.Skipped -> {
                val filename = if (AccountLabelPolicy.isSafe(label)) "$label.json" else "<unsafe filename>"
                log("splice: skipped OAuth pool file $filename (${entry.why})\n")
            }
            is PoolFile.Refused -> log("splice: refused OAuth pool login: ${entry.reason}\n")
            is PoolFile.Account -> Unit
        }
        return entry
    }

    /** [text] is null when the file could not be read. A stray JSON document, one declaring no kind, is not an
     *  account; once a kind is declared, a mislabeled credential is refused and never accepted. */
    private fun classify(kind: AuthKind.OAuth, label: String, text: String?): PoolFile {
        val raw = text?.let(::objectOrNull)
        val labelRefusal = labelRefusal(label)
        return when {
            text != null && !declaresKind(raw) -> PoolFile.Skipped("not a credential")
            labelRefusal != null -> PoolFile.Skipped(labelRefusal)
            text == null -> PoolFile.Refused("'$label' could not be read; check its permissions or sign in again")
            else -> credential(kind, label, raw)
        }
    }

    private fun declaresKind(raw: JsonObject?): Boolean = raw?.get(FIELD_KIND).let { it != null && it !is JsonNull }

    /** A file that declares a kind is this provider's login only when its kind and its own label both match. */
    private fun credential(kind: AuthKind.OAuth, label: String, raw: JsonObject?): PoolFile = when {
        JsonScalars.str(raw, FIELD_KIND) != kind.wire ->
            PoolFile.Refused("'$label' was signed in for another provider; sign in again here, or remove it")
        JsonScalars.str(raw, FIELD_LABEL) != label ->
            PoolFile.Refused("'$label' carries another label inside the file; sign in again under this label")
        else -> PoolFile.Account(label)
    }

    fun requireLabel(label: String) {
        labelRefusal(label)?.let { throw OAuthAccountRefused(it) }
    }

    /** Why [label] cannot name an account, or null when it can. Never echoes the label. */
    private fun labelRefusal(label: String): String? = when {
        !AccountLabelPolicy.isSafe(label) -> "invalid OAuth account label"
        label == PRIMARY -> "OAuth account label primary is reserved for the legacy credential"
        label == AUTO -> "OAuth account label auto is reserved for derived labels"
        label.endsWith("-quota") -> "OAuth account labels must not end in -quota"
        else -> null
    }

    private fun objectOrNull(text: String): JsonObject? = try {
        json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    }
}

/** What one pool file is to discovery: an account under its label, a login refused with its reason, or a
 *  file skipped as no account at all. */
internal sealed class PoolFile {
    data class Account(val label: String) : PoolFile()

    data class Refused(val reason: String) : PoolFile()

    data class Skipped(val why: String) : PoolFile()
}
