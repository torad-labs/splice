package splice.observability.profile

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class ProfileRun(val record: ProfileRecord) {
    private val stopped = CountDownLatch(1)

    @Volatile var aborted = false

    @Volatile var thread: Thread? = null

    fun awaitDuration() { stopped.await(record.request.durationSeconds.toLong(), TimeUnit.SECONDS) }

    fun stop() { stopped.countDown() }

    fun ensureActive() { if (aborted) throw InterruptedException("Capture aborted") }

    fun abort() {
        aborted = true
        stopped.countDown()
        thread?.interrupt()
    }
}
