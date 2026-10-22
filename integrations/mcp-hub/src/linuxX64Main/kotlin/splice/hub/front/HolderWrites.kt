package splice.hub.front

import splice.hub.io.Bytes

fun interface HolderWritten {
    operator fun invoke()
}

/** Receipts follow byte offsets, so EAGAIN cannot settle a response still queued in user space. */
class HolderWrites(private val sys: Sys) {
    private data class Receipt(val through: Long, val delivered: HolderWritten)

    private val outbox = Bytes()
    private val receipts = ArrayDeque<Receipt>()
    private var queued = 0L
    private var written = 0L
    val pending: Boolean get() = outbox.size > 0

    fun accepts(line: String): Boolean =
        outbox.size.toLong() + line.encodeToByteArray().size + 1 <= MAX_QUEUED_OUTPUT_BYTES

    fun append(line: String, delivered: HolderWritten?) {
        val bytes = (line + "\n").encodeToByteArray()
        outbox.append(bytes, bytes.size)
        queued += bytes.size
        delivered?.let { receipts.addLast(Receipt(queued, it)) }
    }

    fun flush(fd: Int): Boolean {
        val before = outbox.size
        val available = sys.flush(fd, outbox)
        written += before - outbox.size
        while (receipts.firstOrNull()?.through?.let { it <= written } == true) {
            receipts.removeFirst().delivered()
        }
        return available
    }
}

private const val MAX_QUEUED_OUTPUT_BYTES = 1024 * 1024L
