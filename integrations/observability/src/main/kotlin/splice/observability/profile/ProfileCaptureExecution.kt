package splice.observability.profile

import splice.core.util.WallClock
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Executes one admitted capture, including its deadline, publication and final metadata, on the bounded worker. */
internal class ProfileCaptureExecution(
    private val archive: ProfileArchive,
    private val capabilities: ProfileCapabilities,
    private val factory: ProfileCaptureFactory,
    private val clock: WallClock,
) : AutoCloseable {
    private val artifacts = archive.artifacts
    private val worker =
        ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS, ArrayBlockingQueue(1), ProfileThreads.factory("splice-profile"))
    private val supervisor = ScheduledThreadPoolExecutor(1, ProfileThreads.factory("splice-profile-deadline")).apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }

    fun scheduleMaintenance(action: Runnable) {
        supervisor.scheduleWithFixedDelay(action, RETENTION_TICK_SECONDS, RETENTION_TICK_SECONDS, TimeUnit.SECONDS)
    }

    fun enqueueMaintenance(action: Runnable) { worker.execute(action) }

    fun submit(run: ProfileRun, completion: ProfileCompletion) {
        worker.execute { execute(run, completion) }
    }

    fun abortStartup() {
        worker.shutdown()
        supervisor.shutdown()
    }

    fun shutdownSupervisor() { supervisor.shutdown() }

    override fun close() {
        worker.shutdown()
        // The active worker's finally block owns capture closure before shutting down its supervisor.
        try {
            worker.awaitTermination(ProfileLimits.CONTROL_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (worker.isTerminated) supervisor.shutdown()
    }

    private fun execute(run: ProfileRun, completion: ProfileCompletion) {
        run.thread = Thread.currentThread()
        val expiry = supervisor.schedule({
            synchronized(archive.lock) {
                if (run.record.status == "running") abort(run, "capture_deadline_expired")
            }
        }, run.record.request.durationSeconds + FINALIZATION_SECONDS, TimeUnit.SECONDS)
        try {
            archive.prune(run.record)
            artifacts.metadata(run.record)
            capture(run)
        } catch (_: ProfileUnavailable) {
            settle(run, "unsupported", "profiler_mode_unavailable")
        } catch (_: ProfileOversized) {
            settle(run, "oversized", "artifact_limit_exceeded")
        } catch (_: InterruptedException) {
            settle(run, "aborted", "capture_cancelled_or_deadline_expired")
            Thread.currentThread().interrupt()
        } catch (_: IOException) {
            settle(run, "failed", "profile_io_or_control_failed")
        } catch (_: RuntimeException) {
            settle(run, "failed", "profile_runtime_failed")
        } finally {
            expiry.cancel(false)
            cleanup(run, completion)
        }
    }

    fun abort(run: ProfileRun, issue: String) {
        run.abort()
        settle(run, "aborted", issue)
    }

    private fun capture(run: ProfileRun) {
        val record = run.record
        val capture = BoundedProfileCapture(
            factory.create(record.request, capabilities.nativeExecutable, supervisor),
            run,
            supervisor,
        )
        val temporary = capture.use {
            run.ensureActive()
            it.start()
            if (record.request.profiler == "native") capabilities.verified(record.request.mode)
            run.awaitDuration()
            run.ensureActive()
            val path = artifacts.temporary(record)
            it.finish(path)
            synchronized(archive.lock) {
                record.captureFromMs = it.fromMs
                record.captureUntilMs = it.untilMs
            }
            path
        }
        synchronized(archive.lock) {
            run.ensureActive()
            artifacts.publish(record, temporary)
            settle(run, "completed", null)
        }
    }

    private fun settle(run: ProfileRun, status: String, issue: String?) = synchronized(archive.lock) {
        run.record.status = status
        run.record.issue = issue
        run.record.untilMs = clock()
    }

    private fun cleanup(run: ProfileRun, completion: ProfileCompletion) {
        val interrupted = Thread.interrupted()
        try {
            artifacts.deleteTemporary(run.record)
            artifacts.metadata(run.record)
        } catch (_: IOException) {
            settle(run, "failed", "profile_metadata_or_cleanup_failed")
        } finally {
            completion.complete(run)
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}

internal fun interface ProfileCompletion {
    fun complete(run: ProfileRun)
}

// Allow thirty seconds beyond the requested recording window for bounded artifact finalization and copying.
private const val FINALIZATION_SECONDS = 30L

// Recheck idle artifact retention once per minute instead of adding file maintenance to every control read.
private const val RETENTION_TICK_SECONDS = 60L
