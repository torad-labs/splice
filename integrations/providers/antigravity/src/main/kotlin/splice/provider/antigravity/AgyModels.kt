// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 1). Asks `agy acp` what it offers the account
// signed in to it: `initialize`, then `session/new` in a scratch folder, which answers with the models and their
// levels, then `session/close`. No prompt is sent, so nothing is spent from the account. The context window comes from
// the `usage_update` agy sends after a session opens. What agy offers comes back as the catalog splice lists.
package splice.provider.antigravity

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.model.ModelCatalog
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** What asking agy came to. */
public sealed class AgyRead {
    /** The models it offers, as a catalog. */
    public data class Offered(val catalog: ModelCatalog) : AgyRead()

    /** It is signed in to no account. */
    public data object SignedOut : AgyRead()

    /** There is no agy to ask: it is not installed, or can't be run, and why. */
    public data class Absent(val reason: String) : AgyRead()

    /** It could not be asked, or offered nothing splice can list, and why. */
    public data class Failed(val reason: String) : AgyRead()
}

/** Asks the `agy` started as [command], reading its output on [io] in [scope]; what agy left unsaid goes to [log]. */
public class AgyModels(
    private val log: LogSink,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val command: List<String> = AGY_COMMAND,
) {
    private val reader = AgyRosterReader()
    private val catalogs = AntigravityCatalog()

    /** What agy offers now. */
    public suspend fun read(): AgyRead = withContext(io) { readOn() }

    private suspend fun readOn(): AgyRead {
        val scratch = try {
            Files.createTempDirectory("splice-agy-")
        } catch (noFolder: IOException) {
            return AgyRead.Failed("agy's scratch folder could not be made (${SafeFailureText.render(noFolder)})")
        }
        try {
            return readIn(scratch)
        } finally {
            if (!scratch.toFile().deleteRecursively()) log("[antigravity] $scratch was not removed\n")
        }
    }

    /** What agy started in [scratch] offers; a failure quotes the last thing agy wrote to its standard error. */
    private suspend fun readIn(scratch: Path): AgyRead {
        val errors = scratch.resolve("agy.err").toFile()
        val agy = try {
            AcpConnection(command, scratch, errors, scope, io)
        } catch (notStarted: IOException) {
            return AgyRead.Absent("agy could not be started (${SafeFailureText.render(notStarted)})")
        }
        val read = agy.use { ask(it, scratch.toString()) }
        if (read !is AgyRead.Failed) return read
        val wrote = agy.unreadable?.let { " (agy wrote, unreadable: $it)" }.orEmpty()
        return AgyRead.Failed(read.reason + lastWords(errors) + wrote)
    }

    private suspend fun ask(agy: AcpConnection, folder: String): AgyRead {
        val hello = answer(agy, "initialize", initialize(), AGY_ANSWER_MS)
        val opened =
            if (hello is AcpAnswer.Result) answer(agy, "session/new", newSession(folder), AGY_ANSWER_MS) else hello
        if (opened !is AcpAnswer.Result) return refusal(opened)
        val session = JsonScalars.str(opened.result, "sessionId")
        val window = withTimeoutOrNull(AGY_SHORT_MS) { windowOf(agy, session) }
            ?: AGY_WINDOW.also { log("[antigravity] agy reported no context window; every model gets $it tokens\n") }
        if (session != null) {
            answer(agy, "session/close", sessionOf(session), AGY_SHORT_MS)
        }
        val roster = reader.of(opened.result, window)
        return catalogs.of(roster)?.let { AgyRead.Offered(it) }
            ?: AgyRead.Failed("agy offers no listable model (${roster.models.size} listed; Claude ids are left out)")
    }

    /** What agy answered [method] within [ms], or a refusal saying it gave no answer in time. */
    private suspend fun answer(agy: AcpConnection, method: String, params: JsonObject, ms: Long): AcpAnswer =
        withTimeoutOrNull(ms) { agy.request(method, params) } ?: AcpAnswer.Refused(0, "no answer to $method in time")

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

    /** The last line agy wrote to its standard error, to say why it failed; empty when it wrote none. */
    private fun lastWords(errors: File): String {
        val lines = try {
            errors.readLines()
        } catch (unread: IOException) {
            listOf("its standard error could not be read (${SafeFailureText.render(unread)})")
        }
        val last = lines.lastOrNull { it.isNotBlank() } ?: return ""
        return " (agy said: ${last.take(LAST_WORDS_CHARS)})"
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

    private fun newSession(folder: String): JsonObject = buildJsonObject {
        put("cwd", folder)
        put("mcpServers", JsonArray(emptyList()))
    }

    private fun sessionOf(id: String): JsonObject = buildJsonObject { put("sessionId", id) }
}

/** agy as its installer puts it on PATH, run as an ACP server over stdio. */
private val AGY_COMMAND = listOf("agy", "acp")

/** ACP's error code for "authentication required". */
private const val AUTH_REQUIRED = -32000

/** How long agy has to answer `initialize` and `session/new`: `session/new` asks Google for the account's models. */
private const val AGY_ANSWER_MS = 60_000L

/** How long the window waits for its `usage_update`, and `session/close` for its answer. */
private const val AGY_SHORT_MS = 5_000L

/** The window agy 1.5.14 reports for every model (`usage_update.size`), taken when it sends none. */
private const val AGY_WINDOW = 1_048_576L

/** How much of agy's last line of standard error a failure quotes. */
private const val LAST_WORDS_CHARS = 300
