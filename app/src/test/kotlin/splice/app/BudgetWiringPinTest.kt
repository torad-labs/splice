// NEW: V4-133 review — the budgets GET/PUT /api/budgets saves are the budgets every head enforces.
//
// Two lines join the two sides, and neither is checked by the compiler, because both sides default so
// tests can build them bare:
//
//   ControlPlane        console = ConsoleEventPublisher(..., budgets = <enforcement over [budgets]>)
//   HeadServerFactory   budget = console?.budgets?.forHead(key, ctx.catalog)
//
// The first is checked BEHAVIOURALLY on a real ControlPlane: a block budget saved into the plane's own
// store (the one the route writes) refuses that head's next turn through the plane's own publisher.
// The second is pinned on the source text, the idiom OneEventBusPinTest uses for its sibling line.
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
import splice.usage.budgets.BudgetActions
import splice.usage.budgets.BudgetRoutes
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BudgetWiringPinTest {

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
            plane.budgets.replace(listOf(Budget("slow-head", 1.0, BudgetActions.BLOCK)))
            val head = plane.console.budgets!!.forHead("slow-head", null)
            val admitted = CompletableFuture.supplyAsync { head.admit() }
            assertNull(
                admitted.get(300, TimeUnit.MILLISECONDS),
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
                    CompletableFuture.supplyAsync { head.admit() }.get(300, TimeUnit.MILLISECONDS),
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
            plane.budgets.replace(listOf(Budget("pinned-head", 0.0, BudgetActions.BLOCK)))
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

    @Test
    fun `the head factory gives each head its own ledger from the publisher, keyed and priced by that head`() {
        assertTrue(
            source("app/src/main/kotlin/splice/app/head/HeadServerFactory.kt")
                .contains("budget = console?.budgets?.forHead(key, ctx.catalog)"),
            "HeadServerFactory must set HeadQuota.budget from the publisher's enforcement, keyed by the head it builds",
        )
    }

    private fun source(relative: String): String {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve(relative)
            if (Files.exists(candidate)) return Files.readString(candidate)
            dir = dir.parent
        }
        error("$relative not found above ${Paths.get("").toAbsolutePath()}")
    }
}
