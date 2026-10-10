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
import splice.upstream.codemode.CodeModeLimits
import splice.upstream.sse.BlockDeltas
import splice.upstream.sse.BlockEnding
import splice.upstream.sse.BlockOpening
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.IndependentRoundSink
import splice.upstream.sse.WholeBlocks
import splice.upstream.sse.WireSink

internal fun interface CodeModeSourceObserver {
    suspend fun observe(event: CustomToolSource)
}

/** One upstream round's sink, which a client step attaches to and detaches from. The attachment, the block table
 *  and the delivered record live in [CodeModeSinkCore]; each part of the content grammar writes through it. */
internal class CodeModeSwitchingSink(
    initial: WireSink,
    private val core: CodeModeSinkCore = CodeModeSinkCore(initial),
    content: CodeModeSinkContent = CodeModeSinkContent(core),
    observer: CodeModeSourceObserver,
) : IndependentRoundSink,
    BlockOpening by CodeModeSinkOpens(core, observer),
    BlockDeltas by content,
    WholeBlocks by content,
    BlockEnding by content {
    private var readingScope: CoroutineScope? = null
    val deliveredNative: List<JsonElement>? get() = core.deliveredNative
    override val ownerScope: CoroutineScope get() = checkNotNull(readingScope)

    fun ownedBy(scope: CoroutineScope) {
        readingScope = scope
    }

    suspend fun attach(sink: WireSink) = core.attach(sink)

    suspend fun detach(): String? = core.detach()
}

/** The state every part shares: the attachment lock, the client sink now attached, the block table, the writes held
 *  for a step, and the delivered record. */
internal class CodeModeSinkCore(initial: WireSink) {
    val mutex = Mutex()
    var target: WireSink? = initial
    val blocks = CodeModeSinkBlocks()
    val writes = CodeModeSinkWrites()
    var deliveredNative: List<JsonElement>? = null
        private set
    private val text = StringBuilder()
    private val native = mutableListOf<JsonElement>()
    private var spent = 0
    private var complete = true

    suspend fun attach(sink: WireSink) = mutex.withLock {
        check(target == null || target === sink) { "upstream round already has a client step" }
        target = sink
        writes.drain(sink)
    }

    suspend fun detach(): String? = mutex.withLock {
        val sink = target
        deliveredNative = sink?.let { native.toList().takeIf { complete } }
        native.clear()
        val text = sink?.let {
            val kept = text.toString().takeIf { complete }
            text.clear()
            blocks.detach(it)
            kept
        }
        target = null
        text
    }

    /** [block] names the block the write belongs to; a write to a notice spends [notice] instead of the
     *  model's budget (V4-456). The block table is read under the lock, with the write. */
    suspend fun write(
        bytes: Int = 0,
        block: WireBlockIndex? = null,
        notice: DetachedSpend = DetachedSpend.NOTICE_TEXT,
        action: BufferedSinkWrite,
    ) = mutex.withLock {
        val spend = if (block != null && blocks.isNotice(block)) notice else DetachedSpend.MODEL
        writes.deliver(target, bytes, spend, action)
    }

    suspend fun open(
        kind: CodeModeSinkBlocks.Kind,
        id: String = "",
        name: String = "",
        raw: JsonObject? = null,
    ): WireBlockIndex = mutex.withLock {
        val bytes = JsonWire.byteSize(id) + JsonWire.byteSize(name) + (raw?.let(JsonWire::byteSize) ?: 0L)
        require(bytes <= CodeModeLimits.MAX_FRAME_BYTES) { "stream block exceeds byte budget" }
        val index = blocks.open(null, kind, id, name, raw)
        writes.deliver(target, bytes.toInt(), DetachedSpend.MODEL) { sink -> blocks.destination(sink, index) }
        index
    }

    /** What the client step was given, kept only while it fits one frame's budget: a longer record could not be
     *  replayed. */
    fun rememberText(chunk: String) {
        if (spend(chunk.encodeToByteArray().size)) text.append(chunk)
    }

    fun rememberNative(item: JsonElement) {
        if (spend(Math.toIntExact(JsonWire.byteSize(item)))) native += item
    }

    fun resetDelivered() {
        text.clear()
        native.clear()
        spent = 0
        complete = true
    }

