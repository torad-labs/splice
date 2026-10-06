// NEW: durable replay witnesses are captured independently from request-local placement and payload matching.
package splice.provider.codex.state.native

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.perf.InputDigest
import splice.provider.codex.CODE_MODE_FIELD_CALL_ID
import splice.provider.codex.CODE_MODE_FIELD_TYPE
import splice.provider.codex.CodeModeInputBoundary
import splice.provider.codex.CodeModeNativeSegment
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.state.CodeModeHistoryAnchor
import splice.provider.codex.state.CodeModeReplayAnchors

private val OPAQUE_TYPES = setOf("custom_tool_call", "custom_tool_call_output")

internal object CodeModeAnchorCapture {
    /** [inputBoundary] for a request held as text, parsed once. */
    fun inputBoundary(
        bodyJson: String,
        completed: List<CodeModeRecord>,
        codec: CodexCodeModeHistoryCodec,
    ): CodeModeInputBoundary? = inputBoundary(codec.root(bodyJson)?.second, completed, codec)

    /** [input]: the round's parsed input array, or null when the round is not a Responses request. */
    fun inputBoundary(
        input: JsonArray?,
        completed: List<CodeModeRecord>,
        codec: CodexCodeModeHistoryCodec,
    ): CodeModeInputBoundary? {
        if (input == null) return null
        val conversation = codec.conversation(codec.projection.project(input))
        val body = conversation.body
        val natives = body.nativeSegments.map { CodeModeNativeSegment(it.logicalOffset, it.items) }
        return CodeModeInputBoundary(
            input.size - conversation.preamble.size,
            body.logicalItems.size,
            "",
            "",
            natives,
        ).also { it.replayAnchors = capture(body.logicalItems, natives, completed, codec) }
    }

    fun capture(
        items: List<JsonElement>,
        natives: List<CodeModeNativeSegment>,
        completed: List<CodeModeRecord>,
        codec: CodexCodeModeHistoryCodec,
    ): CodeModeReplayAnchors {
        val owned = completed.flatMap { it.clientIds() + it.outerCallId }.toSet()
        val continuity = completed.flatMap(CodeModeRecord::continuity).toSet()
        val fingerprints = InputDigest.hexItems(items).toList()
        val eligible = items.indices.filter { codec.callId(items[it]) !in owned && items[it] !in continuity }.toSet()
        fun at(boundary: Int): CodeModeHistoryAnchor {
            val prior = eligible.lastOrNull { it < boundary } ?: return CodeModeHistoryAnchor(null, 0, boundary)
            val digest = fingerprints[prior]
            return CodeModeHistoryAnchor(digest, fingerprints.take(prior).count { it == digest }, boundary - prior - 1)
        }
        // Owned opaque items are unstable baseline text, but their kind and call id survive canonicalization.
        val followingKeys = items.mapIndexed { at, item ->
            if (at in eligible) fingerprints[at] else opaqueKey(item, codec).takeIf { codec.callId(item) in owned }
        }
        val following = natives.mapNotNull { segment ->
            val next = segment.logicalOffset.takeIf { it in items.indices } ?: return@mapNotNull null
            val digest = followingKeys[next] ?: return@mapNotNull null
            segment.logicalOffset to CodeModeHistoryAnchor(digest, followingKeys.take(next).count { it == digest })
        }.toMap()
        return CodeModeReplayAnchors(
            at(items.size),
            natives.associate { it.logicalOffset to at(it.logicalOffset) },
            following,
        )
    }

    /** A following opaque witness names the item, not source or output bytes a later round can grow. */
    fun opaqueKey(item: JsonElement, codec: CodexCodeModeHistoryCodec): String? {
        val value = item as? JsonObject ?: return null
        if (codec.string(value, CODE_MODE_FIELD_TYPE) !in OPAQUE_TYPES || codec.callId(item) == null) return null
        return InputDigest.hex(
            JsonObject(
                mapOf(
                    CODE_MODE_FIELD_TYPE to value.getValue(CODE_MODE_FIELD_TYPE),
                    CODE_MODE_FIELD_CALL_ID to value.getValue(CODE_MODE_FIELD_CALL_ID),
                ),
            ),
        )
    }
}
