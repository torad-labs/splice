// NEW: the pages html_render keeps (modes/visual), in the mode's own store so they survive a restart. The store has
// no delete, so the pages sit in a ring of MAX_PAGES slots, `page-000` to `page-199`, and one record, `pages`, says
// which page id is in which slot and which slot the next page takes. A new page takes the oldest page's slot, so the
// store never holds more than MAX_PAGES pages and the record, and no page expires by age. A page is found by its id
// alone: the store's keys are the mode's, not a session's, since an mcp mode's session id is the client's MCP session.
package splice.modes.visual

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.wire.HttpStatus
import splice.modes.api.ModeRequest
import splice.modes.api.ModeResponse
import splice.modes.api.ModeStore
import java.security.SecureRandom

// why: 200 pages of at most 256 KiB of HTML each bounds the kept pages near 50 MiB, and keeps the pages of many
// conversations, each of which draws a few.
private const val MAX_PAGES = 200

// why: an id is `vp_` and twelve hex digits, six random bytes.
private const val PAGE_ID_BYTES = 6
private val PAGE_ID = Regex("vp_[0-9a-f]{12}")

private const val RING_KEY = "pages"

// why: three digits name every one of the 200 slots, so the files list in slot order.
private const val PAGE_SLOT_DIGITS = 3

/** One kept page, as its slot holds it. */
@Serializable
internal data class KeptPage(val id: String, val title: String, val html: String, val height: Int? = null)

/** Which page id is in which slot (by index; an empty id is an empty slot), and the slot the next page takes. */
@Serializable
internal data class PageRing(val next: Int = 0, val ids: List<String> = emptyList())

internal class VisualPages(private val random: SecureRandom = SecureRandom()) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** Serializes the slot's write and the ring's write of one page against another page's; it guards no state of
     *  its own, only the store's two files, which is why it is held across them. */
    private val ioLock = Any()

    /** Keeps [drawing] as a page and gives its id. */
    fun keep(store: ModeStore, drawing: Drawing.Ready): String = synchronized(ioLock) {
        val ring = decode(store.read(RING_KEY), PageRing.serializer()) ?: PageRing()
        val id = newId(ring.ids)
        val slot = ring.next.takeIf { it in 0 until MAX_PAGES } ?: 0
        val page = KeptPage(id, drawing.title, drawing.code, drawing.height)
        // The page goes first: a daemon stopped between the two writes leaves the ring naming the slot's old id, which
        // the slot no longer holds, so that id is not found rather than found as another page.
        store.write(slotKey(slot), json.encodeToString(KeptPage.serializer(), page))
        val size = maxOf(ring.ids.size, slot + 1).coerceAtMost(MAX_PAGES)
        val ids = List(size) { at -> if (at == slot) id else ring.ids.getOrElse(at) { "" } }
        store.write(RING_KEY, json.encodeToString(PageRing.serializer(), PageRing((slot + 1) % MAX_PAGES, ids)))
        id
    }

    /** The page kept as [id], or null when none is. */
    fun page(store: ModeStore, id: String): KeptPage? {
        val ring = if (PAGE_ID.matches(id)) decode(store.read(RING_KEY), PageRing.serializer()) else null
        val slot = ring?.ids?.indexOf(id)?.takeIf { it >= 0 }
        val kept = slot?.let { decode(store.read(slotKey(it)), KeptPage.serializer()) }
        return kept?.takeIf { it.id == id }
    }

    /** `GET /api/visual/pages/<vp_…>`: `{ html, title, height }`, `height` only when the call gave one; 404 for an id
     *  the mode does not keep. */
    fun answer(request: ModeRequest): ModeResponse {
        val page = page(request.store, request.path.substringAfterLast('/'))
            ?: return ModeResponse(
                HttpStatus.NOT_FOUND,
                buildJsonObject { put("error", "the visual mode keeps no such page") },
            )
        return ModeResponse(
            HttpStatus.OK,
            buildJsonObject {
                put("html", page.html)
                put("title", page.title)
                page.height?.let { put("height", it) }
            },
        )
    }

    private fun newId(taken: List<String>): String {
        val bytes = ByteArray(PAGE_ID_BYTES)
        while (true) {
            random.nextBytes(bytes)
            val id = "vp_" + bytes.joinToString("") { "%02x".format(it) }
            if (id !in taken) return id
        }
    }

    private fun slotKey(slot: Int): String = "page-" + slot.toString().padStart(PAGE_SLOT_DIGITS, '0')

    /** [text] read as [serializer]'s type, or null when there is none or it is not that: a record the mode cannot
     *  read is treated as no record, and the ring starts again. */
    private fun <T> decode(text: String?, serializer: KSerializer<T>): T? = text?.let {
        try {
            json.decodeFromString(serializer, it)
        } catch (_: SerializationException) {
            null
        }
    }
}
