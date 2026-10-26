package splice.codemode.nativehost.grpc

import com.google.protobuf.DynamicMessage
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CoroutineScope
import splice.codemode.nativehost.trace.NativeTracing
import splice.upstream.Waiter
import splice.upstream.codemode.ProcessWaiter
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

private const val NATIVE_GRPC_ADMISSION_RETRIES: Int = 4
private const val NATIVE_GRPC_RETRY_BASE_MS: Long = 50
private const val NATIVE_GRPC_RETRY_JITTER_MIN: Double = 0.9
private const val NATIVE_GRPC_RETRY_JITTER_MAX: Double = 1.1

/** Admission retries never include Execute; stream failure invalidates only its owning session. */
internal class NativeGrpcSessions(
    private val channel: NativeGrpcChannel,
    scope: CoroutineScope,
    private val sink: NativeGrpcSessionSink,
    private val waiter: Waiter = ProcessWaiter(),
    private val tracing: NativeTracing = NativeTracing(),
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val bindings = ConcurrentHashMap<String, NativeGrpcSessionBinding>()
    private val opening = ConcurrentHashMap.newKeySet<String>()
    private val pendingLeases = ConcurrentHashMap.newKeySet<NativeGrpcLease>()
    private val lifetime = requireNotNull(scope.coroutineContext[kotlinx.coroutines.Job])

    suspend fun open(localId: String, request: DynamicMessage): NativeGrpcSession =
        tracing.start("code_mode.grpc.open_binding").use { span ->
            span.run { openBinding(localId, request) }
        }

    private suspend fun openBinding(localId: String, request: DynamicMessage): NativeGrpcSession {
        check(!closed.get() && lifetime.isActive) { "Native gRPC session registry is closed" }
        check(!bindings.containsKey(localId) && opening.add(localId)) { "Native gRPC session is already open" }
        var lease: NativeGrpcLease? = null
        var transferred = false
        val openingSpan = tracing.start("code_mode.grpc.open_session")
        try {
            lease = openingSpan.run {
                NativeGrpcDeadlines.startup("session opening") { admit(localId, request, openingSpan.traceparent) }
            }
            val session = lease.first.await()
            val binding = NativeGrpcSessionBinding(session, lease)
            check(!closed.get() && lifetime.isActive) { "Native gRPC session registry is closed" }
            check(bindings.putIfAbsent(localId, binding) == null) { "Native gRPC session is already open" }
            pendingLeases.remove(lease)
            lease.activate { invalidate(localId, it) }
            subscribe(binding)
            if (bindings[localId] !== binding || binding.closed.get()) {
                throw IOException(
                    "Native gRPC session closed during opening",
                )
            }
            transferred = true
            return session
        } finally {
            openingSpan.close()
            opening.remove(localId)
            if (!transferred) {
                bindings.remove(localId)?.close()
                lease?.let { pendingLeases.remove(it) }
                lease?.close()
            }
        }
    }

    private suspend fun subscribe(binding: NativeGrpcSessionBinding) {
        tracing.start("code_mode.grpc.subscribe_to_tool_calls", mapOf("session.id" to binding.session.id)).use { span ->
            try {
                span.run {
                    NativeGrpcDeadlines.startup("tool subscription") {
                        binding.subscribe(channel, sink, span.traceparent) { invalidate(binding.session.localId, it) }
                    }
                }
            } catch (error: StatusRuntimeException) {
                throw NativeGrpcProtocolException(NativeGrpcErrors.render(error, "tool subscription"), cause = error)
            }
        }
    }

    private suspend fun admit(localId: String, request: DynamicMessage, traceparent: String?): NativeGrpcLease {
        var lastFailure: StatusRuntimeException? = null
        for (attempt in 0..NATIVE_GRPC_ADMISSION_RETRIES) {
            if (attempt > 1) {
                val jitter = Random.nextDouble(NATIVE_GRPC_RETRY_JITTER_MIN, NATIVE_GRPC_RETRY_JITTER_MAX)
                waiter.wait(((NATIVE_GRPC_RETRY_BASE_MS shl (attempt - 2)) * jitter).toLong())
            }
            val lease = NativeGrpcLease(localId, channel, sink::event)
            pendingLeases.add(lease)
            var admitted = false
            try {
                lease.start(request, traceparent)
                lease.first.await()
                admitted = true
                return lease
            } catch (error: StatusRuntimeException) {
                if (error.status.code !in setOf(Status.Code.UNAVAILABLE, Status.Code.RESOURCE_EXHAUSTED)) {
                    throw error
                }
                lastFailure = error
            } finally {
                if (!admitted) {
                    pendingLeases.remove(lease)
                    lease.close()
                }
            }
        }
        throw requireNotNull(lastFailure)
    }

    fun get(localId: String): NativeGrpcSession? = bindings[localId]
        ?.takeIf { !it.closing.get() && !it.closed.get() }?.session

    fun invalidate(localId: String, error: Throwable) {
        val binding = bindings[localId] ?: return
        if (!binding.closing.get() && bindings.remove(localId, binding)) {
            binding.close()
            sink.failed(binding.session, error)
        }
    }

    suspend fun close(localId: String) {
        val binding = bindings[localId] ?: return
        binding.closing.set(true)
        if (!bindings.remove(localId, binding)) return
        try {
            NativeGrpcDeadlines.startup("session shutdown") {
                channel.unary(
                    NativeGrpcRequest(
                        "CloseSession",
                        NativeGrpcWire.message("CloseSessionRequest", mapOf("session_id" to binding.session.id)),
                        binding.session.route,
                        timeoutMillis = null,
                    ),
                )
            }
        } finally {
            binding.close()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            bindings.values.forEach(NativeGrpcSessionBinding::close)
            bindings.clear()
            pendingLeases.forEach(NativeGrpcLease::close)
            pendingLeases.clear()
        }
    }
}
