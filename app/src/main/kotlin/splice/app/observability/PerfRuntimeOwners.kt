package splice.app.observability

import splice.core.perf.runtime.PerfRuntimeSource
import java.lang.ref.WeakReference

/** Actual resource instances, including native runtimes opened lazily after startup. */
internal class PerfRuntimeOwners {
    private val sources = ArrayList<WeakReference<PerfRuntimeSource>>()
    var overflow = false
        private set

    @Synchronized
    fun add(source: PerfRuntimeSource) {
        sources.removeAll { it.get() == null }
        if (sources.any { it.get() === source }) return
        if (sources.size < MAX_TRACKED_RUNTIME_OWNERS) sources.add(WeakReference(source)) else overflow = true
    }

    @Synchronized
    fun snapshot(): List<PerfRuntimeSource> = sources.mapNotNull { it.get() }
}

// Retain at most sixty-four weak owner references and expose overflow instead of extending the runtime registry.
private const val MAX_TRACKED_RUNTIME_OWNERS = 64
