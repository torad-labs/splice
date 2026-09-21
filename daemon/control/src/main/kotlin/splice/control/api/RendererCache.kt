// NEW: V4-156 — the statusline route's per-head renderer cache, moved verbatim out of
// StatuslineRoute.kt, which carried it as two of its five declared types. The route grew into
// concentration band HIGH (own growth, V4-37/V4-45 session cost and perf skips); the cache is a
// self-contained unit with its own lock and no reference back to the route, so it stands alone.
package splice.control.api

import splice.control.StatuslineRenderer

/** Builds the renderer for a head whose cached one no longer matches — the miss branch of
 *  [RendererCache.get], named for that role rather than its `() -> StatuslineRenderer` shape. */
internal fun interface BuildRenderer {
    operator fun invoke(): StatuslineRenderer
}

/** Per-head renderer cache for the statusline route. A cached renderer is reused while the inputs
 *  captured at its construction still hold; a change rebuilds it. Thread-safe: ticks for many
 *  heads land concurrently on Ktor dispatcher threads. */
internal class RendererCache {
    // Label is part of the match (DR-22a): the renderer captures it at construction, so a
    // roots-only check rendered a runtime-renamed head's stale label for the daemon's life.
    private class Entry(val roots: List<String>, val label: String, val renderer: StatuslineRenderer) {
        fun matches(roots: List<String>, label: String): Boolean = this.roots == roots && this.label == label
    }

    private val entries = HashMap<String, Entry>()

    fun get(key: String, label: String, roots: List<String>, create: BuildRenderer): StatuslineRenderer =
        synchronized(entries) {
            val cached = entries[key]
            if (cached != null && cached.matches(roots, label)) {
                cached.renderer
            } else {
                create().also { entries[key] = Entry(roots.toList(), label, it) }
            }
        }
}
