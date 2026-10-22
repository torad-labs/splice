package splice.control.mcp.hub

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The action one holder lifecycle operation owns while its socket is locked. */
internal fun interface HubLifecycleAction<T> {
    operator fun invoke(): T
}

/** Starts and cleanup share one socket lock, so a retired generation cannot unlink its replacement. */
internal class HubHolderLifecycle {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    fun <T> serial(socket: String, action: HubLifecycleAction<T>): T =
        locks.computeIfAbsent(socket) { ReentrantLock() }.withLock { action() }
}

/** One shared record owner carries every reusable-record update through the same socket lifecycle lock. */
internal class HubHolderContext(dir: Path) {
    val records = HubRecords(dir)
    val scope = HubHolderScope()
    val lifecycle = HubHolderLifecycle()
    val liveness = HubHolderLiveness(records, scope, lifecycle)
}
