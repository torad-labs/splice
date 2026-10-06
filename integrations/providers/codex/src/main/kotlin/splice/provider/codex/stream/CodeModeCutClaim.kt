// NEW: the source's first cancellation owner and actual cut are separate, monotonic facts.
package splice.provider.codex.stream

import kotlinx.coroutines.Deferred
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private enum class SourceCutOwner { UNCLAIMED, CLIENT, RETIREMENT }

/** The first cancellation owner is monotonic; only its client step can take an intentional cut. */
internal class CodeModeCutClaim {
    private val owner = AtomicReference(SourceCutOwner.UNCLAIMED)
    private val counted = AtomicBoolean()

    @Volatile private var cancelled = false
    val client: Boolean get() = cancelled && owner.get() == SourceCutOwner.CLIENT

    fun claimClient() {
        owner.compareAndSet(SourceCutOwner.UNCLAIMED, SourceCutOwner.CLIENT)
    }

    fun cancel(reader: Deferred<*>?, stoppedByHead: Boolean) {
        if (stoppedByHead || reader?.isActive != true) return
        claimClient()
        cancelled = true
    }

    fun retire() {
        owner.compareAndSet(SourceCutOwner.UNCLAIMED, SourceCutOwner.RETIREMENT)
    }

    fun take(): Boolean = client && counted.compareAndSet(false, true)
}
