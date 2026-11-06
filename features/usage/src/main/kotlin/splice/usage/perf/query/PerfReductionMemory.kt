package splice.usage.perf.query

import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfLinks
import splice.core.perf.observation.PerfScope

/** Conservative accounting under the separately held 3 MiB reducer lease. Transient decode belongs to the source. */
internal class PerfReductionMemory {
    private var bytes = REDUCTION_OVERHEAD
    private val identities = HashMap<PerfIdentity, PerfIdentity>()
    private val scopes = HashMap<PerfScope, PerfScope>()
    private val strings = HashMap<String, String>()

    fun take(count: Long): Boolean {
        if (count > PERF_REDUCTION_BYTES - bytes) return false
        bytes += count
        return true
    }

    fun give(count: Long) { bytes -= count }

    fun text(value: String): String? = strings[value] ?: value.takeIf {
        strings.size < MAX_STRINGS && take(LINK_BYTES + it.length * 2L)
    }?.also { strings[it] = it }

    fun identity(value: PerfIdentity): PerfIdentity? = identities[value] ?: value.takeIf {
        identities.size < MAX_IDENTITIES && take(
            DIMENSION_BYTES + listOf(
                it.bootId,
                it.build,
                it.version,
                it.configRevision,
            ).sumOf { text -> text.length * 2L },
        )
    }?.also { identities[it] = it }

    fun scope(value: PerfScope): PerfScope? = scopes[value] ?: value.takeIf {
        scopes.size < MAX_SCOPES && take(
            DIMENSION_BYTES + listOfNotNull(
                it.head,
                it.model,
                it.transport,
                it.work,
                it.outcome,
            ).sumOf { text -> text.length * 2L },
        )
    }?.also { scopes[it] = it }

    fun links(value: PerfLinks): PerfLinks? {
        val selected = listOfNotNull(value.sessionId, value.projectId, value.accountId)
        for (item in selected) {
            if (text(item) == null) return null
        }
        return PerfLinks(
            sessionId = value.sessionId?.let(strings::get),
            projectId = value.projectId?.let(strings::get),
            accountId = value.accountId?.let(strings::get),
        )
    }
}

internal const val PERF_REDUCTION_BYTES = 3L * 1_024 * 1_024
private const val MAX_IDENTITIES = 64
private const val MAX_SCOPES = 256
private const val MAX_STRINGS = 8_192
private const val REDUCTION_OVERHEAD = 32L * 1_024
private const val DIMENSION_BYTES = 512L
private const val LINK_BYTES = 96L
