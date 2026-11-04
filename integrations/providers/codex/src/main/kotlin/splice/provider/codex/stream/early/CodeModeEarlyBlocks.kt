// NEW: Marcos, Nov 3, 2026, option 1 ("like native claude code"): a script that opens with tools.Write shows Claude
// Code its Write(path) block while GPT still writes the file, and streams the content into it as a native Write does.
// The runtime's first call takes the block over when it is that Write; any other end closes the block so the client
// refuses it and writes nothing. The Mac session's 10:26 PM CT turn showed nothing for 5.5 minutes while one script
// wrote a 52 KB stylesheet.
package splice.provider.codex.stream.early

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.index.WireBlockIndex
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.provider.codex.CODE_MODE_CLIENT_ID_PREFIX
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink
import java.util.UUID

/** Claude Code's file-writing tool, the one a script's opening call can stream into. */
internal const val CODE_MODE_EARLY_WRITE_TOOL: String = "Write"

/** The Write block a round opened on a client ahead of its script: where it lives, the path it names, and the content
 *  streamed into it so far. */
internal data class CodeModeEarlyBlock(
    val id: String,
    val path: String,
    val sink: WireSink,
    val index: WireBlockIndex,
) {
    val streamed = StringBuilder()
    var claimed = false

    /** Whether [name] and [arguments] are this Write: its path, and a content that starts with what streamed. */
    fun writes(name: String, arguments: JsonObject): Boolean {
        val shaped = name == CODE_MODE_EARLY_WRITE_TOOL && arguments.keys == setOf(WRITE_PATH, WRITE_CONTENT)
        val strings = arguments.values.all { (it as? JsonPrimitive)?.isString == true }
        val samePath = JsonScalars.strIfString(arguments[WRITE_PATH]) == path
        val grows = JsonScalars.strIfString(arguments[WRITE_CONTENT]).startsWith(streamed)
        return shaped && strings && samePath && grows
    }
}

/** How detaching a round's client ends its early Write block. */
internal enum class CodeModeEarlyEnd {
    /** The runtime asked for calls: the first may take the block over. */
    KEEP,

    /** A call is going out: a block no call took over is refused, and one a call took over stays for it. */
    REFUSE_UNCLAIMED,

    /** The step ended: a block still open is refused, taken over or not. */
    ABANDON,
}

/** One round's early Write. Access is serialized by the switching sink's attachment mutex. */
internal class CodeModeEarlyBlocks {
    /** Set once, before the round reads, when the client has a Write tool to stream into. */
    var enabled = false
    private var callId: String? = null
    private var reader: CodeModeEarlyWrite? = null
    private var sealed = false
    private var open: CodeModeEarlyBlock? = null

    /** [event] from the round's script stream, with the client sink attached now and whether the round holds no block
     *  of its own, so an early block never opens inside another. */
    suspend fun observe(event: CustomToolSource, target: WireSink?, idle: Boolean) {
        if (!enabled) return
        when (event) {
            is CustomToolSource.Started -> start(event, target, idle)
            is CustomToolSource.Delta -> if (event.callId == callId) feed(event.text, target)
            is CustomToolSource.Completed -> if (event.call.callId == callId) {
                reader = null
                sealed = true
            }
            is CustomToolSource.Continuity, CustomToolSource.Terminal -> Unit
        }
    }

    /** A response's first script streams; a later one in the same response does not. A script that starts while the
     *  first is still unsealed is its retry, written again from the top. */
    private suspend fun start(event: CustomToolSource.Started, target: WireSink?, idle: Boolean) {
        val first = callId
        val later = first != null && sealed
        if (first == event.call.callId || later) return
        refuse()
        callId = null
        if (target == null || !idle) return
        callId = event.call.callId
        sealed = false
        reader = CodeModeEarlyWrite()
        feed(event.call.input, target)
    }

