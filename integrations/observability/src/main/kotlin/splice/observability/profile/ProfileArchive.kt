package splice.observability.profile

import splice.core.perf.runtime.PERF_PROFILE_READ_BUFFER_BYTES
import splice.core.perf.runtime.PerfProfileArtifact
import splice.core.perf.runtime.PerfProfileRead
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock

/** Records, retention and pinned downloads share the capture coordinator's monitor and one metadata reservation. */
internal class ProfileArchive(val artifacts: ProfileArtifacts, private val clock: WallClock) : AutoCloseable {
    val lock = Any()
    private val records = linkedMapOf<String, ProfileRecord>()
    private val memory =
        checkNotNull(PerfWorkBudget.acquire((ProfileLimits.MAX_RECORDS + 1) * ProfileLimits.PROFILE_METADATA_BYTES))
    private var readers = 0
    private var retired = false

    fun add(record: ProfileRecord) { records[record.id] = record }
    fun record(id: String?): ProfileRecord? = if (id == null) records.values.lastOrNull() else records[id]
    fun pinned(): Boolean = records.values.filter { it.readers > 0 }.sumOf { it.sizeBytes } >
        ProfileLimits.ARTIFACT_STORE_BYTES - ProfileLimits.ARTIFACT_BYTES

    fun artifact(id: String): PerfProfileArtifact? = records[id]
        ?.takeIf { clock() - (it.untilMs ?: it.fromMs) <= ProfileLimits.ARTIFACT_RETENTION_MS }
        ?.let(artifacts::artifact)

    fun open(id: String): PerfProfileRead? {
        if (readers >= MAX_READERS) return null
        val record = records[id] ?: return null
        return artifact(id)?.let { read(record, it) }
    }

    private fun read(record: ProfileRecord, artifact: PerfProfileArtifact): PerfProfileRead? {
        val lease = PerfWorkBudget.acquire(PROFILE_READ_LEASE_BYTES) ?: return null
        var opened = false
        try {
            val result = artifacts.open(
                artifact,
                lease,
                AutoCloseable {
                    synchronized(lock) {
                        record.readers--
                        readers--
                        if (retired && readers == 0) releaseMemory()
                    }
                },
            )
            record.readers++
            readers++
            opened = true
            return result
        } finally {
            if (!opened) lease.close()
        }
    }

    fun prune(active: ProfileRecord?) = synchronized(lock) {
        val completed = records.values.filter { it.untilMs != null }
        var total = completed.filter { it.available }.sumOf { it.sizeBytes }
        completed.forEach { record ->
            val expired = clock() - checkNotNull(record.untilMs) > ProfileLimits.ARTIFACT_RETENTION_MS
            val overBudget = total > ProfileLimits.ARTIFACT_STORE_BYTES - ProfileLimits.ARTIFACT_BYTES
            val remove = expired || overBudget
            if (record.removable && remove) {
                artifacts.expire(record)
                total -= record.sizeBytes
            }
        }
        while (records.size > ProfileLimits.MAX_RECORDS) {
            val oldest = records.values.firstOrNull { it.readers == 0 && it !== active } ?: break
            artifacts.forget(oldest)
            records.remove(oldest.id)
        }
    }

    /** Called only after the active capture has finalized; existing readers keep the directory and metadata alive. */
    override fun close() = synchronized(lock) {
        retired = true
        if (readers == 0) releaseMemory()
    }

    private fun releaseMemory() {
        records.clear()
        memory.close()
        artifacts.close()
    }
}

// Allow two simultaneous downloads so pinned artifacts and per-reader buffers remain bounded.
private const val MAX_READERS = 2

// Reserve the shared download buffer plus 4 KiB for its channel, pinned-record ownership and response metadata.
private const val PROFILE_READ_LEASE_BYTES = PERF_PROFILE_READ_BUFFER_BYTES + 4L * 1_024
