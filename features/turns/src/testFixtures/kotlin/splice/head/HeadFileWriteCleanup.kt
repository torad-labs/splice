// NEW: temporary head stores cannot be deleted while queued telemetry still writes them.
package splice.head

import org.junit.jupiter.api.extension.AnnotatedElementContext
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.io.TempDirDeletionStrategy
import splice.core.util.AsyncFileIo
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

// why: match the file lane's existing five-second bounded drain, while observing delayed slots too.
private const val CLEANUP_WAIT_SECONDS = 5L

// why: sample completion without a busy loop, while keeping normal cleanup below one file-lane tick.
private const val CLEANUP_POLL_MS = 10L
private val CLEANUP_WAIT_NS = TimeUnit.SECONDS.toNanos(CLEANUP_WAIT_SECONDS)
private val CLEANUP_POLL_NS = TimeUnit.MILLISECONDS.toNanos(CLEANUP_POLL_MS)

/** The test modules configure this for every temporary root, including future head fixtures. */
public class HeadFileWriteCleanup : TempDirDeletionStrategy {
    public override fun delete(
        root: Path,
        elementContext: AnnotatedElementContext,
        extensionContext: ExtensionContext,
    ): TempDirDeletionStrategy.DeletionResult {
        awaitWrites(root)
        return TempDirDeletionStrategy.Standard.INSTANCE.delete(root, elementContext, extensionContext)
    }

    /** A bounded refusal is a test failure, never permission to delete past a live writer. */
    public fun awaitWrites(root: Path) {
        val deadline = System.nanoTime() + CLEANUP_WAIT_NS
        var pending = AsyncFileIo.pendingUnder(root)
        while (pending.count != 0) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0 || Thread.currentThread().isInterrupted) {
                val paths = pending.paths.joinToString().ifEmpty { "none tracked under $root" }
                throw IOException(
                    "head test file writes did not finish before deleting $root; " +
                        "pending count=${pending.count}; pending paths: $paths",
                )
            }
            LockSupport.parkNanos(minOf(CLEANUP_POLL_NS, remaining))
            pending = AsyncFileIo.pendingUnder(root)
        }
    }
}
