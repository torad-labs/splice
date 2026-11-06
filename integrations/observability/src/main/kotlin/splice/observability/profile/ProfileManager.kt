package splice.observability.profile

import kotlinx.serialization.json.JsonObject
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.runtime.PerfProfileArtifact
import splice.core.perf.runtime.PerfProfileRead
import splice.core.perf.runtime.PerfProfileRequest
import splice.core.perf.runtime.PerfProfiles
import splice.core.util.Cancellables
import splice.core.util.WallClock
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
    factory: ProfileCaptureFactory = DefaultProfileCaptures,
) : PerfProfiles, AutoCloseable {
    private val capabilities = ProfileCapabilities(nativeExecutable)
    private val archive = ProfileArchive(ProfileArtifacts(directory, identity), clock)
    private val lock = archive.lock
    private val worker =
        ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS, ArrayBlockingQueue(1), ProfileThreads.factory("splice-profile"))
    private val supervisor = ScheduledThreadPoolExecutor(1, ProfileThreads.factory("splice-profile-deadline")).apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }
    private val execution = ProfileCaptureExecution(archive, capabilities, factory, supervisor, clock)
    private var active: ProfileRun? = null
    private var closed = false

    init {
        var initialized = false
        Cancellables.withCleanup({
            if (!initialized) {
                Cancellables.withCleanup({ archive.close() }) {
                    worker.shutdown()
                    supervisor.shutdown()
                }
            }
        }) {
            archive.artifacts.lock()
            archive.artifacts.clearPreviousBoot()
            supervisor.scheduleWithFixedDelay(
                { maintenance() },
                RETENTION_TICK_SECONDS,
                RETENTION_TICK_SECONDS,
                TimeUnit.SECONDS,
            )
            initialized = true
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
                archive.pinned() ->
                    record.reply(identity, clock(), "busy", "artifact_store_pinned")
                !ProfileAdmission.acquire() -> record.reply(identity, clock(), "busy", "one_capture_per_process")
                else -> {
                    val run = ProfileRun(record)
                    active = run
                    archive.add(record)
                    worker.execute { execution.execute(run, ::complete) }
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
            val record = if (id == null) active?.record ?: archive.record(null) else archive.record(id)
            record?.json(
                identity,
                clock(),
            ) ?: ProfileRecord(PerfProfileRequest(), clock())
                .reply(identity, clock(), PROFILE_FAILED, "profile_not_found_or_expired")
        }
    }

    override fun artifact(id: String): PerfProfileArtifact? = synchronized(lock) { archive.artifact(id) }

    override fun openArtifact(id: String): PerfProfileRead? = synchronized(lock) {
        if (closed) null else archive.open(id)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            active?.takeIf { it.record.status == "running" }?.let { execution.abort(it, "manager_closed") }
            if (active == null) {
                archive.close()
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

    private fun complete(run: ProfileRun) = synchronized(lock) {
        if (active === run) active = null
        ProfileAdmission.release()
        if (closed) {
            supervisor.shutdown()
            archive.close()
        }
    }

    private fun maintenance() {
        synchronized(lock) {
            if (closed || active != null) return
            try {
                worker.execute { synchronized(lock) { archive.prune(active?.record) } }
            } catch (_: RejectedExecutionException) {
                // A new capture acquired the single worker; it performs the same pruning before setup.
                return
            }
        }
    }
}

// Recheck idle artifact retention once per minute instead of adding file maintenance to every control read.
private const val RETENTION_TICK_SECONDS = 60L

// Retain only sixteen characters of rejected profiler settings so refusal metadata cannot copy arbitrary input.
private const val MAX_SETTING_LENGTH = 16
private const val PROFILE_FAILED = "failed"
