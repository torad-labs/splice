package splice.observability.profile

import java.util.concurrent.Semaphore

/** Multiple head managers still share one process, so their deep capture admission must also be shared. */
internal object ProfileAdmission {
    private val capture = Semaphore(1)

    fun acquire(): Boolean = capture.tryAcquire()
    fun release() { capture.release() }
}
