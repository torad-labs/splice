// PORT-OF: server/src/config.mjs layers/getConfig @ pre-public-port-baseline — the MERGE ENGINE.
// Invariants: layers are observed FRESH on every read, with immutable normalized snapshots reused
// only for unchanged observations; precedence defaults <- headOverrides(TOML) <- state config.json (mtime-cached)
// <- env (alias order) <- runtime PATCH <- perHead ([heads.<key>.overrides] TOML, a command's own value, which beats
// every shared layer since Oct 12); PATCH persists to the state file best-effort (env still wins at next boot).
// SEAM (recorded): env access is injected as a reader function so tests can fake the
// environment (the JVM cannot setenv); production passes System::getenv.
// HD-25 (2026-08-18) split three responsibilities out of this file, all into splice.core.config so
// no consumer's imports moved: the per-knob coercion + the normalization floors -> ConfigCoercion.kt
// (which carries those invariants in its own header), the typed accessor view -> SpliceConfig.kt,
// the two return DTOs -> ConfigResults.kt. What stays here is one thing: the precedence order, and
// the mutable state that order is read through. The three couplings that must NOT be split are
// named at their sites below — layers()/layeredRaw (one precedence order expressed twice),
// patch()/persistApplied (a concurrency contract over runtimeLayer + the fileCache invalidation),
// and readFileLayer + MTIME_RESOLUTION_WINDOW_MS (one torn-read defence).
package splice.core.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.core.util.PathProbe
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

