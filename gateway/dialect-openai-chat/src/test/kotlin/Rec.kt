// NEW: the chat stream tests' shared drive harness. Extracted from ChatStreamTranslatorTest when
// CX-18's usage cases pushed that class past detekt's LargeClass budget: the split needed the
// recording sink in two files, and a second pasted copy is the drift this repo keeps paying for,
// so there is ONE definition here instead. Internal, not private, precisely so both suites share it.
// (ChatToolFoldTest still carries its own older copy — pre-existing, untouched by this change.)
import kotlinx.coroutines.flow.asFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.index.WireBlockIndex
import splice.core.turn.TurnOutcome
import splice.dialect.chat.ChatStreamTranslator
import splice.dialect.chat.ChatTurnContext
import splice.spi.WireSink

internal class Rec : WireSink {
    val calls = mutableListOf<String>()
    val toolOpens = mutableListOf<Pair<String, String>>() // (id, name) — inspect ids without disturbing `calls`
    private var n = 0
    override suspend fun openText() = WireBlockIndex(n++).also { calls.add("openText") }
    override suspend fun openThinking() = WireBlockIndex(n++).also { calls.add("openThinking") }
    override suspend fun openTool(id: String, name: String) = WireBlockIndex(n++).also {
        calls.add("openTool:$name")
        toolOpens.add(id to name)
    }
    override suspend fun textDelta(index: WireBlockIndex, text: String) { calls.add("text:$text") }
    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) { calls.add("think:$thinking") }
    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) { calls.add("json:$partialJson") }

    // DR-143: the INDEX is the assertion. Recording a bare "close" made block pairing and ordering
    // unrepresentable, so "block 0 closed before block 1 opened" could not be written as a test at
    // all — the harness, not the suite, was why chat could ship overlapping blocks.
    override suspend fun closeBlock(index: WireBlockIndex) { calls.add("close#${index.value}") }
    override suspend fun closeAll() { calls.add("closeAll") }
    override suspend fun addTextBlock(text: String) { calls.add("addText:$text") }
    override suspend fun addRedactedThinking(data: String) = Unit
}

internal fun ev(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
internal fun ctx() = ChatTurnContext({ false }, { null }, 180_000, 900_000)

internal fun firedCtx(fired: splice.spi.WatchdogFired?) = ChatTurnContext({ false }, { fired }, 180_000, 900_000)

internal suspend fun driveEvents(vararg evs: JsonObject): TurnOutcome =
    ChatStreamTranslator(ctx()).driveTurn(evs.toList().asFlow(), Rec())
