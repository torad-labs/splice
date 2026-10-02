// NEW: the current client attachment is serialized independently of the upstream source reader.
package splice.provider.codex.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import splice.core.index.WireBlockIndex
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.IndependentRoundSink
import splice.upstream.sse.WireSink

internal fun interface CodeModeSourceObserver {
    suspend fun observe(event: CustomToolSource)
}

internal class CodeModeSwitchingSink(
    initial: WireSink,
    private val observer: CodeModeSourceObserver,
) : IndependentRoundSink {
    private val mutex = Mutex()
    private var target: WireSink? = initial
    private val blocks = CodeModeSinkBlocks()
    private val writes = CodeModeSinkWrites()
    private var readingScope: CoroutineScope? = null
    override val ownerScope: CoroutineScope get() = checkNotNull(readingScope)

    fun ownedBy(scope: CoroutineScope) {
        readingScope = scope
    }

    suspend fun attach(sink: WireSink) = mutex.withLock {
        check(target == null || target === sink) { "upstream round already has a client step" }
        target = sink
        writes.drain(sink)
    }

    suspend fun detach() = mutex.withLock {
        target?.let { blocks.detach(it) }
        target = null
    }

    /** [block] names the block the write belongs to; a write to a notice block is optional (V4-456). */
    private suspend fun write(
        bytes: Int = 0,
        block: WireBlockIndex? = null,
        action: BufferedSinkWrite,
    ) = mutex.withLock {
        writes.deliver(target, bytes, block != null && blocks.isNotice(block), action)
    }

    private suspend fun open(
        kind: CodeModeSinkBlocks.Kind,
        id: String = "",
        name: String = "",
        raw: JsonObject? = null,
    ): WireBlockIndex = mutex.withLock {
        val bytes = id.encodeToByteArray().size.toLong() + name.encodeToByteArray().size +
            (raw?.toString()?.encodeToByteArray()?.size ?: 0)
        require(bytes <= splice.upstream.codemode.CodeModeLimits.MAX_FRAME_BYTES) {
            "stream block exceeds byte budget"
        }
        val index = blocks.open(null, kind, id, name, raw)
        writes.deliver(target, bytes.toInt()) { sink -> blocks.destination(sink, index) }
        index
    }

    override suspend fun openText(): WireBlockIndex = open(CodeModeSinkBlocks.Kind.TEXT)

    override suspend fun openThinking(): WireBlockIndex = open(CodeModeSinkBlocks.Kind.THINKING)

    override suspend fun openNotice(): WireBlockIndex = open(CodeModeSinkBlocks.Kind.NOTICE)

    override suspend fun openTool(id: String, name: String): WireBlockIndex =
        open(CodeModeSinkBlocks.Kind.TOOL, id, name)

    override suspend fun openRawBlock(contentBlock: JsonObject): WireBlockIndex =
        open(CodeModeSinkBlocks.Kind.RAW, raw = contentBlock)

    override suspend fun textDelta(index: WireBlockIndex, text: String) {
        write(text.encodeToByteArray().size) { sink ->
            blocks.destination(sink, index)?.let { sink.textDelta(it, text) }
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
        write(signature.encodeToByteArray().size, index) { sink ->
            blocks.signatureDestination(sink, index)?.let { sink.signatureDelta(it, signature) }
        }
    }

    override suspend fun rawDelta(index: WireBlockIndex, delta: JsonObject) {
        write(delta.toString().encodeToByteArray().size) { sink ->
            blocks.destination(sink, index)?.let { sink.rawDelta(it, delta) }
        }
    }

    override suspend fun closeBlock(index: WireBlockIndex) {
        write { blocks.close(it, index) }
    }

    override suspend fun closeAll() {
        write { blocks.closeAll(it) }
    }

    override suspend fun addTextBlock(text: String) {
        write(text.encodeToByteArray().size) { it.addTextBlock(text) }
    }

    override suspend fun addRedactedThinking(data: String) {
        write(data.encodeToByteArray().size) { it.addRedactedThinking(data) }
    }

    override suspend fun customToolSource(event: CustomToolSource) {
        observer.observe(event)
    }
}
