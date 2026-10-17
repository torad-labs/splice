// NEW: S8 #16 (Oct 12, 2026) — "test one": the Settings row's Test asks the host to start a shared server if it is
// not running, list its tools, and say what came back. It speaks to the host exactly as a client does (initialize,
// initialized, tools/list, then ends its session), so what it proves is what a session would meet.
package splice.control.mcp

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import kotlin.time.Duration

// why: the version a client asks for in initialize; the child answers with the one it speaks, and later requests of
// this probe name that one, as a real client's do.
private const val ASK_PROTOCOL = "2025-11-25"
private const val KIND_TIMEOUT = "timeout"
private const val KIND_SPAWN = "spawn"
private const val KIND_UNSHARED = "unshared"
private const val KIND_OTHER = "other"

/** What testing one server found; [error] is set exactly when [ok] is false. */
public data class McpTestResult(
    val ok: Boolean,
    val started: Boolean,
    val roundTripMs: Long,
    val tools: Int?,
    val error: String?,
    /** What kind of failure: `timeout` (given up waiting), `spawn` (could not be started), `unshared` (the host does
     *  not serve it) or `other`; null when [ok]. Told apart by code, never by the words. */
    val kind: String? = null,
) {
    public fun json(): String = buildJsonObject {
        put("ok", ok)
        put("started", started)
        put("round_trip_ms", roundTripMs)
        tools?.let { put("tools", it) }
        error?.let { put("error", it) }
        kind?.let { put("kind", it) }
    }.toString()
}

public class McpProbe internal constructor(
    private val host: McpHost,
    private val status: McpStatus,
    private val running: HostedServerLookup,
    private val clock: HostClock,
    private val listBound: Duration,
) {
    private val codec = JsonRpcCodec()

    public suspend fun test(name: String): McpTestResult {
        val begin = clock.millis()
        return run(name, begin, running(name)?.alive != true)
    }

    private suspend fun run(name: String, begin: Long, fresh: Boolean): McpTestResult {
        val notShared = status.unsharedReason(name)
        if (notShared != null) return failed(begin, fresh, "not shared: $notShared", KIND_UNSHARED)
        val opened = host.post(name, null, request(1, "initialize", initializeParams()))
        val session = opened.sessionId
        if (opened.status != HttpStatus.OK || session == null) {
            return failed(begin, fresh, wordsOf(opened), kindOf(opened, KIND_SPAWN))
        }
        return try {
            host.post(name, session, notification("notifications/initialized"))
            list(name, session, begin, fresh)
        } finally {
            host.endSession(name, session)
        }
    }

    private suspend fun list(name: String, session: String, begin: Long, fresh: Boolean): McpTestResult {
        val listed = withTimeoutOrNull(listBound) {
            host.post(name, session, request(2, "tools/list", buildJsonObject { }))
        } ?: return failed(begin, fresh, "no tool list within ${listBound.inWholeSeconds} s", KIND_TIMEOUT)
        val tools = (codec.parse(listed.body.orEmpty())?.get("result") as? JsonObject)?.get("tools") as? JsonArray
        return if (listed.status == HttpStatus.OK && tools != null) {
            McpTestResult(true, fresh, clock.millis() - begin, tools.size, null)
        } else {
            failed(begin, fresh, wordsOf(listed), kindOf(listed, KIND_OTHER))
        }
    }

    private fun failed(begin: Long, fresh: Boolean, error: String, kind: String) =
        McpTestResult(false, fresh, clock.millis() - begin, null, error, kind)

    /** `timeout` when the host gave up waiting (its own code), otherwise [otherwise]. */
    private fun kindOf(reply: McpReply, otherwise: String): String =
        if (codeOf(reply) == RPC_TIMED_OUT) KIND_TIMEOUT else otherwise

    private fun codeOf(reply: McpReply): Int? {
        val error = codec.parse(reply.body.orEmpty())?.get("error") as? JsonObject
        return JsonScalars.str(error?.get("code"))?.toIntOrNull()
    }

    /** The server's own words when it gave any, otherwise the status the host answered with. */
    private fun wordsOf(reply: McpReply): String {
        val error = codec.parse(reply.body.orEmpty())?.get("error") as? JsonObject
        return JsonScalars.str(error?.get("message")) ?: "the host answered ${reply.status}"
    }

    private fun initializeParams(): JsonObject = buildJsonObject {
        put("protocolVersion", ASK_PROTOCOL)
        put("capabilities", buildJsonObject { })
        put(
            "clientInfo",
            buildJsonObject {
                put("name", "splice-test")
                put("version", "1")
            },
        )
    }

    private fun request(id: Int, method: String, params: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        put("params", params)
    }.toString()

    private fun notification(method: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
    }.toString()
}
