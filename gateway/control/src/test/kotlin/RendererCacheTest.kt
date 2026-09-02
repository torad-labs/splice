// DR-22a: StatuslineRenderer captures the head label at construction, and the route's cache used
// to invalidate on git roots only — a head renamed at runtime rendered its stale label for the
// daemon's lifetime. The cache contract pinned here: same inputs reuse, a label change rebuilds.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import splice.control.StatuslineRenderer
import splice.control.api.RendererCache

class RendererCacheTest {

    @Test
    fun `a renamed head gets a renderer carrying the new label`() {
        val cache = RendererCache()
        var built = 0
        val first = cache.get("h", "old-name", listOf("/root")) {
            built++
            StatuslineRenderer("old-name", listOf("/root"))
        }
        val second = cache.get("h", "old-name", listOf("/root")) {
            built++
            StatuslineRenderer("old-name", listOf("/root"))
        }
        assertSame(first, second, "unchanged inputs must reuse the renderer")
        assertEquals(1, built)

        cache.get("h", "new-name", listOf("/root")) {
            built++
            StatuslineRenderer("new-name", listOf("/root"))
        }
        assertEquals(2, built, "a label change must rebuild the renderer (DR-22a)")
    }

    @Test
    fun `changed git roots still rebuild`() {
        val cache = RendererCache()
        var built = 0
        cache.get("h", "name", listOf("/a")) {
            built++
            StatuslineRenderer("name", listOf("/a"))
        }
        cache.get("h", "name", listOf("/b")) {
            built++
            StatuslineRenderer("name", listOf("/b"))
        }
        assertEquals(2, built, "the pre-existing roots invalidation must survive the label fix")
    }
}
