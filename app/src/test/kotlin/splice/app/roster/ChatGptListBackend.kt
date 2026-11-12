// A loopback ChatGPT backend for a codex head: the model list it answers each signed-in token, and the turns it is
// sent.
package splice.app.roster

import com.github.luben.zstd.Zstd
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import splice.core.util.SecureFile
import splice.core.wire.HttpStatus
import splice.provider.codex.auth.CodexOAuth
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Far above any turn these tests send, so a zstd body always inflates whole. */
private const val MAX_TURN_BYTES = 8 * 1024 * 1024

/** 2100-01-01: a signed-in token that never nears expiry, so no head refreshes it mid-test. */
private const val FAR_EXPIRY_SECONDS = 4_102_444_800L

/** The smallest whole Responses answer: one text message, then completion. */
private val ANSWER = listOf(
    """{"type":"response.output_item.added","output_index":0,"item":{"type":"message"}}""",
    """{"type":"response.output_text.delta","output_index":0,"delta":"synthetic answer"}""",
    """{"type":"response.output_item.done","output_index":0}""",
    """{"type":"response.completed","response":{"id":"resp-synthetic","status":"completed","output":[],""" +
        """"usage":{"input_tokens":1,"output_tokens":1}}}""",
)

internal class ChatGptListBackend : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val lists = ConcurrentHashMap<String, String>()

    /** While true the model list answers HTTP 500 to every token. Turns are still answered. */
    @Volatile var listOutage = false

    /** Each turn's body as the head sent it, decoded, in arrival order. */
    val turns = CopyOnWriteArrayList<String>()

    /** Each turn's request headers, names lowercased, in the same order as [turns]. A test reads the one header it
     *  asks about and prints no other: these carry the sign-in's bearer. */
    val turnHeaders = CopyOnWriteArrayList<Map<String, String>>()

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/backend-api/codex"

    init {
        server.createContext("/backend-api/codex/models") { exchange -> list(exchange) }
        server.createContext("/backend-api/codex/responses") { exchange -> turn(exchange) }
        server.start()
    }

    /** What GET /models answers a request signed with [token]: [rows], each one model object. A token never served
     *  here is refused, as the backend refuses a request with no sign-in. */
    fun serve(token: String, rows: List<String>) {
        lists[token] = rows.joinToString(",", """{"models":[""", "]}")
    }

    private fun list(exchange: HttpExchange) {
        val listed = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.let(lists::get)
        when {
            listOutage -> answer(exchange, HttpStatus.INTERNAL_SERVER_ERROR, """{"detail":"synthetic outage"}""")
            listed == null -> answer(exchange, HttpStatus.UNAUTHORIZED, """{"detail":"Unauthorized"}""")
            else -> answer(exchange, HttpStatus.OK, listed)
        }
    }

    private fun turn(exchange: HttpExchange) {
        val raw = exchange.requestBody.readBytes()
        val zstd = exchange.requestHeaders.getFirst("Content-Encoding")?.contains("zstd") == true
        turnHeaders += exchange.requestHeaders.entries.associate { (name, values) ->
            name.lowercase() to values.joinToString(",")
        }
        turns += (if (zstd) Zstd.decompress(raw, MAX_TURN_BYTES) else raw).decodeToString()
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(HttpStatus.OK, 0)
        exchange.responseBody.use { out -> ANSWER.forEach { out.write("data: $it\n\n".toByteArray()) } }
        exchange.close()
    }

    private fun answer(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        server.stop(0)
    }
}

/** One row of the backend's model list, in the Codex backend's own shape: [lite] is its `use_responses_lite`, and a
 *  [visibility] of `hide` keeps the model out of the picker, as the backend lists codex-auto-review. */
internal fun listedModel(
    slug: String,
    codeModeOnly: Boolean,
    lite: Boolean = true,
    visibility: String = "list",
): String {
    val toolMode = if (codeModeOnly) ""","tool_mode":"code_mode_only"""" else ""
    return """{"slug":"$slug","display_name":"$slug","visibility":"$visibility","use_responses_lite":$lite,""" +
        """"context_window":272000$toolMode}"""
}

/** `splice login` as the head sees it: the ChatGPT credential file its sign-in writes, in the Codex CLI's shape. */
internal class SyntheticChatGptLogin(private val authFile: Path) {
    private val oauth = CodexOAuth()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    /** The access token [signIn] writes for [account] and [nonce], known before it is written. */
    fun accessToken(account: String, nonce: String): String = jwt(account, "access-$nonce")

    /** Signs [account] in with a fresh token, and returns that token. */
    fun signIn(account: String, nonce: String): String {
        val access = accessToken(account, nonce)
        val auth = oauth.authJsonFromTokens(
            idToken = jwt(account, "id-$nonce"),
            accessToken = access,
            refreshToken = "synthetic-refresh-$account-$nonce",
            apiKey = null,
            nowIso = "2026-10-27T11:00:00Z",
        )
        SecureFile.writeAtomic0600(authFile, Json.encodeToString(JsonObject.serializer(), auth))
        return access
    }

    fun signOut() {
        Files.deleteIfExists(authFile)
    }

    private fun jwt(account: String, nonce: String): String {
        val header = encoder.encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())
        val claims = """{"exp":$FAR_EXPIRY_SECONDS,"jti":"$nonce",""" +
            """"https://api.openai.com/auth":{"chatgpt_account_id":"$account"}}"""
        return "$header.${encoder.encodeToString(claims.toByteArray())}.synthetic"
    }
}

/** The name of every tool a Responses request offers, wherever its shape declares them (the lite shape puts them in
 *  its first input item, the full one at the top). Code mode offers `exec`; direct tools offer the client's own. */
internal fun offeredTools(body: String): Set<String> =
    objectsIn(Json.parseToJsonElement(body))
        .flatMap { (it["tools"] as? JsonArray).orEmpty() }
        .mapNotNull { tool -> (tool as? JsonObject)?.get("name")?.jsonPrimitive?.content }
        .toSet()

private fun objectsIn(element: JsonElement): List<JsonObject> = when (element) {
    is JsonObject -> listOf(element) + element.values.flatMap(::objectsIn)
    is JsonArray -> element.flatMap(::objectsIn)
    is JsonPrimitive -> emptyList()
}