    private suspend fun feed(text: String, target: WireSink?) {
        val events = reader?.feed(text) ?: return
        for (event in events) {
            when (event) {
                is CodeModeEarlyWrite.Event.Opened -> openOn(target, event.path)
                is CodeModeEarlyWrite.Event.Content -> open?.takeUnless { it.claimed }?.let { block ->
                    block.sink.inputJsonDelta(block.index, escaped(event.text))
                    block.streamed.append(event.text)
                }
            }
        }
    }

    private suspend fun openOn(target: WireSink?, path: String) {
        if (target == null) {
            reader = null
            return
        }
        val id = "$CODE_MODE_CLIENT_ID_PREFIX${UUID.randomUUID()}"
        val index = target.openTool(id, CODE_MODE_EARLY_WRITE_TOOL)
        target.inputJsonDelta(index, "{\"$WRITE_PATH\":${JsonWire.string(JsonPrimitive(path))},\"$WRITE_CONTENT\":\"")
        open = CodeModeEarlyBlock(id, path, target, index)
    }

    /** The open block's id when [name] and [arguments] are the Write it streamed, its content the streamed text and
     *  whatever followed it: the call takes the block over and no refusal can close it. */
    fun claim(name: String, arguments: JsonObject): String? {
        val block = open?.takeIf { !it.claimed && it.writes(name, arguments) } ?: return null
        block.claimed = true
        return block.id
    }

    /** Ends the claimed block [clientId] on [sink] with the rest of [arguments]' content. False when no claimed block
     *  is [clientId] on [sink]; a claimed block on another client's stream is forgotten with that stream. */
    suspend fun finish(sink: WireSink, clientId: String, arguments: JsonObject): Boolean {
        val block = open?.takeIf { it.claimed && it.id == clientId } ?: return false
        open = null
        if (block.sink !== sink) return false
        val content = JsonScalars.strIfString(arguments[WRITE_CONTENT])
        block.sink.inputJsonDelta(block.index, escaped(content.substring(block.streamed.length)) + "\"}")
        block.sink.closeBlock(block.index)
        return true
    }

    /** Closes an open block no call took over, with a parameter Write does not take, so the client refuses it. A
     *  script whose block has not opened yet stops streaming: something else holds the client now. */
    suspend fun refuse() {
        reader = null
        val block = open?.takeUnless { it.claimed } ?: return
        open = null
        val reason = JsonWire.string(JsonPrimitive(NOT_WRITTEN_REASON))
        block.sink.inputJsonDelta(block.index, "\",\"$NOT_WRITTEN_KEY\":$reason}")
        block.sink.closeBlock(block.index)
    }

    /** A detach ends the block as [how] says. */
    suspend fun end(how: CodeModeEarlyEnd) {
        when (how) {
            CodeModeEarlyEnd.KEEP -> Unit
            CodeModeEarlyEnd.REFUSE_UNCLAIMED -> refuse()
            CodeModeEarlyEnd.ABANDON -> abandon()
        }
    }

    /** The step ended: a block a call took over but never finished, because the call failed before it went out,
     *  closes the way one no call took over does. */
    suspend fun abandon() {
        open?.claimed = false
        refuse()
    }

    /** The client step dropped its draft, the block with it, so no frame may name it again. */
    fun discard() {
        open = null
        reader = null
        callId = null
        sealed = false
    }

    /** [text] as it reads inside a JSON string: escaping is per character, so the parts join into the whole. */
    private fun escaped(text: String): String =
        JsonWire.string(JsonPrimitive(text)).let { it.substring(1, it.length - 1) }
}

/** Claude Code's Write tool parameters, the two keys a streamed Write block carries. */
private const val WRITE_PATH = "file_path"
private const val WRITE_CONTENT = "content"

/** The parameter Write does not take, which makes Claude Code refuse a block no call took over. */
private const val NOT_WRITTEN_KEY = "splice_not_written"
private const val NOT_WRITTEN_REASON =
    "the script did not make this Write as it streamed; its own call, if it made one, follows"
