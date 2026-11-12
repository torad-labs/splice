package splice.observability.runtime

internal object RuntimeUnknowns {
    fun fill(reading: RuntimeReading) {
        listOf(
            "runtime_heap_used_bytes", "runtime_heap_committed_bytes", "runtime_heap_max_bytes",
            "runtime_nonheap_used_bytes", "runtime_nonheap_committed_bytes",
            "runtime_direct_bytes", "runtime_mapped_bytes",
            "runtime_rss_bytes", "runtime_pss_bytes", "runtime_executor_queued", "runtime_executor_active",
            "runtime_dispatcher_lag_ms", "runtime_connections", "runtime_connections_idle",
            "runtime_connections_reused",
            "runtime_connect_wait_ms", "runtime_tools_active", "runtime_processes_active",
        ).filterNot(reading.values::containsKey).forEach {
            reading.add(it, null, "owner source not attached or counter not exposed; no inferred measurement")
        }
    }
}
