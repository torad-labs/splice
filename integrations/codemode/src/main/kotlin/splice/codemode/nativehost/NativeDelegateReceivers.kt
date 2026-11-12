package splice.codemode.nativehost

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** The delegate consumer has the cell's lifetime, independently of its foreground wait or queued event frames. */
internal class NativeDelegateReceivers {
    private val consumers = ConcurrentHashMap<Pair<String, String>, NativeDelegatePump>()
    private val sessionFailures = ConcurrentHashMap<String, IOException>()

    @Volatile private var ended: IOException? = null

    fun failure(sessionId: String): IOException? = ended ?: sessionFailures[sessionId]

    fun attach(sessionId: String, id: String, consumer: NativeDelegatePump) {
        check(
            consumers.putIfAbsent(sessionId to id, consumer) == null,
        ) { "Native cell already has a delegate receiver" }
        failure(sessionId)?.let(consumer::fail)
    }

    fun detach(sessionId: String, id: String, consumer: NativeDelegatePump) {
        consumers.remove(sessionId to id, consumer)
    }

    fun release(key: Pair<String, String>) {
        consumers.remove(key)?.close()
    }

    fun fail(error: IOException) {
        ended = error
        consumers.values.forEach { it.fail(error) }
        consumers.clear()
    }

    fun failSession(sessionId: String, error: IOException) {
        sessionFailures[sessionId] = error
        consumers.forEach { (key, consumer) -> if (key.first == sessionId) consumer.fail(error) }
    }
}
