// NEW: desktop-antigravity peer inbox port (docs/specs/desktop-antigravity.md). Lets `splice desktop acp` open an
// owner-only peer UDS inbox when a Desktop session starts, advertise it in `<config>/sessions/<pid>.json`, and feed
// cross-session messages directly into DesktopSession and agy's `session/prompt`.
package splice.provider.antigravity.peer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/** How [DesktopSession] disposed of one inbound peer message, returned as a PeerWire receipt. */
public sealed class DesktopPeerOutcome {
    /** Replayed into Desktop and forwarded to agy's `session/prompt`. */
    public data object Delivered : DesktopPeerOutcome()

    /** Not delivered to agy, with the reason reported in the `dropped` receipt. */
    public data class Dropped(public val detail: String) : DesktopPeerOutcome()
}

private enum class DeliveryGate { PENDING, CLAIMED, SUBMITTING, EXPIRED }

/** One inbound peer message ready for DesktopSession to replay and forward to agy. An atomic state machine
 *  (`PENDING -> CLAIMED -> SUBMITTING`, or `PENDING|CLAIMED -> EXPIRED`) guarantees a message that timed out before
 *  `session/prompt` submission is never forwarded to agy after a `dropped` receipt was sent. */
public class DesktopPeerMessage(
    public val content: String,
    public val outcome: CompletableDeferred<DesktopPeerOutcome>,
) {
    private val gate = AtomicReference(DeliveryGate.PENDING)

    /** True once the sender timed out or the inbox closed before submission began. */
    public val isExpired: Boolean get() = gate.get() == DeliveryGate.EXPIRED

    /** Atomically claims exclusive ownership of preparing this message (`PENDING -> CLAIMED`). */
    public fun claimForDelivery(): Boolean = gate.compareAndSet(DeliveryGate.PENDING, DeliveryGate.CLAIMED)

    /** Atomically commits to submitting `session/prompt` to agy (`CLAIMED -> SUBMITTING`). Returns false if a
     *  timeout or close already transitioned the message to `EXPIRED`. */
    public fun beginSubmit(): Boolean = gate.compareAndSet(DeliveryGate.CLAIMED, DeliveryGate.SUBMITTING)

    /** Atomically expires a message that has not yet entered `SUBMITTING` (`PENDING|CLAIMED -> EXPIRED`). Returns
     *  false if submission to agy already began or the message was already expired. */
    public fun expireBeforeSubmit(detail: String): Boolean {
        while (true) {
            val current = gate.get()
            if (current == DeliveryGate.SUBMITTING || current == DeliveryGate.EXPIRED) return false
            if (gate.compareAndSet(current, DeliveryGate.EXPIRED)) {
                outcome.complete(DesktopPeerOutcome.Dropped(detail))
                return true
            }
        }
    }

    /** Alias for [expireBeforeSubmit], used when closing the inbox or draining queued messages. */
    public fun expire(detail: String): Boolean = expireBeforeSubmit(detail)

    /** Builds the Desktop stream-json `user` frame for this peer message under [uuid] and [body]. */
    public fun userFrame(uuid: String, body: JsonElement): JsonObject = buildJsonObject {
        put("type", "user")
        putJsonObject("message") {
            put("role", "user")
            put("content", body)
        }
        put("parent_tool_use_id", JsonNull)
        put("priority", "next")
        put("uuid", uuid)
    }

    /** Maps a delivery attempt result into a [DesktopPeerOutcome]. */
    public fun outcomeFor(delivered: Boolean, switchingOut: Boolean, fallback: String): DesktopPeerOutcome = when {
        delivered -> DesktopPeerOutcome.Delivered
        switchingOut -> DesktopPeerOutcome.Dropped("session is switching models")
        else -> DesktopPeerOutcome.Dropped(fallback)
    }

    /** Settles an unsettled message (pending or claimed) on unexpected abort or cancellation so senders never hang. */
    public fun abortIfUnsettled(detail: String) {
        if (outcome.isCompleted) return
        gate.set(DeliveryGate.EXPIRED)
        outcome.complete(DesktopPeerOutcome.Dropped(detail))
    }
}

/** A live in-process peer inbox bound to a Desktop Antigravity session. */
public interface DesktopPeerInbox : AutoCloseable {
    /** Absolute path of the bound UDS socket advertised as `messagingSocketPath`, or null if unavailable. */
    public val socketPath: String?

    /** Host pid domain advertised beside `procStart`, or null when unavailable. */
    public val pidDomain: String?

    /** Kernel process start time advertised as `procStart` (Linux `/proc` or macOS `ps`), or null. */
    public val procStart: String?

    /** Inbound peer messages for the session event loop. */
    public val incoming: ReceiveChannel<DesktopPeerMessage>
}

/** Opens a [DesktopPeerInbox] for one Desktop Antigravity session once its session id and config folder are known. */
public fun interface DesktopPeerInboxFactory {
    public fun open(
        configDir: Path,
        pid: Long,
        sessionId: String,
        scope: CoroutineScope,
    ): DesktopPeerInbox?
}

/** Optional peer inbox and outbound peer environment wired into DesktopAcp.run. */
public data class DesktopPeerWiring(
    public val inbox: DesktopPeerInboxFactory? = null,
    public val childEnv: Map<String, String> = emptyMap(),
)
