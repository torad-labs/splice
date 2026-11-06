package splice.observability.profile

import kotlinx.serialization.json.JsonObject
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.runtime.PerfProfileArtifact
import splice.core.perf.runtime.PerfProfileRequest
import splice.core.perf.runtime.PerfProfiles
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Construction starts no capture. One bounded worker owns start, stop, copy and cleanup, off request threads. */
public class ProfileManager(
    directory: Path,
    private val identity: PerfIdentity,
    nativeExecutable: Path? = null,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val factory: ProfileCaptureFactory = DefaultProfileCaptures,
) : PerfProfiles, AutoCloseable {
    private val lock = Any()
    private val capabilities = ProfileCapabilities(nativeExecutable)
    private val artifacts = ProfileArtifacts(directory, identity)
    private val records = linkedMapOf<String, ProfileRecord>()
    private val memory =
        checkNotNull(PerfWorkBudget.acquire((ProfileLimits.MAX_RECORDS + 1) * ProfileLimits.METADATA_BYTES))
    private val worker =
        ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS, ArrayBlockingQueue(1), ProfileThreads.factory("splice-profile"))
    private val supervisor = ScheduledThreadPoolExecutor(1, ProfileThreads.factory("splice-profile-deadline")).apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }
    private var active: ProfileRun? = null
    private var closed = false

    init {
        try {
            artifacts.lock()
            artifacts.clearPreviousBoot()
            supervisor.scheduleWithFixedDelay(
                { maintenance() },
                RETENTION_TICK_SECONDS,
                RETENTION_TICK_SECONDS,
                TimeUnit.SECONDS,
            )
        } catch (error: IOException) {
            setupFailed(error)
        } catch (error: IllegalStateException) {
            setupFailed(error)
        } catch (error: SecurityException) {
            setupFailed(error)
        }
    }

    override fun capabilities(): JsonObject = ProfileReplies.create { capabilities.json() }

    override fun start(request: PerfProfileRequest): JsonObject = ProfileReplies.create {
        synchronized(lock) {
            val safeRequest = request.copy(
                profiler = request.profiler.take(MAX_SETTING_LENGTH),
                mode = request.mode.take(MAX_SETTING_LENGTH),
            )
            val record = ProfileRecord(safeRequest, clock())
            val reason = capabilities.refusal(request)
            when {
                closed -> record.reply(identity, clock(), PROFILE_FAILED, "manager_closed")
                active != null -> record.reply(identity, clock(), "busy", "one_capture_at_a_time")
                reason != null -> record.reply(identity, clock(), "unsupported", reason)
                !ProfileAdmission.acquire() -> record.reply(identity, clock(), "busy", "one_capture_per_process")
                else -> {
                    val run = ProfileRun(record)
                    active = run
                    records[record.id] = record
                    worker.execute { execute(run) }
                    record.json(identity)
                }
            }
        }
    }
    override fun stop(id: String): JsonObject = synchronized(lock) {
        active?.takeIf { it.record.id == id }?.stop()
        status(id)
    }

    override fun status(id: String?): JsonObject = ProfileReplies.create {
        synchronized(lock) {
            val record = if (id == null) active?.record ?: records.values.lastOrNull() else records[id]
            record?.json(
                identity,
                clock(),
            ) ?: ProfileRecord(PerfProfileRequest(), clock())
                .reply(identity, clock(), PROFILE_FAILED, "profile_not_found_or_expired")
        }
    }

    override fun artifact(id: String): PerfProfileArtifact? = synchronized(lock) {
        records[id]?.takeIf { clock() - (it.untilMs ?: it.fromMs) <= ProfileLimits.RETENTION_MS }?.let(
            artifacts::artifact,
        )
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            active?.takeIf { it.record.status == "running" }?.let { run ->
                run.abort()
                settle(run, "aborted", "manager_closed")
            }
            if (active == null) {
                releaseMemory()
                supervisor.shutdown()
            }
        }
        worker.shutdown()
        // The active worker's finally block owns capture closure before shutting down its supervisor.
        try {
            worker.awaitTermination(ProfileLimits.CONTROL_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (worker.isTerminated) supervisor.shutdown()
    }

    private fun execute(run: ProfileRun) {
        run.thread = Thread.currentThread()
        val expiry = supervisor.schedule({
            synchronized(lock) {
                if (run.record.status == "running") {
                    run.abort()
                    settle(run, "aborted", "capture_deadline_expired")
                }
            }
        }, run.record.request.durationSeconds + FINALIZATION_SECONDS, TimeUnit.SECONDS)
        try {
            prune()
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
            settle(run, PROFILE_FAILED, "profile_io_or_control_failed")
        } catch (_: RuntimeException) {
            settle(run, PROFILE_FAILED, "profile_runtime_failed")
        } finally {
            expiry.cancel(false)
            cleanup(run)
        }
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
            synchronized(lock) {
                record.captureFromMs = it.fromMs
                record.captureUntilMs = it.untilMs
            }
            path
        }
        synchronized(lock) {
            run.ensureActive()
            artifacts.publish(record, temporary)
            settle(run, "completed", null)
        }
    }

    private fun settle(run: ProfileRun, status: String, issue: String?) = synchronized(lock) {
        run.record.status = status
        run.record.issue = issue
        run.record.untilMs = clock()
    }

    private fun cleanup(run: ProfileRun) {
        val interrupted = Thread.interrupted()
        try {
            artifacts.deleteTemporary(run.record)
            artifacts.metadata(run.record)
        } catch (_: IOException) {
            settle(run, PROFILE_FAILED, "profile_metadata_or_cleanup_failed")
        } finally {
            synchronized(lock) {
                if (active === run) active = null
                ProfileAdmission.release()
                if (closed) {
                    supervisor.shutdown()
                    releaseMemory()
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun prune() = synchronized(lock) {
        val completed = records.values.filter { it.untilMs != null }
        var total = completed.filter { it.available }.sumOf { it.sizeBytes }
        completed.forEach { record ->
            val expired = clock() - checkNotNull(record.untilMs) > ProfileLimits.RETENTION_MS
            val overBudget = total > ProfileLimits.STORE_BYTES - ProfileLimits.ARTIFACT_BYTES
            val remove = expired || overBudget
            if (record.available && remove) {
                artifacts.expire(record)
                total -= record.sizeBytes
            }
        }
        while (records.size > ProfileLimits.MAX_RECORDS) {
            val oldest = records.values.first()
            artifacts.forget(oldest)
            records.remove(oldest.id)
        }
    }

    private fun maintenance() {
        synchronized(lock) {
            if (closed || active != null) return
            try {
                worker.execute { prune() }
            } catch (_: RejectedExecutionException) {
                // A new capture acquired the single worker; it performs the same pruning before setup.
                return
            }
        }
    }

    private fun releaseMemory() {
        records.clear()
        memory.close()
        artifacts.close()
    }

    private fun setupFailed(error: Exception): Nothing {
        try {
            artifacts.close()
        } finally {
            memory.close()
            worker.shutdown()
            supervisor.shutdown()
        }
        throw error
    }
}

private const val FINALIZATION_SECONDS = 30L
private const val RETENTION_TICK_SECONDS = 60L
private const val MAX_SETTING_LENGTH = 16
private const val PROFILE_FAILED = "failed"
