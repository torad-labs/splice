package splice.observability.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.runtime.PerfRuntimeSource
import splice.core.util.AsyncFileIo

/** One process-owned file lane; exposes scalar snapshots without flushing work or retaining task payloads. */
public object AsyncFileIoRuntimeSource : PerfRuntimeSource {
    override fun runtime(): JsonObject {
        val work = AsyncFileIo.workSnapshot
        return buildJsonObject {
            put(
                "measurements",
                buildJsonObject {
                    put("runtime_file_io_accepted", work.accepted)
                    put("runtime_file_io_completed", work.completed)
                    put("runtime_file_io_failed", work.failed)
                    put("runtime_file_io_failed_writes", work.failedWrites)
                    put("runtime_file_io_dropped", work.dropped)
                    put("runtime_file_io_pending", work.pending)
                    put("runtime_file_io_running", work.running)
                    put("runtime_file_io_scheduled", work.scheduled)
                    put("runtime_file_io_queued", work.queued)
                    put("runtime_file_io_peak_pending", work.peakPending)
                    put("runtime_file_io_queue_wait_ms", work.elapsed.queueWaitMs)
                    put("runtime_file_io_run_ms", work.elapsed.runMs)
                    put("runtime_file_io_peak_queue_wait_ms", work.elapsed.peakQueueWaitMs)
                    put("runtime_file_io_peak_run_ms", work.elapsed.peakRunMs)
                },
            )
        }
    }
}
