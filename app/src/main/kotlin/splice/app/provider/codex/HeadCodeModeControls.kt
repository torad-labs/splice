package splice.app.provider.codex

import splice.provider.codex.CodexCodeModeBridge
import java.util.concurrent.ConcurrentHashMap

/** The actual bridge assembled for each head, read by explicit conversation controls at request time. */
internal class HeadCodeModeControls {
    private val bridges = ConcurrentHashMap<String, CodexCodeModeBridge>()

    internal fun register(head: String, bridge: CodexCodeModeBridge?) {
        if (bridge == null) bridges.remove(head) else bridges[head] = bridge
    }

    internal fun remove(head: String, bridge: CodexCodeModeBridge) {
        bridges.remove(head, bridge)
    }

    internal fun bridge(head: String): CodexCodeModeBridge? = bridges[head]
}
