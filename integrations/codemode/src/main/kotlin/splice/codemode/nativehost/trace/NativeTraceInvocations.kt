package splice.codemode.nativehost.trace

import java.util.concurrent.ConcurrentHashMap

/** Entries share the native pending invocation's bounded lifetime and opaque delegate identity. */
internal class NativeTraceInvocations : AutoCloseable {
    private val active = ConcurrentHashMap<String, NativeTraceSpan>()

    fun add(id: String, span: NativeTraceSpan) {
        check(active.putIfAbsent(id, span) == null) { "Duplicate native callback trace" }
    }

    fun get(id: String): NativeTraceSpan? = active[id]

    fun remove(id: String, span: NativeTraceSpan?) {
        if (span != null && active.remove(id, span)) span.close()
    }

    override fun close() {
        active.entries.forEach { (id, span) -> remove(id, span) }
    }
}
