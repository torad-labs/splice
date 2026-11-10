package splice.upstream.memory

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import splice.core.util.JsonWire
import splice.core.wire.AnthropicRequest
import splice.core.wire.ToolDefinition
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.HexFormat

/** One request's fragment identity, digested once per request. A cache entry keeps these 64 hex characters, never
 *  the tools, system prompt and request fields they stand for: an entry charged for the whole context could not fit
 *  a real Claude Code request's tool list, so the cache never hit. */
@JvmInline
public value class FragmentContextKey internal constructor(internal val digest: String) {
    /** The identity of messages whose mapping also reads [detail], derived without reading the request again. */
    public fun with(detail: JsonObject): FragmentContextKey = MessageFragmentContext.key(
        buildJsonObject {
            put("context", digest)
            put("detail", detail)
        },
    )
}

/** Request identity without any message/history graph or transport headers.
 *  Builder namespaces isolate heads, dialects and immutable quirks. Later prompt layers/code-mode
 *  shaping still run on every request; these fragments precede and do not consume those layers.
 */
public object MessageFragmentContext {
    public fun of(body: AnthropicRequest, raw: JsonObject): JsonObject = buildJsonObject {
        put("raw", JsonObject(raw - "messages"))
        put("model", body.model)
        body.system?.let { put("system", it) }
        put("tools", Json.encodeToJsonElement(ListSerializer(ToolDefinition.serializer()), body.tools))
        body.toolChoice?.let { put("tool_choice", Json.encodeToJsonElement(it)) }
        body.thinking?.let { put("thinking", Json.encodeToJsonElement(it)) }
        body.maxTokens?.let { put("max_tokens", it) }
        put("stream", body.stream)
    }

    /** SHA-256 of [context]'s wire bytes, streamed without building the context's string. */
    public fun key(context: JsonObject): FragmentContextKey {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestOutputStream(OutputStream.nullOutputStream(), digest).use { JsonWire.write(context, it) }
        return FragmentContextKey(HexFormat.of().formatHex(digest.digest()))
    }
}
