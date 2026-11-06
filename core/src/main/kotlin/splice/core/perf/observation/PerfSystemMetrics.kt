package splice.core.perf.observation

internal object PerfSystemMetrics {
    fun definitions(): List<PerfMetricDefinition> = gates() + tools() + nativeSpan() + runtime() + selfCost()

    private fun nativeSpan(): List<PerfMetricDefinition> = listOf(
        PerfMetricDefinition(
            "tool_native_span",
            "Existing native SDK owns this span; observation exporters must not export it again",
            PerfMetricType.GAUGE,
            "boolean",
            "native span starts",
            "native span ends",
            "tool operations",
            setOf(PerfEventKind.TOOL_STARTED, PerfEventKind.TOOL_PHASE, PerfEventKind.TOOL_FINISHED),
        ),
    )

    private fun gates(): List<PerfMetricDefinition> = listOf(
        "gate_permits",
        "gate_queued",
        "gate_limit",
        "gate_oldest_wait_ms",
        "gate_refused",
        "gate_queued_cancelled",
        "gate_saturated",
    ).map {
        PerfMetricDefinition(
            it,
            if (it == "gate_saturated") {
                "One when positive gate limit is fully occupied, otherwise zero; weightedSum is saturated milliseconds"
            } else {
                "Gate state sample; refused and cancelled are cumulative readings, never event deltas"
            },
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
        "tool_pending_reply_ms",
        "tool_delivery_ms",
        "tool_calls_per_turn",
        "tool_timeouts",
        "tool_cancelled",
        "tool_admission_depth",
        "tool_admission_capacity",
        "tool_occupied_cells",
    ).map {
        PerfMetricDefinition(
            it,
            "Measured tool phase; callback waiting is separate from execution",
            when {
                it.endsWith("_ms") -> PerfMetricType.HISTOGRAM
                it in setOf("tool_calls_per_turn", "tool_timeouts", "tool_cancelled") -> PerfMetricType.COUNTER
                else -> PerfMetricType.GAUGE
            },
            unit(it),
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
        "runtime_http_connections", "runtime_http_connections_idle",
        "runtime_http_calls_queued", "runtime_http_calls_running",
        "runtime_nonheap_used_bytes", "runtime_nonheap_committed_bytes", "runtime_mapped_bytes", "runtime_pss_bytes",
        "runtime_platform_threads", "runtime_virtual_threads", "runtime_allocation_bytes",
        "runtime_allocation_bytes_per_second", "runtime_fd_limit", "runtime_system_load", "runtime_connections",
        "runtime_connections_idle", "runtime_connections_reused", "runtime_connect_wait_ms", "runtime_tool_capacity",
        "runtime_tool_queued", "runtime_tool_pending_replies", "runtime_tool_oldest_reply_ms",
    ).map {
        PerfMetricDefinition(
            it,
            when {
                it.startsWith(
                    "runtime_allocation",
                ) -> "Approximate JDK all-thread heap allocation; not exclusive owner bytes"
                it.startsWith(
                    "runtime_http_",
                ) -> "Owned HTTP pool or call dispatcher only; no websocket support implied"
                else -> "Runtime sample; unsupported platform values remain absent"
            },
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
        id.endsWith("_bytes_per_second") -> "bytes/s"
        id == "runtime_system_load" -> "load"
        else -> "count"
    }
}
