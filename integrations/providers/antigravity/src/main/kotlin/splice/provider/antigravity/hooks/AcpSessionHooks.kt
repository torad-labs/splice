// NEW: hooks for an in-process Antigravity ACP Desktop session: unified Console Accounts `/login`
// interception, session-start notification (Teams rule-binding) and foreground tool activity.
// Kept in a subpackage so `splice.provider.antigravity` stays below the package-size cap.
// Never reads or writes `~/.gemini` (docs/specs/desktop-antigravity.md).
package splice.provider.antigravity.hooks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.client.ForegroundToolActivity
import splice.core.client.ForegroundToolCall
import splice.core.client.ForegroundToolPhase
import splice.core.util.JsonScalars
import java.util.UUID

/** Opens Splice Console's Accounts page (or explains how to) when the user types `/login` in an ACP session. */
public fun interface AcpLoginOpener {
    public fun open(): String
}

/** Notifies the daemon when a fresh ACP session starts in [cwd], so Teams cwd rule-binding sees it. */
public fun interface AcpSessionStart {
    public fun started(sessionId: String, cwd: String)
}

/** In-process callbacks for [splice.provider.antigravity.DesktopAcp] and [splice.provider.antigravity.DesktopSession]. */
public data class AcpSessionHooks(
    public val login: AcpLoginOpener = AcpLoginOpener { DEFAULT_ACP_LOGIN_REPLY },
    public val sessionStart: AcpSessionStart? = null,
    public val foreground: ForegroundToolActivity? = null,
    public val foregroundOwner: String = UUID.randomUUID().toString(),
) {
    /** Session [sessionId] opened in [cwd]; a [fresh] one (not a resume) is announced for Teams rule-binding. */
    internal fun opened(sessionId: String, cwd: String, fresh: Boolean) {
        if (fresh && cwd.isNotBlank()) sessionStart?.started(sessionId, cwd)
        foreground?.record(ForegroundToolCall(sessionId, null, ForegroundToolPhase.SESSION_START, foregroundOwner))
    }

    /** Session [sessionId] ended. */
    internal fun ended(sessionId: String) {
        foreground?.record(ForegroundToolCall(sessionId, null, ForegroundToolPhase.SESSION_END, foregroundOwner))
    }
}

/** Recognizes `/login` and `/splice:login` user prompts before they reach agy. */
internal object AcpLoginPrompt {
    private val pattern = Regex("""^(/login|/splice:login)(\s|$)""")

    fun matches(content: JsonElement): Boolean {
        val text = plainText(content)?.trim() ?: return false
        return pattern.containsMatchIn(text)
    }

    private fun plainText(content: JsonElement): String? = when (content) {
        is JsonPrimitive -> JsonScalars.str(content)
        is JsonArray -> {
            val parts = mutableListOf<String>()
            for (item in content) {
                val obj = item as? JsonObject ?: return null
                if (JsonScalars.str(obj, "type") != "text") return null
                parts += JsonScalars.str(obj, "text").orEmpty()
            }
            parts.joinToString("\n")
        }
        is JsonObject -> null
    }
}

internal const val DEFAULT_ACP_LOGIN_REPLY: String =
    "Manage accounts on Splice Console's Accounts page: run `splice console` in a terminal to open it. " +
        "Nothing was sent to the model."
