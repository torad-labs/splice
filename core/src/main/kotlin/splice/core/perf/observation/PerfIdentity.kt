package splice.core.perf.observation

import kotlinx.serialization.Serializable

/** Provenance travels with every observation and retained aggregate. Unknown values stay explicit. */
@Serializable
public data class PerfIdentity(
    val bootId: String,
    val build: String,
    val version: String,
    val configRevision: String,
    val schemaVersion: Int = 1,
)

@Serializable
public data class PerfStamp(
    val observationId: String,
    val identity: PerfIdentity,
    val wallMs: Long,
    val monotonicMs: Long,
)
