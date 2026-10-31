package splice.codemode.nativehost.grpc.state

import kotlinx.coroutines.sync.Mutex
import splice.codemode.nativehost.grpc.NativeGrpcProtocolException
import splice.codemode.nativehost.grpc.NativeGrpcSession
import java.util.concurrent.atomic.AtomicBoolean

/** A live observer owns active; a retiring RPC keeps its permit until CancelWait is acknowledged. */
internal class NativeGrpcWaitSlots {
    private val slots = HashMap<Pair<NativeGrpcSession, String>, NativeGrpcWaitSlot>()

    @Synchronized
    fun claim(session: NativeGrpcSession, cell: String): NativeGrpcWaitClaim {
        val key = session to cell
        val slot = slots.getOrPut(key, ::NativeGrpcWaitSlot)
        if (!slot.activate()) throw NativeGrpcProtocolException("exec cell $cell already has an active observer")
        slot.references++
        return NativeGrpcWaitClaim(slot) { release(key, slot) }
    }

    @Synchronized
    private fun release(key: Pair<NativeGrpcSession, String>, slot: NativeGrpcWaitSlot) {
        check(slot.references > 0) { "CodeMode wait slot released twice" }
        if (--slot.references == 0) slots.remove(key, slot)
    }
}

internal class NativeGrpcWaitSlot {
    val permit = Mutex()
    private val active = AtomicBoolean()
    var references: Int = 0

    fun activate(): Boolean = active.compareAndSet(false, true)
    fun deactivate() { active.set(false) }
}

internal class NativeGrpcWaitClaim(
    private val slot: NativeGrpcWaitSlot,
    private val released: NativeGrpcWaitReleased,
) {
    private val active = AtomicBoolean(true)
    private val permit = AtomicBoolean()
    private val closed = AtomicBoolean()

    suspend fun acquire() {
        slot.permit.lock(this)
        permit.set(true)
    }

    fun deactivate() {
        if (active.compareAndSet(true, false)) slot.deactivate()
    }

    fun release() {
        if (closed.compareAndSet(false, true)) {
            deactivate()
            if (permit.compareAndSet(true, false)) slot.permit.unlock(this)
            released.release()
        }
    }
}

internal fun interface NativeGrpcWaitReleased {
    fun release()
}
