// NEW: V4-291 — a bounded setup child dies whole at its deadline. ChildProcesses.run killed only the
// process it started, so `sh -c 'curl ... | sh'` at its deadline left the pipeline downloading and
// running the installer after the wizard said it failed, and both drains stayed blocked on the pipes
// the orphans held.
package splice.app.cli.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

class ProcessRigTest {

    @Test
    fun `a run killed at its deadline leaves no descendant alive and no drain blocked`() {
        val drains = Executors.newCachedThreadPool()
        val running = CompletableFuture.supplyAsync { ChildProcesses(drains).run(PIPELINE, DEADLINE_MS) }
        val pipeline = awaitPipeline(running)
        val sleep = pipeline.first { isSleep(it) }
        try {
            val run = running.get(RUN_WAIT_S, TimeUnit.SECONDS)
            assertEquals(RigRun(KILLED, "", "sh -c sleep 60 | cat did not finish within 1 s"), run)
            awaitUntil("the pipeline's sleep (pid ${sleep.pid()}) died with the run") { !sleep.isAlive }
            drains.shutdown()
            assertTrue(
                drains.awaitTermination(DRAIN_WAIT_S, TimeUnit.SECONDS),
                "a drain is still blocked on a pipe the pipeline's orphans hold",
            )
        } finally {
            // Only the handles captured while they were this JVM's own. Never sleep.parent(): once the
            // deadline kills `sh`, its orphans are reparented to the user's systemd manager, and a
            // SIGKILL there ends the operator's whole desktop session.
            pipeline.forEach(ProcessHandle::destroyForcibly)
            drains.shutdownNow()
        }
    }

    /** The pipeline — `sh`, its `sleep` and its `cat` — found among this JVM's descendants while
     *  [running] waits on its deadline. */
    private fun awaitPipeline(running: CompletableFuture<RigRun>): List<ProcessHandle> {
        var found: List<ProcessHandle> = emptyList()
        awaitUntil("the pipeline's sh, sleep and cat started before the deadline") {
            check(!running.isDone) { "the run ended before its pipeline was seen: ${running.join()}" }
            val ours = ProcessHandle.current().descendants().toList()
            val shell = ours.firstOrNull { isSleep(it) }?.parent()?.orElse(null)?.takeIf { it in ours }
            found = shell?.let { listOf(it) + it.children().toList() }.orEmpty()
            found.size == PIPELINE_PROCESSES
        }
        return found
    }

    /** By its arguments, not its binary: /usr/bin/sleep resolves to gnusleep on this box. */
    private fun isSleep(handle: ProcessHandle): Boolean =
        handle.info().arguments().map { it.toList() }.orElse(emptyList()) == listOf("60")

    /** Polls [done] with a deadline, never a sleep for a duration (kt-tests-no-wall-clock). */
    private fun awaitUntil(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DRAIN_WAIT_S)
        while (!done()) {
            check(System.nanoTime() < deadline) { "never happened within $DRAIN_WAIT_S s: $what" }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(POLL_MS))
        }
    }

    private companion object {
        /** `sleep` writes to `cat` and `cat` to the run's pipe: two descendants that outlive a killed `sh`. */
        val PIPELINE = listOf("sh", "-c", "sleep 60 | cat")
        const val PIPELINE_PROCESSES = 3
        const val DEADLINE_MS = 1_000L
        const val KILLED = 124
        const val RUN_WAIT_S = 30L
        const val DRAIN_WAIT_S = 5L
        const val POLL_MS = 5L
    }
}
