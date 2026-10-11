// The budgets GET/PUT /api/budgets saves are the budgets every head enforces: a block budget saved into
// the control plane's own store refuses that head's next turn through the plane's own publisher, and a
// budgeted admission never waits on file I/O.
package splice.app

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.util.AsyncFileIo
import splice.usage.budgets.Budget
import splice.usage.budgets.BudgetRoutes
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BudgetEnforcementTest {

    @Test
    fun `a budgeted admission never waits for its perf file writer`(@TempDir tempDir: Path) {
        val paths = StatePaths(baseOverride = tempDir.resolve("state"))
        val plane = ControlPlane(DaemonEnvironment(paths, ConfigService(paths), MgmtKey(paths), { }), { })
        val file = paths.perfStatsFile("slow-head")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        assertTrue(
            AsyncFileIo.submitFor(file) {
                started.countDown()
                release.await()
            },
        )
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS), "the owned file write is deliberately slow")
            plane.budgets.replace(listOf(Budget("slow-head", 1.0, "block")))
            val head = plane.console.budgets!!.forHead("slow-head", null)
            val admitted = CompletableFuture.supplyAsync { head.admit() }
            assertNull(
                admitted.get(30, TimeUnit.SECONDS),
                "admission uses the tally it has without file I/O",
            )
            val policy = BudgetRoutes({ plane.budgets }, ConfigService(paths)).read(plane.console.budgets)
            assertTrue(
                policy.body.contains("\"spend_complete\":false"),
                "pending history is explicit on the existing wire",
            )
            assertTrue(policy.body.contains("\"used_usd\":null"), "pending history never claims an exact amount")
            synchronized(plane.budgets) {
                assertNull(
                    CompletableFuture.supplyAsync { head.admit() }.get(30, TimeUnit.SECONDS),
                    "admission never acquires the settings reader lock",
                )
            }
        } finally {
            release.countDown()
            assertTrue(AsyncFileIo.awaitFile(file), "the owned file writer is released before fixture cleanup")
            plane.cancelProbes()
        }
    }

    @Test
    fun `a block budget saved into the control plane's store refuses that head through the plane's publisher`(
        @TempDir tempDir: Path,
    ) {
        val paths = StatePaths(baseOverride = tempDir.resolve("state"))
        val plane = ControlPlane(DaemonEnvironment(paths, ConfigService(paths), MgmtKey(paths), { }), { })
        try {
            plane.budgets.replace(listOf(Budget("pinned-head", 0.0, "block")))
            val enforcement = plane.console.budgets
            assertNotNull(enforcement, "the publisher every head is built from must carry the daemon's enforcement")
            assertNotNull(
                enforcement!!.forHead("pinned-head", null).admit(),
                "a \$0.00 block budget in the store the route writes must refuse the head's next turn",
            )
        } finally {
            plane.cancelProbes()
        }
    }
}