public class ConfigService(
    /** The selected root is shared with stateful control routes, never re-resolved from process defaults. */
    public val statePaths: StatePaths,
    // NAME IS A TRAP (three agents mis-read it): this is the GLOBAL knob layer sourced from the
    // head-topology FILE ([defaults] + [daemon]), NOT a per-head dimension. Per-head lives in
    // [perHeadOverrides] below.
    headOverrides: Map<String, String> = emptyMap(),
    // headKey -> knob map, from [heads.<key>.overrides]. Applied only by getConfig(headKey), so a
    // head can hold its own maxInflight/timeouts without the value leaking onto its siblings.
    perHeadOverrides: Map<String, Map<String, String>> = emptyMap(),
    private val envReader: EnvReader = EnvReader(System::getenv),
    /** Daemon log sink (Main.persistentLogger): writes BOTH stderr and daemon.log, which is what
     *  /mgmt/logs tails. A bare System.err.println reaches stderr ONLY, so its line never appears in
     *  the log endpoint — the failure you most want to read is the one you cannot (wall
     *  kt-no-println, 2026-07-27). Defaults to a no-op so tests need not thread it; the daemon
     *  always injects the real sink. */
    private val log: LogSink = LogSink(DaemonLog::write),
) {
    private val json = Json { ignoreUnknownKeys = true }

    // The dissolved companion's coercion/normalization half (Kotlin style law, 2026-08-16).
    // Stateless apart from the injected [envReader], so one instance per service is enough.
    private val coercion = ConfigCoercion(envReader)

    private val headOverrides = Collections.unmodifiableMap(LinkedHashMap(headOverrides))
    private val defaults: Map<String, Any?> =
        Collections.unmodifiableMap(Knob.entries.associate { it.key to it.default })
    private val globalKnobs = Collections.unmodifiableMap(
        coerceAll(this.headOverrides).accepted.filterKeys { it !in headOnlyKnobKeys },
    )
    private val snapshots = ConfigSnapshotCache()

    // PATCH publishes one immutable batch. Readers never retain a map another thread can mutate.
    private val runtimeLock = Any()
    private val persistLock = Any()
    private var runtimeLayer: Map<String, Any?> = emptyMap()

    /** [heads.<key>.overrides] as the daemon holds them now: the boot read, then each console write of splice.toml. */
    public val headLayer: HeadOverrideLayer = HeadOverrideLayer(perHeadOverrides)

    init {
        headLayer.observeChanges(ConfigRevisionChanged(snapshots::invalidate))
        // V4-109: say it ONCE, at construction, for every key the TOML layers asked for and did not
        // get. Logged here rather than at each read because the merge runs per request — a line per
        // read would bury the daemon log in a config mistake that never changes. The doctor repeats
        // the same facts as a named row, so an operator who never reads the log still sees them.
        coerceRejects().forEach { (where, why) ->
            log("[config] ignoring $where: $why; the knob keeps its default\n")
        }
    }

    private data class FileCache(
        val path: Path,
        val modified: FileTime,
        val size: Long,
        val raw: Map<String, String>,
        val data: Map<String, Any?>,
    )

    // Identity is the fence: invalidation replaces the holder even when its cache was already empty.
    private data class FileObservation(val cached: FileCache?)

    private val fileCache = AtomicReference(FileObservation(null))

    /** Effective config. [headKey] folds that head's [heads.<key>.overrides] in above the global
     *  TOML layer — an unknown/absent key is simply the global view, so callers never branch. */
    @JvmOverloads
    public fun getConfig(headKey: String? = null): SpliceConfig = snapshot(headKey).effective

    public fun layers(): ConfigLayers = snapshot().layers

    // The guard cascade is the literal port of config.mjs's patch loop (each `when` arm is one of
    // its `continue`s): unknown key -> reject; null -> delete; uncoercible -> reject; else -> apply.
    public fun patch(partial: Map<String, Any?>): PatchResult {
        val applied = LinkedHashMap<String, Any?>()
        val rejected = LinkedHashMap<String, String>()
        for ((key, raw) in partial) {
            val knob = knobsByKey[key]
            val folderRefusal = ProjectFolderCheck.refusalOn(knob, raw?.toString())
            when {
                knob == null -> rejected[key] = "unknown key"
                knob.headOnly -> rejected[key] = HEAD_ONLY
                folderRefusal != null -> rejected[key] = folderRefusal
                raw == null -> {
                    applied[key] = null
                }
                else -> {
                    val coerced = coercion.coerce(knob, raw.toString())
                    if (coerced == null) {
                        rejected[key] = "invalid value"
                    } else {
                        applied[key] = coerced
                    }
                }
            }
        }
        val notPersisted = if (applied.isEmpty()) {
            null
        } else {
            synchronized(persistLock) {
                synchronized(runtimeLock) {
                    runtimeLayer = Collections.unmodifiableMap((runtimeLayer + applied).filterValues { it != null })
                    snapshots.invalidate()
                }
                persistApplied(applied)
            }
        }
        val restartRequired = applied.keys.filter { it in restartRequiredKnobKeys }
        return PatchResult(applied, rejected, restartRequired, getConfig(), notPersisted)
    }

    /** One observation pairs effective values and provenance. File freshness and environment aliases stay live. */
    @JvmOverloads
    public fun snapshot(headKey: String? = null): ConfigSnapshot {
        val ticket = snapshots.begin()
        val file = fileLayer()
        val environment = snapshots.environment(envReader)
        val inputs = synchronized(runtimeLock) {
            ConfigSnapshotCache.Inputs(
                file,
                environment,
                runtimeLayer,
                headLayer.all(),
                envReader("CODEX_PROXY_TEST") == "1",
            )
        }
        val previous = snapshots.current()
        val generation = if (previous != null && previous.inputs == inputs) {
            previous
        } else {
            snapshots.publish(ticket, buildSnapshot(inputs, previous))
        }
        generation.envRejected.keys.forEach { key ->
            log("[config] ignoring invalid env value for $key: '${environment[key]}'")
        }
        return generation.snapshot(headKey, FreshConfig { getConfig(headKey) })
    }

    private fun buildSnapshot(
        inputs: ConfigSnapshotCache.Inputs,
        previous: ConfigSnapshotCache.Generation?,
    ): ConfigSnapshotCache.Generation {
        val env = if (previous != null && previous.inputs.env == inputs.env) {
            CoercedLayer(previous.global.layers.env, previous.envRejected)
        } else {
            coerceAll(inputs.env)
        }
        val perHead = inputs.heads.filterValues { it.isNotEmpty() }.mapValues { (head, raw) ->
            previous?.global?.layers?.perHead?.get(head)?.takeIf { previous?.inputs?.heads?.get(head) == raw }
                ?: coerceAll(raw).accepted
        }.let(Collections::unmodifiableMap)
        val layers = ConfigLayers(defaults, globalKnobs, inputs.file, env.accepted, inputs.runtime, perHead)
        // Most-specific head values are LAST, above every shared layer, including runtime PATCH.
        val globalRaw = LinkedHashMap(globalKnobs)
        globalRaw.putAll(inputs.file)
        globalRaw.putAll(env.accepted)
        globalRaw.putAll(inputs.runtime)
        val view = ConfigSnapshotCache.View(
            defaults,
            coercion,
            layers,
            ConfigSnapshotCache.HeadRead { getConfig(it) },
            inputs.testIdleFloor,
        )
        return ConfigSnapshotCache.Generation(
            inputs,
            view(globalRaw, null),
            Collections.unmodifiableMap(perHead.mapValues { (head, values) -> view(globalRaw + values, head) }),
            env.rejected,
        )
    }

    /** Every key the TOML layers asked for and did not get, keyed by where the operator WROTE it:
     *  a global key as itself, a per-head one as `heads.<head>.<key>`. The doctor renders these as
     *  named rows, and the constructor renders them once into the log — the two surfaces the
     *  operator actually looks at, from the one computation. */
    public fun coerceRejects(): Map<String, String> {
        val rejects = LinkedHashMap<String, String>()
        coerceAll(headOverrides).rejected.forEach { (key, why) -> rejects[key] = why }
        headOverrides.keys.filter { it in headOnlyKnobKeys }.forEach { rejects[it] = HEAD_ONLY }
        headLayer.all().forEach { (head, raw) ->
            coerceAll(raw).rejected.forEach { (key, why) -> rejects["heads.$head.$key"] = why }
        }
        return rejects
    }

    private fun coerceAll(raw: Map<String, String>): CoercedLayer {
        val accepted = LinkedHashMap<String, Any?>()
        val rejected = LinkedHashMap<String, String>()
        for ((key, value) in raw) {
            val knob = knobsByKey[key]
            if (knob == null) {
                rejected[key] = UNKNOWN_KEY
                continue
            }
            val coerced = coercion.coerce(knob, value)
            if (coerced == null) {
                rejected[key] = "not a valid ${knob.kind.name.lowercase()}"
            } else {
                accepted[key] = coerced
            }
        }
        return CoercedLayer(Collections.unmodifiableMap(accepted), Collections.unmodifiableMap(rejected))
    }

    // Best-effort by design (port fidelity): a broken/absent state file yields {} — the daemon
    // must never crash on config reads. But a PRESENT file being discarded is logged, latched per
    // mtime so the merge-per-request path cannot spam (DR-9 second arm: every persisted knob
    // silently reverting to defaults, while envLayer logged the single-value equivalent). Absence
    // is proven by the read itself, never a Files.exists pre-gate (DR-9 class law): only NoSuchFile
    // with no NOFOLLOW path entry is the quiet fresh-install {} — a dangling link throws NoSuch too
    // but its entry exists, and an untraversable parent fails exists() checks entirely, which is
    // exactly how the old pre-gate lied "absent". So exists(NOFOLLOW) only disambiguates a caught
    // NoSuch; untraversable parents, inaccessible targets, and dangling links all log their discard.
    private fun fileLayer(): Map<String, Any?> {
        val observed = fileCache.get()
        val read = Cancellables.runCatchingCancellable { readFileLayer(observed) }
        val failure = read.exceptionOrNull()
        if (failure != null) fileCache.compareAndSet(observed, FileObservation(null))
        val genuinelyAbsent = failure is java.nio.file.NoSuchFileException &&
            !Files.exists(statePaths.configFile, LinkOption.NOFOLLOW_LINKS)
        if (failure == null || genuinelyAbsent) {
            // A healthy read OR a proven fresh install re-arms both discard latches: the next
            // unreadable episode is new news, not a continuation.
            discardStreakLogged.set(false)
            discardLoggedFor.set(null)
        } else {
            logFileLayerDiscard(failure)
        }
        return read.getOrDefault(emptyMap())
    }

    // CAS, not volatile check-then-set (DR-9 redo, 2026-08-31): the merge-per-request path calls
    // this concurrently, and a racing check-then-set logged the same mtime up to 11 times under a
    // 64-way probe. One CAS attempt per caller: the winner logs, same-mtime losers return, and a
    // loser holding a NEWER mtime logs it on its next call.
    private val discardLoggedFor = AtomicReference<FileTime?>(null)

    // DR-9 (codex latch trap): an access-indeterminate file usually has no readable mtime either, so
    // the CAS above cannot dedup it — unlatched, the merge-per-request path logged the discard on
    // EVERY call. One line per failure STREAK; fileLayer re-arms on a healthy read or proven absence.
    private val discardStreakLogged = AtomicBoolean(false)

    private fun logFileLayerDiscard(cause: Throwable?) {
        // A null mtime falls to the streak guard below; the discard is logged either way.
        val mtime = PathProbe.modified(statePaths.configFile)
        if (mtime != null) {
            val seen = discardLoggedFor.get()
            if (mtime == seen || !discardLoggedFor.compareAndSet(seen, mtime)) return
        } else if (!discardStreakLogged.compareAndSet(false, true)) {
            return
        }
        log(
            "[config] config.json present but unreadable (${cause?.let(SafeFailureText::render)}); " +
                "persisted knobs ignored, defaults/env in effect",
        )
    }

    private fun readFileLayer(observed: FileObservation): Map<String, Any?> {
        val path = statePaths.configFile
        // No existence pre-gate (DR-9): absence is proven by this stat throwing NoSuchFile, which
        // fileLayer classifies (genuine absence quiet, dangling/inaccessible logged).
        val modified = Files.getLastModifiedTime(path)
        val size = Files.size(path)
        // A same-second edit that lands at an identical byte count is invisible to (mtime, size)
        // alone (torn-read risk) — refuse the cache while the file's mtime is still within the
        // resolution window, forcing a fresh read until it ages past it.
        val cacheable = System.currentTimeMillis() - modified.toMillis() >= MTIME_RESOLUTION_WINDOW_MS
        val cached = observed.cached
        if (cacheable && cached != null) {
            if (cached.path == path && cached.modified == modified) {
                if (cached.size == size) return cached.data
            }
        }
        val parsed = json.parseToJsonElement(Files.readString(path)).jsonObject
        val raw = LinkedHashMap<String, String>()
        for (knob in Knob.entries) {
            fileScalar(parsed, knob)?.let { raw[knob.key] = it }
        }
        val data = cached?.data?.takeIf { cached.raw == raw } ?: Collections.unmodifiableMap(
            coerceAll(raw).accepted.filterKeys { it !in headOnlyKnobKeys },
        )
        val next = FileCache(path, modified, size, Collections.unmodifiableMap(raw), data)
        fileCache.compareAndSet(observed, FileObservation(next))
        return data
    }

    private fun fileScalar(parsed: JsonObject, knob: Knob): String? {
        val el = parsed[knob.key] ?: return null
        return coercion.jsonScalar(el)?.toString()
    }

    // Best-effort by design (port fidelity): persistence failure must not undo the applied
    // runtime layer. Env still wins at next boot — the launcher is the boot authority. A failure is
    // returned as well as logged, so PATCH /api/config never answers "persisted" for a write that did
    // not land (V4-299); null means it landed.
    private fun persistApplied(applied: Map<String, Any?>): String? =
        Cancellables.runCatchingCancellable {
            synchronized(persistLock) {
                val path = statePaths.configFile
                val onDisk = readOnDiskStrict(path)
                val next = mergePersisted(onDisk, applied)
                SecureFile.writeAtomic0600(path, json.encodeToString(JsonObject.serializer(), next) + "\n")
                fileCache.set(FileObservation(null))
                snapshots.invalidate()
            }
        }.fold(
            onSuccess = { null },
            onFailure = { e ->
                val why = SafeFailureText.render(e)
                log("[config] failed to persist config to disk: $why")
                "${statePaths.configFile} could not be written ($why); the change is live until the daemon " +
                    "restarts, which reads the saved value again"
            },
        )

    /** MUTATION-path read (DR-9, the KeyStore.entriesStrict doctrine): PROVEN-ABSENT = legitimately
     *  empty (safe to seed); UNREADABLE = unknown state — abort THIS persist rather than merge over
     *  an empty base and atomically destroy every previously persisted knob. Absence is proven by
     *  the read throwing NoSuchFile with no NOFOLLOW path entry — never by a Files.exists pre-gate,
     *  which read false for an inaccessible config SYMLINK and let writeAtomic0600 atomically
     *  REPLACE the operator's link with a fresh file (the knobs on the real target shadowed, the
     *  link destroyed). A dangling link aborts too: its entry exists, so seeding would replace it.
     *  The runtime layer is already applied either way; the path keeps its shape for the operator
     *  to fix. */
    private fun readOnDiskStrict(path: Path): JsonObject =
        Cancellables.runCatchingCancellable { json.parseToJsonElement(Files.readString(path)).jsonObject }
            .getOrElse {
                val genuinelyAbsent = it is java.nio.file.NoSuchFileException &&
                    !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                if (!genuinelyAbsent) {
                    // A FileSystemException — still IOException, so the outer best-effort wrap
                    // catches it (an IllegalState would escape runCatchingCancellable's caught set
                    // and fail patch() itself) AND SafeFailureText passes its reason through
                    // verbatim when the persist log renders it (DR-73).
                    throw java.nio.file.FileSystemException(
                        path.toString(),
                        null,
                        "config.json unreadable (${SafeFailureText.render(it)}); refusing to rewrite, " +
                            "persisted knobs preserved",
                    )
                }
                JsonObject(emptyMap())
            }

    private fun mergePersisted(onDisk: JsonObject, applied: Map<String, Any?>): JsonObject =
        buildJsonObject {
            onDisk.forEach { (k, v) -> if (k !in applied) put(k, v) }
            applied.forEach { (k, v) ->
                when (v) {
                    null -> Unit // deletion = omission from the persisted file
                    is Boolean -> put(k, JsonPrimitive(v))
                    is Long -> put(k, JsonPrimitive(v))
                    else -> put(k, JsonPrimitive(v.toString()))
                }
            }
        }
}

