package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.InputDigest
import splice.core.perf.InputItemDigests
import splice.core.util.LogSink
import splice.provider.codex.history.CodeModeClientPrefix
import splice.provider.codex.state.CodeModeHistoryAnchor
import splice.provider.codex.state.CodeModeReplayAnchors
import splice.provider.codex.stream.CodeModeUpstreamPost
import splice.upstream.InterceptedRoundPost
import splice.upstream.RoundBody
import splice.upstream.codemode.CodeModeResult
import java.nio.file.Path

internal class CodeModeGatewayProbeSupport(val nonce: String, directory: Path) {
    val json = Json { encodeDefaults = true }
    val codec = CodexCodeModeHistoryCodec(json)
    val location = CodeModeStateLocation(directory.resolve("records"), directory.resolve("legacy.json"))
    val logs = mutableListOf<String>()
    val log = LogSink { logs += it }
    val key = "parity-$nonce"
    val initial = listOf(user("begin-$nonce"))
    val native = listOf(
        buildJsonObject {
            put("type", "reasoning"); put("id", "rs-$nonce")
            put("summary", JsonArray(emptyList())); put("encrypted_content", "opaque-$nonce")
        },
        assistant("Same words. "),
        assistant("Same words."),
    )
    val nextClient = initial + assistant("Same words. Same words.") + user("next-$nonce")
    val baseline = initial + callback("old") + native.first() + user("continue-$nonce")

    fun user(text: String): JsonObject = message("user", text)
    fun assistant(text: String): JsonObject = message("assistant", text)
    private fun message(role: String, text: String): JsonObject = buildJsonObject {
        put("role", role); put("content", text)
    }
    fun callback(id: String): List<JsonElement> = listOf(
        buildJsonObject {
            put("type", "function_call"); put("call_id", id); put("name", "Read"); put("arguments", "{}")
        },
        buildJsonObject { put("type", "function_call_output"); put("call_id", id); put("output", "result-$nonce") },
    )
    fun body(items: List<JsonElement>): CodeModeBody = CodeModeBody(
        RoundBody.Tree(buildJsonObject { put("input", JsonArray(items)) }),
        json,
    )
    fun context(items: List<JsonElement>): CodeModeRunContext {
        val wire = CodexCodeModeWire(json, log)
        return CodeModeRunContext(
            CodexCodeModeBridge.Turn("probe-$nonce", key, "fixture-model", emptySet(), emptyList()),
            false,
            key,
            InputDigest.hex(JsonArray(items)),
            CodeModeRoundLink(
                CodeModeGatewayProbeSink,
                CodeModeUpstreamPost(
                    InterceptedRoundPost { error("history probe must not dispatch a model request") },
                    wire,
                ),
                body(items),
            ),
        )
    }
    fun record(): CodeModeRecord = CodeModeRecord(
        id = "record-$nonce",
        key = key,
        phase = CodeModePhase.ACTIVE,
        origin = CodeModeOrigin(
            JsonObject(emptyMap()),
            "exec-$nonce",
            "source",
            CodeModeBaseline(1, "", 1, "", CODE_MODE_METADATA_VERSION),
        ),
        progress = CodeModeProgress(updatedAt = 1000, lastDigest = "request-$nonce"),
        carry = CodeModeNativeContinuity(
            listOf(CodeModeNativeSegment(1, listOf(native.first()))),
            emptyList(),
            emptyList(),
        ),
    ).also { record ->
        record.replayAnchors = CodeModeReplayAnchors(
            CodeModeHistoryAnchor(InputDigest.hex(initial.first()), 0),
            emptyMap(),
            client = CodeModeClientPrefix(baseline, InputItemDigests()).start(),
        )
        val calls = listOf("one", "two").map { id -> CodeModePending(id, id, "Read", JsonObject(emptyMap()), true) }
        record.issued += CodeModeIssuedStep("request-$nonce", calls)
        record.progress.pending += calls
        record.accepted.accept(
            calls.associate { it.clientId to CodeModeResult(it.clientId, "result-$nonce") },
            emptyMap(),
        )
    }
    fun input(body: CodeModeBody?): JsonArray = checkNotNull(body?.request?.second) {
        "production history did not produce a Responses input"
    }
}
