// NEW: V4-444 — the provider each assembled head serves with, by head key, so the Playground builds its one call
// through the head's own provider instead of a hand-built copy of each dialect's request (the copy drifted, and
// ChatGPT refused every Playground send with 400 "Input must be a list"). ManagedHeadFactory registers a head's
// provider when it assembles it; the probe reads it at call time. A head assembled again replaces its entry.
package splice.app.probe

import splice.upstream.Provider
import java.util.concurrent.ConcurrentHashMap

internal class PlaygroundProviders {
    private val byKey = ConcurrentHashMap<String, Provider>()

    fun register(key: String, provider: Provider) {
        byKey[key] = provider
    }

    operator fun get(key: String): Provider? = byKey[key]
}
