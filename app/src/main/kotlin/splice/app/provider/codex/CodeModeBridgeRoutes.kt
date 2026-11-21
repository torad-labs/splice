package splice.app.provider.codex

import splice.core.config.SpliceConfig
import splice.provider.codex.CodeModeRouteLifetime
import splice.provider.codex.CodexCodeModeBridge
import java.util.concurrent.CopyOnWriteArraySet

/** One bridge owns a persisted conversation store. A route can stop without ending a sibling's work. */
internal class CodeModeBridgeRoutes(
    val bridge: CodexCodeModeBridge,
    private val storage: CodeModeStoragePolicy,
) {
    private val lock = Any()
    private val active = mutableSetOf<Route>()
    private val retiring = mutableSetOf<Route>()
    private var handedOver = false

    fun route(config: SpliceConfig): CodeModeRouteLifetime {
        storage.add(config)
        return Route()
    }

    private fun retireIfReady() {
        if (handedOver || active.isNotEmpty()) return
        if (retiring.isEmpty()) return
        handedOver = true
        bridge.onHeadRetire()
    }

    private inner class Route : CodeModeRouteLifetime {
        override fun onStart() {
            synchronized(lock) {
                active += this
            }
        }

        override fun onRetire() {
            synchronized(lock) {
                if (active.remove(this)) retiring += this
                retireIfReady()
            }
        }

        override fun onStop() {
            synchronized(lock) {
                if (!active.remove(this) && !retiring.remove(this)) return
                if (active.isEmpty() && retiring.isEmpty()) {
                    bridge.onHeadStop()
                    handedOver = false
                } else {
                    retireIfReady()
                }
            }
        }
    }
}

/** Retention settings act on one shared store: neither route may override the other's choice not to retain. */
internal class CodeModeStoragePolicy(initial: SpliceConfig) {
    private val routes = CopyOnWriteArraySet(listOf(initial))

    fun add(config: SpliceConfig) {
        routes += config
    }

    fun keepWork(): Boolean = routes.all { it.current().keepCodeModeWork }

    fun replayReasoning(): Boolean = routes.all { it.current().replayReasoning }
}
