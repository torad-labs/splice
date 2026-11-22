// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 1). Asks `agy acp` what it offers the account
// signed in to it: `initialize`, then `session/new` in a scratch folder, which answers with the models and their
// levels, then `session/close`. No prompt is sent, so nothing is spent from the account. The context window comes from
// the `usage_update` agy sends after a session opens.
package splice.provider.antigravity

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import java.io.IOException
import java.nio.file.Files

/** What asking agy came to. */
public sealed class AgyRead {
    /** What it offers. */
    public data class Offered(val roster: AgyRoster) : AgyRead()

    /** It is signed in to no account. */
    public data object SignedOut : AgyRead()

    /** It could not be asked, and why. */
    public data class Failed(val reason: String) : AgyRead()
}

/** Asks the `agy` started as [command], reading its output on [io] in [scope]. */
public class AgyModels(
    private val command: List<String>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val reader = AgyRosterReader()

    /** What agy offers now. */
    public suspend fun read(): AgyRead {
        val folder = Files.createTempDirectory("splice-agy-")
        try {
            return AcpConnection(command, folder, scope, io).use { agy -> ask(agy, folder.toString()) }
        } catch (notStarted: IOException) {
            return AgyRead.Failed("agy could not be started (${SafeFailureText.render(notStarted)})")
        } finally {
            Files.deleteIfExists(folder)
        }
    }

    private suspend fun ask(agy: AcpConnection, folder: String): AgyRead {
        val hello = answer(agy, "initialize", initialize())
        val opened = if (hello is AcpAnswer.Result) answer(agy, "session/new", newSession(folder)) else hello
        if (opened !is AcpAnswer.Result) return refusal(opened)
        val session = JsonScalars.str(opened.result, "sessionId")
        val window = withTimeoutOrNull(WINDOW_MS) { windowOf(agy, session) } ?: AGY_WINDOW
        if (session != null) {
            answer(agy, "session/close", sessionOf(session))
        }
        return AgyRead.Offered(reader.of(opened.result, window))
    }

    /** What agy answered [method], or a refusal saying it gave no answer in time. */
    private suspend fun answer(agy: AcpConnection, method: String, params: JsonObject): AcpAnswer =
        withTimeoutOrNull(AGY_ANSWER_MS) { agy.request(method, params) }
            ?: AcpAnswer.Refused(0, "no answer to $method in time")

    private fun newSession(folder: String): JsonObject = buildJsonObject {
        put("cwd", folder)
        put("mcpServers", JsonArray(emptyList()))
    }

    /** The window the first `usage_update` of [session] gives, or null when agy's output ends first. */
    private suspend fun windowOf(agy: AcpConnection, session: String?): Long? {
        for (message in agy.incoming) {
            val params = message["params"] as? JsonObject
            val update = params?.get("update") as? JsonObject
            val ofSession = JsonScalars.str(params, "sessionId") == session
            if (ofSession && JsonScalars.str(update, "sessionUpdate") == "usage_update") {
                return JsonScalars.long(update, "size")
            }
        }
        return null
    }

    private fun refusal(answer: AcpAnswer): AgyRead = when (answer) {
        is AcpAnswer.Refused ->
            if (answer.code == AUTH_REQUIRED) AgyRead.SignedOut else AgyRead.Failed("agy: ${answer.message}")
        AcpAnswer.Ended -> AgyRead.Failed("agy ended before it answered")
        is AcpAnswer.Result -> AgyRead.Failed("agy answered out of turn")
    }

    private fun initialize(): JsonObject = buildJsonObject {
        put("protocolVersion", 1)
        putJsonObject("clientCapabilities") {
            putJsonObject("fs") {
                put("readTextFile", false)
                put("writeTextFile", false)
            }
            put("terminal", false)
        }
        putJsonObject("clientInfo") {
            put("name", "splice")
            put("title", "Splice for Claude Desktop")
        }
    }

    private fun sessionOf(id: String): JsonObject = buildJsonObject { put("sessionId", id) }
}

/** ACP's error code for "authentication required". */
private const val AUTH_REQUIRED = -32000

/** How long agy has to answer one request: `session/new` asks Google for the account's models. */
private const val AGY_ANSWER_MS = 60_000L

/** How long the window waits for its `usage_update` after the session opens. */
private const val WINDOW_MS = 5_000L

/** The window agy 1.5.14 reports for every model (`usage_update.size`), taken when it sends none. */
private const val AGY_WINDOW = 1_048_576L
