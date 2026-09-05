// PORT-OF: server/src/config.mjs layers/getConfig @ pre-public-port-baseline — the MERGE ENGINE.
// Invariants: layers merge FRESH on every read (v29 froze knobs at import; nothing was
// hot-tunable); precedence defaults <- headOverrides(TOML, NEW layer) <- perHead
// ([heads.<key>.overrides] TOML, more specific than the global one) <- state config.json
// (mtime-cached) <- env (alias order) <- runtime PATCH; PATCH persists to the state file
// best-effort (env still wins at next boot).
// SEAM (recorded): env access is injected as a reader function so tests can fake the
// environment (the JVM cannot setenv); production passes System::getenv.
// HD-25 (2026-08-18) split three responsibilities out of this file, all into splice.core.config so
// no consumer's imports moved: the per-knob coercion + the normalization floors -> ConfigCoercion.kt
// (which carries those invariants in its own header), the typed accessor view -> SpliceConfig.kt,
// the two return DTOs -> ConfigResults.kt. What stays here is one thing: the precedence order, and
// the mutable state that order is read through. The three couplings that must NOT be split are
// named at their sites below — layers()/mergedRaw (one precedence order expressed twice),
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
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

public class ConfigService(
    private val statePaths: StatePaths,
    // NAME IS A TRAP (three agents mis-read it): this is the GLOBAL knob layer sourced from the
    // head-topology FILE ([defaults] + [daemon]), NOT a per-head dimension. Per-head lives in
    // [perHeadOverrides] below.
    private val headOverrides: Map<String, String> = emptyMap(),
    // headKey -> knob map, from [heads.<key>.overrides]. Applied only by getConfig(headKey), so a
    // head can hold its own maxInflight/timeouts without the value leaking onto its siblings.
    private val perHeadOverrides: Map<String, Map<String, String>> = emptyMap(),
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

    // PATCH mutates on control-plane threads while every request thread merges — guard every
    // touch with [runtimeLock] and hand out COPIES only (audit 2026-07-18: CME risk + torn reads).
    private val runtimeLock = Any()
    private val persistLock = Any()
    private val runtimeLayer = LinkedHashMap<String, Any?>()

    private data class FileCache(
        val path: Path,
        val modified: FileTime,
        val size: Long,
        val data: Map<String, Any?>,
    )

    @Volatile
    private var fileCache: FileCache? = null

    /** Effective config. [headKey] folds that head's [heads.<key>.overrides] in above the global
     *  TOML layer — an unknown/absent key is simply the global view, so callers never branch. */
    @JvmOverloads
    public fun getConfig(headKey: String? = null): SpliceConfig =
        SpliceConfig(coercion.normalize(mergedRaw(headKey)))

    public fun layers(): ConfigLayers = ConfigLayers(
        defaults = Knob.entries.associate { it.key to it.default },
        headOverrides = coerceAll(headOverrides),
        file = fileLayer(),
        env = envLayer(),
        runtime = synchronized(runtimeLock) { runtimeLayer.toMap() },
        // JW-06: [heads.<key>.overrides] folds into mergedRaw but was invisible here — the
        // dashboard's provenance feature was silently wrong for exactly the heads that were
        // tuned. Heads with no overrides are absent (the webui renders only what differs).
        perHead = perHeadOverrides.filterValues { it.isNotEmpty() }.mapValues { (_, v) -> coerceAll(v) },
    )

    // The guard cascade is the literal port of config.mjs's patch loop (each `when` arm is one of
    // its `continue`s): unknown key -> reject; null -> delete; uncoercible -> reject; else -> apply.
    public fun patch(partial: Map<String, Any?>): PatchResult {
        val applied = LinkedHashMap<String, Any?>()
        val rejected = LinkedHashMap<String, String>()
        for ((key, raw) in partial) {
            val knob = knobsByKey[key]
            when {
                knob == null -> rejected[key] = "unknown key"
                raw == null -> {
                    applied[key] = null
                    synchronized(runtimeLock) { runtimeLayer.remove(key) }
                }
                else -> {
                    val coerced = coercion.coerce(knob, raw)
                    if (coerced == null) {
                        rejected[key] = "invalid value"
                    } else {
                        applied[key] = coerced
                        synchronized(runtimeLock) { runtimeLayer[key] = coerced }
                    }
                }
            }
        }
        if (applied.isNotEmpty()) persistApplied(applied)
        val restartRequired = applied.keys.filter { it in restartRequiredKnobKeys }
        return PatchResult(applied, rejected, restartRequired, getConfig())
    }

    private fun mergedRaw(headKey: String? = null): Map<String, Any?> {
        val merged = LinkedHashMap<String, Any?>()
        Knob.entries.forEach { merged[it.key] = it.default }
        coerceAll(headOverrides).forEach { (k, v) -> merged[k] = v }
        // Sits directly above the global TOML layer: more specific TOML wins over less specific,
        // while state/env/PATCH keep their existing authority over BOTH (unchanged precedence).
        headKey?.let { key -> perHeadOverrides[key]?.let { coerceAll(it) } }
            ?.forEach { (k, v) -> merged[k] = v }
        fileLayer().forEach { (k, v) -> merged[k] = v }
        envLayer().forEach { (k, v) -> merged[k] = v }
        synchronized(runtimeLock) { runtimeLayer.forEach { (k, v) -> merged[k] = v } }
        return merged
    }

    private fun coerceAll(raw: Map<String, String>): Map<String, Any?> =
        raw.entries.mapNotNull { (k, v) ->
            val knob = knobsByKey[k] ?: return@mapNotNull null
            coercion.coerce(knob, v)?.let { k to it }
        }.toMap()

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
        val read = Cancellables.runCatchingCancellable { readFileLayer() }
        val failure = read.exceptionOrNull()
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
        val mtime = Cancellables.runCatchingCancellable { Files.getLastModifiedTime(statePaths.configFile) }
            .getOrNull()
        if (mtime != null) {
            val seen = discardLoggedFor.get()
            if (mtime == seen || !discardLoggedFor.compareAndSet(seen, mtime)) return
        } else if (!discardStreakLogged.compareAndSet(false, true)) {
            return
        }
        log(
            "[config] config.json present but unreadable (${cause?.let(SafeFailureText::render)}) — " +
                "persisted knobs ignored, defaults/env in effect",
        )
    }

    private fun readFileLayer(): Map<String, Any?> {
        val path = statePaths.configFile
        // No existence pre-gate (DR-9): absence is proven by this stat throwing NoSuchFile, which
        // fileLayer classifies (genuine absence quiet, dangling/inaccessible logged).
        val modified = Files.getLastModifiedTime(path)
        val size = Files.size(path)
        // A same-second edit that lands at an identical byte count is invisible to (mtime, size)
        // alone (torn-read risk) — refuse the cache while the file's mtime is still within the
        // resolution window, forcing a fresh read until it ages past it.
        val cacheable = System.currentTimeMillis() - modified.toMillis() >= MTIME_RESOLUTION_WINDOW_MS
        val cached = fileCache
        if (cacheable && cached != null) {
            if (cached.path == path && cached.modified == modified) {
                if (cached.size == size) return cached.data
            }
        }
        val parsed = json.parseToJsonElement(Files.readString(path)).jsonObject
        val data = LinkedHashMap<String, Any?>()
        for (knob in Knob.entries) {
            fileScalar(parsed, knob)?.let { data[knob.key] = it }
        }
        fileCache = FileCache(path, modified, size, data)
        return data
    }

    private fun fileScalar(parsed: JsonObject, knob: Knob): Any? {
        val el = parsed[knob.key] ?: return null
        val scalar = coercion.jsonScalar(el) ?: return null
        return coercion.coerce(knob, scalar)
    }

    private fun envLayer(): Map<String, Any?> {
        val data = LinkedHashMap<String, Any?>()
        for (knob in Knob.entries) {
            val raw = knob.envNames.firstNotNullOfOrNull { name -> envReader(name)?.takeIf { it.isNotEmpty() } }
            if (raw != null) {
                val coerced = coercion.coerce(knob, raw)
                if (coerced != null) {
                    data[knob.key] = coerced
                } else {
                    log("[config] ignoring invalid env value for ${knob.key}: '$raw'")
                }
            }
        }
        return data
    }

    // Best-effort by design (port fidelity): persistence failure must not undo the applied
    // runtime layer. Env still wins at next boot — the launcher is the boot authority.
    private fun persistApplied(applied: Map<String, Any?>) {
        // persistence is best-effort; the runtime layer already applied
        Cancellables.runCatchingCancellable {
            synchronized(persistLock) {
                val path = statePaths.configFile
                val onDisk = readOnDiskStrict(path)
                val next = mergePersisted(onDisk, applied)
                SecureFile.writeAtomic0600(path, json.encodeToString(JsonObject.serializer(), next) + "\n")
                fileCache = null
            }
        }.onFailure { e -> log("[config] failed to persist config to disk: ${SafeFailureText.render(e)}") }
    }

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
                        "config.json unreadable (${SafeFailureText.render(it)}) — refusing to rewrite, " +
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
