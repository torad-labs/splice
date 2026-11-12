package splice.provider.codex.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.provider.codex.CodeModeRecordSnapshot
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.CodexCodeModeStore
import splice.provider.codex.history.CodeModeOwnership
import splice.provider.codex.state.CodeModeExtraContent

/** Raw synthetic requests and reloaded witnesses accompany actual production ownership decisions. */
internal class CodeModeGatewayReplayOwnership(private val fixture: CodeModeGatewayProbeSupport) {
    private val codec = CodexCodeModeHistoryCodec(fixture.json)
    private val classifier = CodeModeExtraContent(codec, CodeModeOwnership(codec))
    private val reasoning = buildJsonObject {
        put("type", "reasoning"); put("id", "rs-added-${fixture.nonce}")
        put("summary", JsonArray(emptyList())); put("encrypted_content", "opaque-added-${fixture.nonce}")
    }

    fun run(): JsonObject = buildJsonObject {
        put("schema", 1)
        put("baseline", JsonArray(fixture.baseline))
        put("callbackOnly", observe("callbackOnly", emptyList(), delivered = false))
        put(
            "unknownReasoningBeforeOwnedCallback",
            observe("unknownReasoningBeforeOwnedCallback", listOf(reasoning), delivered = false),
        )
        put(
            "deliveredReasoningWithValidatedDeclaration",
            observe("deliveredReasoningWithValidatedDeclaration", declaration(), delivered = true),
        )
        put("unknownReasoningWithDeclaration", observe("unknownReasoningWithDeclaration", declaration(), false))
    }

    private fun observe(kind: String, extra: List<JsonElement>, delivered: Boolean): JsonObject {
        val scoped = CodeModeGatewayProbeSupport(fixture.nonce, fixture.location.dir.parent.resolve(kind))
        val record = scoped.record()
        if (delivered) record.issued[0] = record.issued[0].copy(deliveredNative = listOf(reasoning))
        val store = CodexCodeModeStore(scoped.location, scoped.json, scoped.log)
        store.load()
        store.save(listOf(record), emptyList())
        val restored = store.load().records.single().restore()
        val input = JsonArray(scoped.baseline + extra + scoped.callback("one") + scoped.callback("two"))
        val projected = codec.projection.project(input)
        return buildJsonObject {
            put("classification", classifier.of(input, restored, emptyMap()).name)
            put("input", input)
            put("record", scoped.json.encodeToJsonElement(CodeModeRecordSnapshot.serializer(), restored.snapshot()))
            put(
                "projection",
                JsonArray(
                    projected.replayItems.map { replay ->
                        buildJsonObject {
                            put("logicalOffset", replay.logicalOffset)
                            replay.callbackId?.let { put("callbackId", it) }
                            put("items", JsonArray(replay.items))
                        }
                    },
                ),
            )
        }
    }

    private fun declaration(): List<JsonElement> {
        val id = "ts_decl_replay-${fixture.nonce}"
        return listOf(
            reasoning,
            buildJsonObject {
                put("type", "tool_search_call"); put("call_id", id); put("execution", "client")
                put("arguments", buildJsonObject { put("query", "Read") })
            },
            buildJsonObject {
                put("type", "tool_search_output"); put("call_id", id); put("execution", "client")
                put(
                    "tools",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("type", "function"); put("name", "Read")
                                put("parameters", buildJsonObject { put("type", "object") })
                            },
                        ),
                    ),
                )
            },
        )
    }
}
