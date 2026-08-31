// DR-22 redo: the branch cache's publish must survive a concurrent late-publisher. Two ticks miss
// the cache for the same cwd; the one that OBSERVED the branch EARLIER but finishes its git lookup
// LAST must not clobber the fresher entry the later observer already published. cachedGitBranch
// stamps observedAt BEFORE the lookup and revalidates under the lock — it keeps the entry whose
// expiresAtMs (observedAt + a constant TTL, so a larger value strictly means a later observation)
// is >= its own. This latched test makes that race deterministic instead of timing-dependent: an
// injected GitBranchReader blocks the early observer until the late observer has published, and an
// injected clock keys observedAt per thread so "earlier" is a fixed fact, not a scheduling accident.
// RED on an unconditional publish (the early-but-late writer overwrites the fresher branch and
// returns its own stale read).
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.control.GitBranchReader
import splice.control.StatuslineRenderer
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

class StatuslineLatePublishTest {

    private val earlyName = "early-tick"
    private val lateName = "late-tick"
    private val earlyBranch = "feature-early"
    private val lateBranch = "feature-late"

    @Test
    fun `a slow early observer must not clobber the branch a later observer already cached`(@TempDir tmpDir: Path) {
        val repo = Files.createDirectory(tmpDir.resolve("repo"))
        val stdin = """{"cwd":"$repo"}"""
        val earlyEntered = CountDownLatch(1)
        val releaseEarly = CountDownLatch(1)
        val renderer = latchedRenderer(tmpDir, earlyEntered, releaseEarly)

        val earlyResult = arrayOf("")
        val lateResult = arrayOf("")
        val early = Thread(
            { earlyResult[0] = renderer.render(stdin, usage = null, warnPct = 0, warnTokens5h = 0) },
            earlyName,
        )
        val late = Thread(
            { lateResult[0] = renderer.render(stdin, usage = null, warnPct = 0, warnTokens5h = 0) },
            lateName,
        )

        early.start()
        earlyEntered.await() // early stamped observedAt=1000 and is blocked BEFORE it can publish
        late.start()
        late.join() // late observed at 2000, published (lateBranch, 2000+TTL) into an empty slot
        releaseEarly.countDown()
        early.join() // early resumes with its stale 1000 read; revalidation must keep the fresher entry

        assertTrue(lateResult[0].contains("⎇ $lateBranch"), lateResult[0])
        assertTrue(earlyResult[0].contains("⎇ $lateBranch"), earlyResult[0])
        assertFalse(earlyResult[0].contains(earlyBranch), earlyResult[0])
    }

    // The early tick blocks inside its lookup — after it has stamped its older observedAt — until
    // releaseEarly fires; the late tick returns immediately. The clock keys observedAt per thread so
    // the early read (1000) is strictly staler than the late read (2000) regardless of publish order.
    private fun latchedRenderer(
        tmpDir: Path,
        earlyEntered: CountDownLatch,
        releaseEarly: CountDownLatch,
    ): StatuslineRenderer {
        val clock = WallClock { if (Thread.currentThread().name == earlyName) 1_000L else 2_000L }
        val lookup = GitBranchReader { _ ->
            if (Thread.currentThread().name == earlyName) {
                earlyEntered.countDown()
                releaseEarly.await()
                earlyBranch
            } else {
                lateBranch
            }
        }
        return StatuslineRenderer(
            label = "codex",
            extraGitRoots = listOf(tmpDir.toString()),
            now = clock,
            branchLookup = lookup,
        )
    }
}
