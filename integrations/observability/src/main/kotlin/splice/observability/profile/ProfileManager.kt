package splice.observability.profile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.runtime.PerfProfileArtifact
import splice.core.perf.runtime.PerfProfileRead
import splice.core.perf.runtime.PerfProfileRequest
import splice.core.perf.runtime.PerfProfiles
import splice.core.util.Cancellables
import splice.core.util.WallClock
import java.nio.file.Path
import java.util.concurrent.RejectedExecutionException

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
    private val execution = ProfileCaptureExecution(archive, capabilities, factory, clock)
    private var active: ProfileRun? = null
    private var closed = false

    init {
        var initialized = false
        Cancellables.withCleanup({
            if (!initialized) {
                Cancellables.withCleanup({ archive.close() }) {
                    execution.abortStartup()
                }
            }
        }) {
            archive.artifacts.lock()
            archive.artifacts.clearPreviousBoot()
            execution.scheduleMaintenance(::maintenance)
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
                    execution.submit(run, ::complete)
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
            when {
                record != null -> record.json(identity, clock())
                id == null -> none()
                else -> ProfileRecord(PerfProfileRequest(), clock())
                    .reply(identity, clock(), PROFILE_FAILED, "profile_not_found_or_expired")
            }
        }
    }

    /**
     * The answer when there is no capture to report: this process has taken none, or its last one is no longer kept.
     * Until Nov 11, 2026 that was a made-up record, failed, with a new id on every call, and the console drew a red
     * "failed · 0 B" for a capture nobody had started.
     */
    private fun none(): JsonObject = buildJsonObject {
        put("schema_version", identity.schemaVersion)
        put("status", PROFILE_NONE)
        put("profile_id", JsonNull)
        put("identity", Json.encodeToJsonElement(identity))
        put("process_id", ProcessHandle.current().pid())
        put("size_bytes", 0)
        put("artifact_available", false)
        put("issues", JsonArray(emptyList()))
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
                execution.shutdownSupervisor()
            }
        }
        execution.close()
    }

    private fun complete(run: ProfileRun) = synchronized(lock) {
        if (active === run) active = null
        ProfileAdmission.release()
        if (closed) {
            execution.shutdownSupervisor()
            archive.close()
        }
    }

    private fun maintenance() {
        synchronized(lock) {
            if (closed || active != null) return
            try {
                execution.enqueueMaintenance { synchronized(lock) { archive.prune(active?.record) } }
            } catch (_: RejectedExecutionException) {
                // A new capture acquired the single worker; it performs the same pruning before setup.
                return
            }
        }
    }
}

// Retain only sixteen characters of rejected profiler settings so refusal metadata cannot copy arbitrary input.
private const val MAX_SETTING_LENGTH = 16
private const val PROFILE_FAILED = "failed"
private const val PROFILE_NONE = "none"
