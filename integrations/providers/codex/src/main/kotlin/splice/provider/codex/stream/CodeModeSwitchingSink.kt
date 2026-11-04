// NEW: the current client attachment is serialized independently of the upstream source reader.
package splice.provider.codex.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.index.WireBlockIndex
import splice.core.util.JsonWire
import splice.provider.codex.stream.sink.CodeModeSinkContent
import splice.provider.codex.stream.sink.CodeModeSinkOpens
import splice.provider.codex.stream.source.CodeModeDeliveredText
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
    val deliveredText: CodeModeDeliveredText get() = core.deliveredText
    override val ownerScope: CoroutineScope get() = checkNotNull(readingScope)

    fun ownedBy(scope: CoroutineScope) {
        readingScope = scope
    }

    suspend fun attach(sink: WireSink) = core.attach(sink)

    /** [refuseEarly] false keeps the early Write block open for the calls a step is about to issue. */
    suspend fun detach(refuseEarly: Boolean = true): String? = core.detach(refuseEarly)

    suspend fun confirmedText(): CodeModeDeliveredText = core.confirmedText()

    /** Streams a script's opening Write into the client's Write block (CodeModeEarlyBlocks); called before reading. */
    fun streamEarlyWrites() {
        core.early.enabled = true
    }

    /** The early Write block this round streamed, when [name] and [arguments] are that Write (CodeModeEarlyBlocks). */
    suspend fun claimEarly(name: String, arguments: JsonObject): String? = core.claimEarly(name, arguments)

    suspend fun finishEarly(sink: WireSink, clientId: String, arguments: JsonObject): Boolean =
        core.finishEarly(sink, clientId, arguments)
}

/** The state every part shares: the attachment lock, the client sink now attached, the block table, the writes held
 *  for a step, and the delivered record. */
internal class CodeModeSinkCore(initial: WireSink) {
    val mutex = Mutex()
    var target: WireSink? = initial
    val blocks = CodeModeSinkBlocks()
    val writes = CodeModeSinkWrites()
    val early = CodeModeEarlyBlocks()
    var deliveredNative: List<JsonElement>? = null
        private set
    var deliveredText: CodeModeDeliveredText = CodeModeDeliveredText(0, "")
        private set
    private var textOffset = 0
    private val text = StringBuilder()
    private val native = mutableListOf<JsonElement>()
    private var spent = 0
    private var complete = true

    suspend fun attach(sink: WireSink) = mutex.withLock {
        check(target == null || target === sink) { "upstream round already has a client step" }
        target = sink
        writes.drain(sink)
    }

    suspend fun detach(refuseEarly: Boolean = true): String? = mutex.withLock {
        if (refuseEarly) early.refuse()
        val sink = target
        deliveredNative = sink?.let { native.toList().takeIf { complete } }
        native.clear()
        val text = sink?.let {
            val kept = text.toString().takeIf { complete }
            deliveredText = CodeModeDeliveredText(textOffset, kept)
            textOffset += text.length
            text.clear()
            blocks.detach(it)
            kept
        }
        target = null
        text
    }

    suspend fun confirmedText(): CodeModeDeliveredText = mutex.withLock {
        CodeModeDeliveredText(textOffset, text.toString().takeIf { complete })
    }

    /** The block table is read under the lock, with the write. */
    suspend fun write(bytes: Int = 0, action: BufferedSinkWrite) = mutex.withLock {
        writes.deliver(target, bytes, action)
    }

    suspend fun open(
        kind: CodeModeSinkBlocks.Kind,
        id: String = "",
        name: String = "",
        raw: JsonObject? = null,
    ): WireBlockIndex = mutex.withLock {
        early.refuse()
        val bytes = JsonWire.byteSize(id) + JsonWire.byteSize(name) + (raw?.let(JsonWire::byteSize) ?: 0L)
        require(bytes <= CodeModeLimits.MAX_FRAME_BYTES) { "stream block exceeds byte budget" }
        val index = blocks.open(null, kind, id, name, raw)
        writes.deliver(target, bytes.toInt()) { sink -> blocks.destination(sink, index) }
        index
    }

    /** The script stream reaches the early Write before the source capture: a block opens only while the round holds
     *  none of its own. */
    suspend fun observe(event: CustomToolSource) = mutex.withLock { early.observe(event, target, blocks.idle()) }

    suspend fun claimEarly(name: String, arguments: JsonObject): String? =
        mutex.withLock { early.claim(name, arguments) }

    suspend fun finishEarly(sink: WireSink, clientId: String, arguments: JsonObject): Boolean =
        mutex.withLock { early.finish(sink, clientId, arguments) }

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
        textOffset = 0
        deliveredText = CodeModeDeliveredText(0, "")
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
