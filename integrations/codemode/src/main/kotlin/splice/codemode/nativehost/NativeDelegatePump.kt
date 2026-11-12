package splice.codemode.nativehost

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

/** One cell owns the only delegate receiver. Foreground observation consumes prepared frames independently. */
internal class NativeDelegatePump(
    private val connection: NativeConnection,
    scope: CoroutineScope,
    private val sessionId: String,
    private val cellId: String,
    private val callbacks: NativeCallbacks,
) : AutoCloseable {
    // Observation may time out after receiving a wakeup. Frames remain owned by
    // the cell until it synchronously consumes them, independently of that wakeup.
    private val frames = ConcurrentLinkedQueue<JsonObject>()
    val ready: Channel<Unit> = Channel(Channel.CONFLATED)
    private val incoming = connection.events.cell(sessionId, cellId)
    private val job = scope.launch(start = CoroutineStart.LAZY) {
        try {
            coroutineScope {
                for (frame in incoming) {
                    if (callbacks.handlesNotification(frame)) {
                        launch(start = CoroutineStart.UNDISPATCHED) { callbacks.notify(frame) }
                    } else {
                        frames.add(frame)
                        ready.trySend(Unit)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            ready.close(error)
        } catch (error: IllegalArgumentException) {
            ready.close(error)
        } catch (error: IllegalStateException) {
            ready.close(error)
        } finally {
            ready.close()
        }
    }

    init {
        job.invokeOnCompletion { connection.events.detach(sessionId, cellId, this) }
        connection.events.attach(sessionId, cellId, this)
        job.start()
        if (job.isCompleted) connection.events.detach(sessionId, cellId, this)
    }

    fun poll(): JsonObject? = frames.poll()

    fun fail(error: IOException) {
        ready.close(error)
        job.cancel()
    }

    override fun close() {
        ready.close()
        job.cancel()
    }
}
