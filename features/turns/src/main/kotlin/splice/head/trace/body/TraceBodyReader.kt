// NEW: V4-457 selected body hydration with digest and bounds validation.
package splice.head.trace.body

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Reads only selected body references; missing and corrupt content is never read as empty. */
internal class TraceBodyReader(private val file: Path) : AutoCloseable {
    private val channel = Cancellables.runCatchingCancellable {
        FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    }.getOrElse { failure -> throw IOException("trace body chunk pack missing or unreadable: $file", failure) }

    private val boundaries = HashSet<Long>()
    private var scanned = TRACE_PACK_START_BYTES.toLong()

    init {
        var ready = false
        Cancellables.withCleanup({ if (!ready) close() }) {
            TracePackBytes.generation(channel)
            ready = true
        }
    }

    fun literal(parts: JsonArray): JsonPrimitive {
        val literal = ByteArrayOutputStream()
        parts.forEach { literal.write(chunk(it)) }
        return Cancellables.runCatchingCancellable {
            val primitive = Json.parseToJsonElement(literal.toString(Charsets.UTF_8)) as? JsonPrimitive
            if (primitive?.isString != true) throw IOException("invalid trace body chunk literal: $file")
            primitive
        }.getOrElse { failure -> throw IOException("invalid trace body chunk literal: $file", failure) }
    }

    private fun chunk(element: JsonElement): ByteArray {
        val part = required(element as? JsonObject)
        val hash = required(JsonScalars.str(part["hash"]))
        val offset = required((part["offset"] as? JsonPrimitive)?.longOrNull)
        val length = required((part["bytes"] as? JsonPrimitive)?.intOrNull)
        bounds(offset, length)
        if (!entry(offset) || !TracePackBytes.headerMatches(channel, offset, length, hash)) {
            throw IOException("invalid trace body chunk entry at byte $offset: $file")
        }
        val bytes = TracePackBytes.read(channel, offset, length)
        if (TracePackBytes.hashOf(bytes) != hash) throw IOException("corrupt trace body chunk at byte $offset: $file")
        return bytes
    }

    private fun bounds(offset: Long, length: Int) {
        val first = TRACE_PACK_START_BYTES + TRACE_PACK_HEADER_BYTES
        val inside = offset >= first && offset <= channel.size() - length
        if (!inside || length !in 1..CHUNK_MAX) throw IOException("invalid trace body chunk bounds: $file")
    }

    /** Scan headers, never unselected payloads, to reject references starting inside an entry. */
    private fun entry(offset: Long): Boolean {
        while (scanned < offset) {
            val header = ByteBuffer.wrap(TracePackBytes.read(channel, scanned, TRACE_PACK_HEADER_BYTES))
            val length = header.int
            val payload = scanned + TRACE_PACK_HEADER_BYTES
            if (length !in 1..CHUNK_MAX || payload > channel.size() - length) {
                throw IOException("invalid trace body chunk header at byte $scanned: $file")
            }
            boundaries.add(payload)
            scanned = payload + length
        }
        return offset in boundaries
    }

    private fun <T : Any> required(value: T?): T =
        value ?: throw IOException("invalid trace body chunk reference: $file")

    override fun close() = channel.close()
}
