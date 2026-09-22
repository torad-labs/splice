// NEW: v0.4.0 FEATURES.md §1 — is a stored OAuth credential USABLE, not merely present. A file that
// parses and carries token material where ITS KIND keeps it (chatgpt and grok under "tokens", kimi
// flat) plus a refresh token or an expiry still ahead lets `splice add` skip the sign-in; an empty,
// gutted, decoy-shaped or dead file must not. Reads the file once, names no byte of it, never goes to
// the network: the bar is "the daemon could serve or refresh this", which is what each provider needs.
package splice.app.cli.add

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import splice.app.auth.StoredCredential
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.Path

private const val SIGN_IN = "sign in again"

internal class AddCredentialFile(private val json: Json = Json { ignoreUnknownKeys = true }) {

    /** WHICH reader judges a kind's file is [StoredCredential]'s (2026-09-22) — this check and
     *  `splice models`' bearer lookup must not disagree about where a kind keeps its token. */
    private val credentials = StoredCredential(json)

    /** Null when the file can serve or refresh; otherwise the reason, with the fix being a sign-in. */
    fun problem(path: Path, kind: String, nowMs: Long = System.currentTimeMillis()): String? {
        val root = Cancellables.runCatchingCancellable { json.parseToJsonElement(Files.readString(path)).jsonObject }
            .getOrElse { return "the stored credential does not parse (${SafeFailureText.render(it)}) — $SIGN_IN" }
        val material = credentials.shapeFor(kind)?.material(root)
            ?: return "splice add cannot judge a $kind credential — $SIGN_IN"
        val expiresAt = material.expiresAtMs
        return when {
            material.access.isNullOrEmpty() ->
                "the stored credential holds no access token where $kind keeps it — $SIGN_IN"
            material.refreshOptional -> null
            !material.refresh.isNullOrEmpty() -> null
            expiresAt != null && expiresAt > nowMs -> null
            else -> "the stored credential holds no refresh token and no expiry still ahead — $SIGN_IN"
        }
    }
}
