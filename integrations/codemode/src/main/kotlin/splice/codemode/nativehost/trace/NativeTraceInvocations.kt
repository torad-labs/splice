package splice.codemode.nativehost.trace

import java.util.concurrent.ConcurrentHashMap

/** Entries share the native pending invocation's bounded lifetime and opaque delegate identity. */
internal class NativeTraceInvocations : AutoCloseable {
    private val active = ConcurrentHashMap<String, NativeTraceSpan>()

    fun add(id: String, span: NativeTraceSpan) {
        check(active.putIfAbsent(id, span) == null) { "Duplicate native callback trace" }
    }

    fun get(id: String): NativeTraceSpan? = active[id]

    fun measurements(): Map<String, Double> {
        var pending = 0
        var oldest: Long? = null
        active.values.forEach { span ->
            span.pendingSinceMs?.let { since ->
                pending++
                oldest = minOf(oldest ?: since, since)
            }
        }
        val age = (oldest?.let { splice.core.util.MonoClock.nowMs() - it } ?: 0).coerceAtLeast(0).toDouble()
        return mapOf("runtime_tool_pending_replies" to pending.toDouble(), "runtime_tool_oldest_reply_ms" to age)
    }

    fun remove(id: String, span: NativeTraceSpan?) {
        if (span != null && active.remove(id, span)) span.close()
    }

    override fun close() {
        active.entries.forEach { (id, span) -> remove(id, span) }
    }
}
