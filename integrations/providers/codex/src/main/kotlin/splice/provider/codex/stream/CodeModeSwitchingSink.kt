// NEW: the current client attachment is serialized independently of the upstream source reader.
package splice.provider.codex.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.index.WireBlockIndex
import splice.core.reasoning.ReasoningReplay
import splice.core.util.JsonWire
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.IndependentRoundSink
import splice.upstream.sse.WireSink

internal fun interface CodeModeSourceObserver {
    suspend fun observe(event: CustomToolSource)
}

internal class CodeModeSwitchingSink(
    initial: WireSink,
    private val disposed: CodeModeExecutionDisposed = CodeModeExecutionDisposed { false },
    private val observer: CodeModeSourceObserver,
) : IndependentRoundSink {
    private val mutex = Mutex()
    private var target: WireSink? = initial
    private val blocks = CodeModeSinkBlocks()
    private val writes = CodeModeSinkWrites()
    private var readingScope: CoroutineScope? = null
    private val deliveredText = StringBuilder()
    private val native = mutableListOf<JsonElement>()
    var deliveredNative: List<JsonElement>? = null
        private set
    private var deliveredBytes = 0
    private var continuityComplete = true
    override val ownerScope: CoroutineScope get() = checkNotNull(readingScope)

    fun ownedBy(scope: CoroutineScope) {
        readingScope = scope
    }

    suspend fun attach(sink: WireSink) = mutex.withLock {
        check(target == null || target === sink) { "upstream round already has a client step" }
        target = sink
        writes.drain(sink)
    }

    suspend fun detach(): String? = mutex.withLock {
        deliveredNative = target?.let { native.toList().takeIf { continuityComplete } }
        native.clear()
        val delivered = target?.let {
            val text = deliveredText.toString().takeIf { continuityComplete }
            deliveredText.clear()
            blocks.detach(it)
            text
        }
        target = null
        delivered
    }

    /** [block] names the block the write belongs to; a write to a notice spends [notice] instead of the
     *  model's budget (V4-456). The block table is read under the lock, with the write. */
    private suspend fun write(
        bytes: Int = 0,
        block: WireBlockIndex? = null,
        notice: DetachedSpend = DetachedSpend.NOTICE_TEXT,
        action: BufferedSinkWrite,
    ) = mutex.withLock {
        if (disposed()) return@withLock
        val spend = if (block != null && blocks.isNotice(block)) notice else DetachedSpend.MODEL
        writes.deliver(target, bytes, spend, action)
    }

    private suspend fun open(
        kind: CodeModeSinkBlocks.Kind,
        id: String = "",
        name: String = "",
        raw: JsonObject? = null,
    ): WireBlockIndex = mutex.withLock {
        if (disposed()) return@withLock WireBlockIndex(0)
        val bytes = JsonWire.byteSize(id) + JsonWire.byteSize(name) + (raw?.let(JsonWire::byteSize) ?: 0L)
        require(bytes <= splice.upstream.codemode.CodeModeLimits.MAX_FRAME_BYTES) {
            "stream block exceeds byte budget"
        }
        val index = blocks.open(null, kind, id, name, raw)
        writes.deliver(target, bytes.toInt(), DetachedSpend.MODEL) { sink -> blocks.destination(sink, index) }
        index
    }

    override suspend fun openText(): WireBlockIndex = open(CodeModeSinkBlocks.Kind.TEXT)

    override suspend fun openThinking(): WireBlockIndex = open(CodeModeSinkBlocks.Kind.THINKING)

    /** A notice opens in its first delta's own locked write, never here: a cut between the writer's open
     *  and its first delta then leaves no empty signed block, and the open spends nothing. */
    override suspend fun openNotice(): WireBlockIndex =
        mutex.withLock { blocks.open(null, CodeModeSinkBlocks.Kind.NOTICE) }

    override suspend fun openTool(id: String, name: String): WireBlockIndex =
        open(CodeModeSinkBlocks.Kind.TOOL, id, name)

    override suspend fun openRawBlock(contentBlock: JsonObject): WireBlockIndex =
        open(CodeModeSinkBlocks.Kind.RAW, raw = contentBlock)

    override suspend fun textDelta(index: WireBlockIndex, text: String) {
        write(text.encodeToByteArray().size) { sink ->
            blocks.destination(sink, index)?.let {
                sink.textDelta(it, text)
                rememberText(text)
            }
        }
    }

    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) {
        write(thinking.encodeToByteArray().size, index) { sink ->
            blocks.destination(sink, index)?.let { sink.thinkingDelta(it, thinking) }
        }
    }

    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) {
        write(partialJson.encodeToByteArray().size) { sink ->
            blocks.destination(sink, index)?.let { sink.inputJsonDelta(it, partialJson) }
        }
    }

    override suspend fun signatureDelta(index: WireBlockIndex, signature: String) {
        write(signature.encodeToByteArray().size, index, DetachedSpend.NOTICE_FRAME) { sink ->
            blocks.sign(sink, index, signature)
        }
    }

    override suspend fun rawDelta(index: WireBlockIndex, delta: JsonObject) {
        write(Math.toIntExact(JsonWire.byteSize(delta))) { sink ->
            blocks.destination(sink, index)?.let { sink.rawDelta(it, delta) }
        }
    }

    override suspend fun closeBlock(index: WireBlockIndex) {
        write(block = index, notice = DetachedSpend.NOTICE_FRAME) { blocks.close(it, index) }
    }

    override suspend fun closeAll() {
        write { blocks.closeAll(it) }
    }

    override suspend fun addTextBlock(text: String) {
        write(text.encodeToByteArray().size) {
            it.addTextBlock(text)
            rememberText(text)
        }
    }

    private fun rememberText(text: String) {
        if (rememberBytes(text.encodeToByteArray().size)) deliveredText.append(text)
    }

    private fun rememberBytes(bytes: Int): Boolean {
        if (!continuityComplete) return false
        if (bytes > splice.upstream.codemode.CodeModeLimits.MAX_FRAME_BYTES - deliveredBytes) {
            continuityComplete = false
            deliveredText.clear()
            native.clear()
            return false
        }
        deliveredBytes += bytes
        return true
    }

    override suspend fun addRedactedThinking(data: String) {
        write(data.encodeToByteArray().size) {
            it.addRedactedThinking(data)
            ReasoningReplay.decodeReasoningEnvelope(data)?.let { item ->
                if (rememberBytes(Math.toIntExact(JsonWire.byteSize(item)))) native += item
            }
        }
    }

    override suspend fun customToolSource(event: CustomToolSource) {
        observer.observe(event)
    }
}
