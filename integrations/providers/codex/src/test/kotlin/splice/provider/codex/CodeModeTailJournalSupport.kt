package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.reasoning.ReasoningReplay
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.ResponsesAssistantText
import splice.dialect.responses.responsesTestTranslator
import splice.upstream.RedirectableRoundPost
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeResult
import splice.upstream.sse.WireSink
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

// why: 48 pairs expose repeated prefixes while the whole old journal remains below its 8 MiB compaction floor.
private const val GROWING_TAIL_PAIRS = 48

abstract class CodeModeTailJournalSupport : CodeModeSealedItemSupport() {
    protected inner class TailPost : RedirectableRoundPost {
        val tailSent = CompletableDeferred<Unit>()
        val terminalRelease = CompletableDeferred<Unit>()
        val continuation = CompletableDeferred<String>()
        val journal = TailJournalBytes(stateFiles.dir)
        val completed = outer(source = script).let {
            it.copy(raw = JsonObject(it.raw + ("status" to JsonPrimitive("completed"))))
        }
        val reason = reasoning("prefix-r")
        private val preface = message("preface-a", "A")
        private val following = List(GROWING_TAIL_PAIRS) { index ->
            reasoning("tail-r-$index") to message("tail-b-$index", body(index))
        }
        val tail = following.flatMap { listOf(it.first, it.second) }
        val text = "A" + following.indices.joinToString("") { body(it) }
        private var first = true

        override suspend fun invoke(bodyJson: String): RoundResult = error("redirect required")

        override suspend fun into(bodyJson: String, sink: WireSink): RoundResult {
            if (!first) {
                continuation.complete(bodyJson)
                return RoundResult.Outcome(completedOutcome())
            }
            first = false
            val reader = responsesTestTranslator(
                collectReasoningEnvelopes = true,
                encode = ReasoningReplay::encodeReasoningEnvelope,
            )
            return RoundResult.Outcome(reader.driveTurn(frames(), sink))
        }

        private fun frames(): Flow<JsonObject> = flow {
            emit(event("response.output_item.done", 0, reason))
            prose(1, preface, "A").forEach { emit(it) }
            val added = JsonObject(
                completed.raw + mapOf("input" to JsonPrimitive(""), "status" to JsonPrimitive("in_progress")),
            )
            emit(event("response.output_item.added", 2, added))
            journal.observe()
            emit(event("response.output_item.done", 2, completed.raw))
            journal.observe()
            following.forEachIndexed { index, (reasoning, message) ->
                emit(event("response.output_item.done", 3 + 2 * index, reasoning))
                journal.observe()
                prose(4 + 2 * index, message, body(index)).forEach { emit(it) }
                journal.observe()
            }
            tailSent.complete(Unit)
            terminalRelease.await()
            emit(terminal())
            journal.observe()
        }

        private fun terminal(): JsonObject = buildJsonObject {
            put("type", "response.completed")
            put(
                "response",
                buildJsonObject {
                    put("status", "completed")
                    put("output", JsonArray(listOf(reason, preface, completed.raw) + tail))
                },
            )
        }

        private fun body(index: Int): String = "$index:" + "b".repeat(1024)

        private fun reasoning(id: String): JsonObject = buildJsonObject {
            put("type", "reasoning")
            put("id", id)
            put("status", "completed")
            put("encrypted_content", id + "r".repeat(1024))
            put("summary", JsonArray(emptyList()))
            put("content", JsonArray(emptyList()))
        }

        private fun message(id: String, text: String): JsonObject = buildJsonObject {
            put("type", "message")
            put("id", id)
            put("status", "completed")
            put("role", "assistant")
            put("phase", "commentary")
            put("content", JsonArray(listOf(buildJsonObject { put("type", "output_text"); put("text", text) })))
        }

        private fun event(type: String, index: Int, item: JsonObject): JsonObject = buildJsonObject {
            put("type", type)
            put("output_index", index)
            put("item", item)
        }

        private fun prose(index: Int, item: JsonObject, text: String): List<JsonObject> = listOf(
            event("response.output_item.added", index, item),
            buildJsonObject {
                put("type", "response.output_text.delta")
                put("output_index", index)
                put("item_id", item.getValue("id"))
                put("delta", text)
            },
            event("response.output_item.done", index, item),
        )
    }

    protected suspend fun completeTailSource(manager: CodexCodeModeBridge, post: TailPost, read: SeenTool): SourceRun {
        val sink = SourceSink()
        manager.interceptor(turn(read.id, "result-0"), disableParallel = false)
            .intercept(transcript(listOf(read), post.text), sink, post)
        post.journal.observe()
        val edit = sink.callback.await()
        val calls = listOf(read, edit)
        val results = calls.mapIndexed { index, call -> CodeModeResult(call.id, "result-$index") }
        val outcome = manager.interceptor(turn(results = results), disableParallel = false)
            .intercept(transcript(calls, post.text), SourceSink(), post).turn()
        post.journal.observe()
        return SourceRun(calls, outcome)
    }

    protected suspend fun restoredTailInput(manager: CodexCodeModeBridge, client: String): JsonArray {
        var posted: JsonObject? = null
        manager.interceptor(turn(), disableParallel = false).intercept(client, SourceSink()) {
            posted = Json.parseToJsonElement(it).jsonObject
            RoundResult.Outcome(completedOutcome())
        }
        return checkNotNull(posted).getValue("input").jsonArray
    }

    protected fun canonicalPreface(): JsonObject = ResponsesAssistantText.item("A", AssistantPhase.COMMENTARY)
}

/** Filesystem bytes, including checkpoint replacements, rather than a count of persistence methods or writes. */
class TailJournalBytes(private val directory: Path) {
    private var identity: Any? = null
    private var size = 0L
    var persisted = 0L
        private set

    fun observe() {
        synchronized(this) {
            if (!Files.exists(directory)) return
            val file = Files.list(directory).use { it.toList().singleOrNull() } ?: return
            val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
            val next = attributes.size()
            persisted += if (identity == attributes.fileKey() && next >= size) next - size else next
            identity = attributes.fileKey()
            size = next
        }
    }
}
