// NEW: deterministic synthetic frame-reader and native lifecycle faults exercise the real host dispatcher.
package splice.codemode

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import org.graalvm.polyglot.Engine
import splice.codemode.engine.HostEngineFactory
import splice.codemode.engine.NativeHostEngines
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Waits for the pool to clear a generation, not merely for its underlying process to exit. */
internal object HostLifecycleAwait {
    suspend fun ended(messages: Collection<String>) = withTimeout(5_000) {
        while (messages.none { it.contains("placement cleared") }) yield()
    }
}

internal class HostControlFixture(val factory: ControlledHostEngines = ControlledHostEngines()) : AutoCloseable {
    private val replies = ConcurrentLinkedQueue<HostFrame>()
    private val seen = mutableMapOf<Long, JsonObject>()
    private val sequence = AtomicLong()
    private val reader = Executors.newSingleThreadExecutor()
    private val output = object : ByteArrayOutputStream() {
        override fun flush() {
            replies.add(HostProtocol.parse(CodeModeWire.read(DataInputStream(ByteArrayInputStream(toByteArray())))))
            reset()
        }
    }
    private val host = HostWorkerDispatcher(DataOutputStream(output), HostWorkerSessions(factory))

    fun send(session: Long, payload: JsonObject, cell: Long = session): Long {
        val request = sequence.incrementAndGet()
        reader.execute { host.dispatch(HostFrame(cell, request, payload, session)) }
        return request
    }

    suspend fun reply(request: Long): JsonObject = withTimeout(5_000) {
        var found = seen.remove(request)
        while (found == null) {
            replies.poll()?.let { seen[it.request] = it.payload }
            found = seen.remove(request)
            if (found == null) yield()
        }
        found
    }

    override fun close() {
        factory.release.countDown()
        reader.shutdownNow()
        try {
            host.close()
        } finally {
            reader.close()
            factory.close()
        }
    }
}

internal class ControlledHostEngines(heldOpens: Int = 1) : HostEngineFactory, AutoCloseable {
    val entered = CountDownLatch(heldOpens)
    val release = CountDownLatch(1)
    val siblingEntered = CountDownLatch(1)
    var blockCreation: Int? = null
    val blockCreations = mutableSetOf<Int>()
    var failOpen = false
    val failClose = AtomicBoolean()
    private val opened = AtomicInteger()
    private val engines = ConcurrentLinkedQueue<Engine>()

    override fun create(): Engine {
        val index = opened.incrementAndGet()
        if (index == 2) siblingEntered.countDown()
        if (index == blockCreation || index in blockCreations) {
            entered.countDown()
            release.await()
        }
        if (failOpen) throw IOException("private synthetic engine detail")
        return NativeHostEngines.create().also(engines::add)
    }

    override fun close(engine: Engine) {
        if (failClose.getAndSet(false)) throw IOException("private synthetic close detail")
        NativeHostEngines.close(engine)
    }

    override fun close() {
        release.countDown()
        engines.forEach { it.close(true) }
    }
}