// Companion dissolved to file scope (Kotlin style law, 2026-08-16 — HD-M8): this is a plain
// constant and a top-level `private const val` is its sanctioned home. `private` at file scope is
// file-private in Kotlin, so nothing else in splice.core.config can see or collide with it — which
// is also why HD-25 (2026-08-18) had to take the ten clamp/floor constants WITH ConfigCoercion into
// ConfigCoercion.kt rather than leave them here. This one stays: it is part of the torn-read
// defence in [ConfigService.readFileLayer], not of the clamp table.
// Filesystem mtime granularity is 1s on many platforms; a same-second edit landing at an
// identical byte count is otherwise indistinguishable from an unchanged file (CONF-3).
private const val MTIME_RESOLUTION_WINDOW_MS = 2_000L

/** V4-109: what one raw TOML layer asked for and what it actually got. [accepted] is what merges;
 *  [rejected] maps the key the operator WROTE to why it was dropped, which is the half that used to
 *  vanish — a mistyped key or an uncoercible value was discarded in silence, so the knob kept its
 *  default and nothing anywhere said the operator's line had not been read. */
private data class CoercedLayer(
    val accepted: Map<String, Any?>,
    val rejected: Map<String, String>,
)

private const val UNKNOWN_KEY: String = "unknown key"
private const val HEAD_ONLY: String =
    "set per head in [heads.<key>.overrides], never globally: it keeps that head's whole conversations"
