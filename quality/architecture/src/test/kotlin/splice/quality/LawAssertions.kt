// NEW: the one assertion every ported law's red proof repeats (restructure PR 6, §4.3).
package splice.quality

import org.junit.jupiter.api.Assertions.assertTrue

/** Some hit carries every needle; otherwise fails with [why] and the hits, Python-repr'd as the checkers printed them. */
internal fun assertHit(hits: List<String>, vararg needles: String, why: () -> String) {
    assertTrue(hits.any { hit -> needles.all { hit.contains(it) } }) { "${why()}, got: ${KotlinText.pyReprList(hits)}" }
}
