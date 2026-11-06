package splice.core.perf.observation

internal object PerfSystemMetrics {
    fun definitions(): List<PerfMetricDefinition> = gates() + tools() + runtime() + selfCost()

    private fun gates(): List<PerfMetricDefinition> = listOf(
        "gate_permits",
        "gate_queued",
        "gate_limit",
        "gate_oldest_wait_ms",
        "gate_refused",
        "gate_queued_cancelled",
    ).map {
        PerfMetricDefinition(
            it,
            "Gate state sample; refused and cancelled are cumulative readings, never event deltas",
            PerfMetricType.GAUGE,
            if (it.endsWith("_ms")) "ms" else "count",
            "gate state transition",
            "next transition or five-second sample",
            "time-weighted gate state",
            setOf(PerfEventKind.GATE_STATE),
        )
    }

    private fun tools(): List<PerfMetricDefinition> = listOf(
        "tool_duration_ms",
        "tool_queue_ms",
        "tool_execution_ms",
        "tool_callback_wait_ms",
        "tool_buffered_ms",
        "tool_cold_start_ms",
        "tool_buffered_bytes",
    ).map {
        PerfMetricDefinition(
            it,
            "Measured tool phase; callback waiting is separate from execution",
            if (it.endsWith("_ms")) PerfMetricType.HISTOGRAM else PerfMetricType.GAUGE,
            if (it.endsWith("_ms")) "ms" else "bytes",
            "named tool phase begins",
            "named tool phase ends",
            "completed tool phases",
            setOf(PerfEventKind.TOOL_PHASE, PerfEventKind.TOOL_FINISHED),
        )
    }

    private fun runtime(): List<PerfMetricDefinition> = listOf(
        "runtime_heap_used_bytes", "runtime_heap_committed_bytes", "runtime_heap_max_bytes", "runtime_rss_bytes",
        "runtime_direct_bytes", "runtime_threads", "runtime_cpu_ratio", "runtime_cpu_ns", "runtime_gc_count",
        "runtime_gc_pause_ms", "runtime_fd_count", "runtime_dispatcher_lag_ms", "runtime_executor_queued",
        "runtime_executor_active", "runtime_tools_active", "runtime_processes_active",
    ).map {
        PerfMetricDefinition(
            it,
            "Runtime sample; unsupported platform values remain absent",
            PerfMetricType.GAUGE,
            unit(it),
            "runtime sample",
            "next runtime sample",
            "sampled process state",
            setOf(PerfEventKind.RUNTIME),
        )
    }

    private fun selfCost(): List<PerfMetricDefinition> = listOf(
        "self_record_ns", "self_snapshot_ms", "self_serialize_ms", "self_enqueue_ms", "self_write_ms",
        "self_read_ms", "self_export_ms", "self_query_ms", "self_compaction_ms", "self_flush_ms",
        "self_queue_depth", "self_queue_oldest_ms", "self_dropped", "self_failures", "self_bytes_written",
    ).map {
        val type = when {
            it.endsWith("_ms") -> PerfMetricType.HISTOGRAM
            it == "self_queue_depth" -> PerfMetricType.GAUGE
            else -> PerfMetricType.COUNTER
        }
        PerfMetricDefinition(
            it,
            "Instrumentation overhead measured independently of inference",
            type,
            unit(it),
            "instrument operation begins",
            "operation completes",
            "instrument operations",
            setOf(PerfEventKind.SELF_COST),
        )
    }

    private fun unit(id: String): String = when {
        id.endsWith("_bytes") || id.endsWith("_written") -> "bytes"
        id.endsWith("_ms") -> "ms"
        id.endsWith("_ns") -> "ns"
        id.endsWith("_ratio") -> "ratio"
        else -> "count"
    }
}