    private fun spend(bytes: Int): Boolean {
        if (!complete) return false
        if (bytes > CodeModeLimits.MAX_FRAME_BYTES - spent) {
            complete = false
            text.clear()
            native.clear()
            return false
        }
        spent += bytes
        return true
    }
}

/** Opening blocks for the client step. */
internal class CodeModeSinkOpens(
    private val core: CodeModeSinkCore,
    private val observer: CodeModeSourceObserver,
) : BlockOpening {
    override suspend fun openThinking(): WireBlockIndex = core.open(CodeModeSinkBlocks.Kind.THINKING)

    override suspend fun openText(): WireBlockIndex = core.open(CodeModeSinkBlocks.Kind.TEXT)

    /** A notice opens in its first delta's own locked write, never here: a cut between the writer's open
     *  and its first delta then leaves no empty signed block, and the open spends nothing. */
    override suspend fun openNotice(): WireBlockIndex =
        core.mutex.withLock { core.blocks.open(null, CodeModeSinkBlocks.Kind.NOTICE) }

    override suspend fun openTool(id: String, name: String): WireBlockIndex =
        core.open(CodeModeSinkBlocks.Kind.TOOL, id, name)

    override suspend fun openRawBlock(contentBlock: JsonObject): WireBlockIndex =
        core.open(CodeModeSinkBlocks.Kind.RAW, raw = contentBlock)

    override suspend fun customToolSource(event: CustomToolSource) {
        observer.observe(event)
    }
}

/** Content written into opened blocks, whole blocks and endings, each spent against the round's byte budget. */
internal class CodeModeSinkContent(private val core: CodeModeSinkCore) : BlockDeltas, WholeBlocks, BlockEnding {
    override suspend fun textDelta(index: WireBlockIndex, text: String) {
        core.write(text.encodeToByteArray().size) { sink ->
            core.blocks.destination(sink, index)?.let {
                sink.textDelta(it, text)
                core.rememberText(text)
            }
        }
    }

    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) {
        core.write(thinking.encodeToByteArray().size, index) { sink ->
            core.blocks.destination(sink, index)?.let { sink.thinkingDelta(it, thinking) }
        }
    }

    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) {
        core.write(partialJson.encodeToByteArray().size) { sink ->
            core.blocks.destination(sink, index)?.let { sink.inputJsonDelta(it, partialJson) }
        }
    }

    override suspend fun signatureDelta(index: WireBlockIndex, signature: String) {
        core.write(signature.encodeToByteArray().size, index, DetachedSpend.NOTICE_FRAME) { sink ->
            core.blocks.sign(sink, index, signature)
        }
    }

    override suspend fun rawDelta(index: WireBlockIndex, delta: JsonObject) {
        core.write(Math.toIntExact(JsonWire.byteSize(delta))) { sink ->
            core.blocks.destination(sink, index)?.let { sink.rawDelta(it, delta) }
        }
    }

    override suspend fun addTextBlock(text: String) {
        core.write(text.encodeToByteArray().size) {
            it.addTextBlock(text)
            core.rememberText(text)
        }
    }

    override suspend fun addRedactedThinking(data: String) {
        core.write(data.encodeToByteArray().size) {
            it.addRedactedThinking(data)
            ReasoningReplay.decodeReasoningEnvelope(data)?.let(core::rememberNative)
        }
    }

    override suspend fun closeBlock(index: WireBlockIndex) {
        core.write(block = index, notice = DetachedSpend.NOTICE_FRAME) { core.blocks.close(it, index) }
    }

    override suspend fun closeAll() {
        core.write { core.blocks.closeAll(it) }
    }

    /** A websocket round re-served over SSE answers on this same sink, so its draft is dropped whole and at once:
     *  what the client step holds, the block mappings that name the dropped blocks (a later closeAll would close
     *  them on a buffer that no longer opens them), the writes still waiting for a step, and the continuity the
     *  draft would have reported as delivered. It runs after the failed round's reader has ended (the NeedsSse
     *  branch) and before the SSE round writes, so no write holds the attachment lock against it. */
    override fun discard() {
        core.target?.discard()
        core.blocks.discard()
        core.writes.discard()
        core.resetDelivered()
    }
}
