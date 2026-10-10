package splice.provider.codex.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.usage.PlanLimit
import splice.provider.codex.auth.CodexPlanLimitBody
import java.nio.file.Path

private const val NOW_S = 1_790_584_000L
private const val SIX_DAYS_S = 6L * 24 * 3_600

/** ChatGPT names a spent plan window in the 429 BODY. The provider reads it into the same
 *  [PlanLimit] the transport already holds a turn on; a burst 429 and any other body read null. */
class CodexPlanLimitBodyTest {
    private val prefetch = SupervisorJob()

    @AfterEach
    fun stop() = prefetch.cancel()

    private fun auth(dir: Path) = CodexAuthProvider(
        authPath = dir.resolve("auth.json"),
        authCacheMs = 60_000,
        refreshCall = { error("no refresh in a body read") },
        prefetchScope = CoroutineScope(prefetch as Job),
    )

    private fun body(vararg fields: String) =
        """{"error":{"type":"usage_limit_reached","plan_type":"pro",${fields.joinToString(",")}}}"""

    @Test
    fun `the live weekly body reads as a seven-day plan limit at its reset`(@TempDir dir: Path) {
        val reset = NOW_S + SIX_DAYS_S
        val text = body("\"resets_at\":$reset", "\"resets_in_seconds\":$SIX_DAYS_S", "\"limit_window_minutes\":10080")

        assertEquals(PlanLimit("seven_day", reset), auth(dir).planLimitFromBody(text, NOW_S))
    }

    @Test
    fun `a five-hour window, an odd window and an unnamed window are named as such`(@TempDir dir: Path) {
        val auth = auth(dir)

        fun claim(vararg extra: String) =
            auth.planLimitFromBody(body("\"resets_at\":${NOW_S + 3_600}", *extra), NOW_S)?.claim

        assertEquals("five_hour", claim("\"limit_window_minutes\":300"))
        assertEquals("60-minute", claim("\"limit_window_minutes\":60"))
        assertEquals("usage", claim())
    }

    @Test
    fun `only a duration is enough, measured from now`(@TempDir dir: Path) {
        val text = body("\"resets_in_seconds\":7200", "\"limit_window_minutes\":300")

        assertEquals(PlanLimit("five_hour", NOW_S + 7_200), auth(dir).planLimitFromBody(text, NOW_S))
    }

    @Test
    fun `a reset further out than one whole window is bounded by that window`(@TempDir dir: Path) {
        val text = body("\"resets_at\":${NOW_S + 40 * 24 * 3_600L}", "\"limit_window_minutes\":10080")

        assertEquals(NOW_S + SIX_DAYS_S + 24 * 3_600L, auth(dir).planLimitFromBody(text, NOW_S)?.resetEpochSeconds)
    }

    @Test
    fun `bodies that name no spent window read null and keep the burst schedule`(@TempDir dir: Path) {
        val auth = auth(dir)
        val passed = body("\"resets_at\":${NOW_S - 10}", "\"limit_window_minutes\":10080")
        val burst = """{"error":{"type":"rate_limit_exceeded","message":"slow down","resets_at":${NOW_S + 60}}}"""
        val noReset = body("\"limit_window_minutes\":10080")

        assertNull(auth.planLimitFromBody(passed, NOW_S), "a reset already passed names nothing")
        assertNull(auth.planLimitFromBody(burst, NOW_S), "another error type is not a spent plan")
        assertNull(auth.planLimitFromBody(noReset, NOW_S), "no reset, no hold")
        assertNull(auth.planLimitFromBody("upstream connect error", NOW_S), "not JSON at all")
    }
}
