// NEW: LAN share, round 13 of PR #419's review. What this machine last acknowledged of each joined machine's
// outbox: the notes its sessions were handed from there, up to which one. A note leaves that outbox only once a later
// collection acknowledges it, so an acknowledgement kept only in memory was lost to a restart between handing a note
// over and the next collection, which then handed it over again. Kept in <state>/share/acks.json, written after each
// collection that handed something over; a write the disk refuses is said in the log and the acknowledgement still goes
// with the next collection, so only a restart while the disk refuses can hand a note over twice. A file that cannot be
// read or does not parse reads as nothing acknowledged, as a first start does.
package splice.share.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.util.Cancellables
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.PathProbe
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.share.ShareOutboxAck
import java.nio.file.Path

/** [machine]'s outbox [box], acknowledged here up to note [after]. */
@Serializable
internal data class RemoteAck(val machine: String, val box: String, val after: Long)

@Serializable
internal data class RemoteAckBook(val acks: List<RemoteAck> = emptyList())

internal class RemoteAcks(private val file: Path, private val log: LogSink) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val held: MutableMap<String, ShareOutboxAck> by lazy { read() }

    /** What the next collection from [machine] acknowledges: null when nothing of its outbox was handed over here. */
    @Synchronized
    fun of(machine: String): ShareOutboxAck? = held[machine]

    /** Every note of [machine]'s outbox up to [ack] was handed to its session here. Blocks on the disk. */
    @Synchronized
    fun delivered(machine: String, ack: ShareOutboxAck) {
        held[machine] = ack
        val book = RemoteAckBook(held.map { (name, it) -> RemoteAck(name, it.box, it.after) }.sortedBy { it.machine })
        Cancellables.runCatchingCancellable {
            SecureFile.writeAtomic0600(file, json.encodeToString(RemoteAckBook.serializer(), book) + "\n")
        }.onFailure {
            log(
                "[remote ${LogSafe.str(machine)}] what its sessions were handed is not written, so a restart now " +
                    "would hand it over again: ${SafeFailureText.render(it)}\n",
            )
        }
    }

    private fun read(): MutableMap<String, ShareOutboxAck> {
        val text = PathProbe.text(file) ?: return mutableMapOf()
        return Cancellables.runCatchingCancellable { json.decodeFromString(RemoteAckBook.serializer(), text) }.fold(
            onSuccess = { book ->
                book.acks.associateTo(mutableMapOf()) { it.machine to ShareOutboxAck(it.box, it.after) }
            },
            onFailure = { mutableMapOf() },
        )
    }
}
