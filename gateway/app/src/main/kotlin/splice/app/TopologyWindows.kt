// NEW: V4-162 — the context windows splice.toml declares, re-read while the daemon runs, and the file
// version the daemon therefore RUNS (what /health publishes as topologyDigest).
//
// WHY. A context_window edit used to need a daemon restart because the topology is parsed once at
// boot, yet every window the daemon serves is a number splice authors itself: on the wire through
// usage scaling, and at launch through CLAUDE_CODE_MAX_CONTEXT_TOKENS. The operator, 2026-09-19:
// "we're a proxy, we can change those configurations at proxy time". So the windows follow the file
// and nothing else does; the roster, ports, auth, quirks and knobs stay boot-time.
//
// THE IDIOM IS CompactionInstructions.currentText: a stat per read, the bytes re-read only when the
// (modification time, size) stamp moved, and parsed only when their sha-256 moved. Reading, hashing
// and parsing happen OUTSIDE the monitor (kt-no-blocking-io-under-monitor), which only publishes, and
// a reader that finds another thread mid-refresh answers from what is published instead of waiting.
//
// WHAT THE DAEMON RUNS: the booted bytes, or a later version whose windows are all in force and whose
// every other key equals boot's (Topology.withoutWindows). A window-only or comment-only edit leaves
// nothing stale; any other edit reads stale until a restart, as before.
//
// NEVER-BELOW-STATUS-QUO: a file that does not parse, a head whose new declaration fails catalogFor,
// a head or provider no longer declared, and a local runtime that refuses a window all keep the
// windows in force and say why in daemon.log, and that version does not become the running one. A
// local-runtime head (FEATURES.md §10) is asked about a new window the way boot asks it
// (LocalProbeInputs.check), on this class's own scope: the question is network, and a window read
// sits on the request path of a turn, a statusline post and /health.
package splice.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splice.app.provider.LocalProbeInputs
import splice.app.provider.LocalRowsCheck
import splice.app.provider.ProviderBuild
import splice.core.model.LiveWindows
import splice.core.model.ModelCatalog
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The catalog a head boots with from a declared topology — HeadBuildInputs.catalogFor in production,
 *  so a re-read resolves through exactly the knob remap and override boot applied. */
internal fun interface HeadCatalogs {
    fun catalogFor(key: String, head: HeadConfig, provider: ProviderConfig, legacyKnobsGovern: Boolean): ModelCatalog
}

/** What a local runtime refuses in a head's new windows, one line per row; empty when it accepts them
 *  or cannot be asked (a runtime that is down, or lists no models, boots unchecked too). */
internal fun interface LocalWindowCheck {
    fun refusals(key: String, provider: ProviderConfig, catalog: ModelCatalog): List<String>
}

/** The production [LocalWindowCheck]: [LocalProbeInputs.check], with the key the head itself reads. */
internal class RuntimeWindowCheck(private val env: EnvReader = EnvReader(System::getenv)) : LocalWindowCheck {
    private val inputs = LocalProbeInputs()

    override fun refusals(key: String, provider: ProviderConfig, catalog: ModelCatalog): List<String> =
        when (val found = inputs.check(provider, inputs.bearer(key, provider, env), catalog)) {
            is LocalRowsCheck.Checked -> found.refused.map { "'${it.id}': ${it.reason}" }
            LocalRowsCheck.Down, is LocalRowsCheck.Unlisted -> emptyList()
        }
}

internal class TopologyWindows(
    private val path: Path?,
    private val boot: Topology,
    bootDigest: String,
    private val catalogs: HeadCatalogs,
    private val log: LogSink,
    private val local: LocalWindowCheck = RuntimeWindowCheck(),
    /** Owned here, like AuthProbeLoop's: the local-runtime questions end with Daemon.stop ([close]). */
    private val scope: CoroutineScope = LifecycleScope(ProcessDispatchers().background()),
) : RunningTopology {

    /** What one head booted with. */
    private data class Head(val boot: ModelCatalog, val legacyKnobsGovern: Boolean, val local: Boolean)

    /** The file as last stat'ed; only a moved stamp re-reads the bytes. */
    private data class Stamp(val modified: FileTime, val size: Long)

    /** Everything a read answers from, replaced whole under [lock]. */
    private data class Published(
        val stamp: Stamp?,
        /** sha-256 of the bytes last read. */
        val seen: String,
        /** The version the daemon runs. */
        val running: String,
        /** Heads whose windows moved since boot; a head absent here answers its boot windows. */
        val live: Map<String, ModelCatalog> = emptyMap(),
        /** Local-runtime heads whose new windows wait for their runtime's answer. */
        val pending: Map<String, ModelCatalog> = emptyMap(),
        /** [seen] becomes [running] once nothing is pending: it differs from boot only in windows, and
         *  every head took its new declaration. */
        val candidate: Boolean = false,
    )

    private val heads = ConcurrentHashMap<String, Head>()
    private val lock = Any()

    // A null stamp: the first read re-reads the bytes once, which also covers an edit that landed
    // between Main's boot read and this constructor.
    private val published = AtomicReference(Published(stamp = null, seen = bootDigest, running = bootDigest))
    private val refreshing = AtomicBoolean(false)

    /** [ctx] with its catalog's windows following splice.toml from now on. */
    fun attach(ctx: ProviderBuild, legacyKnobsGovern: Boolean): ProviderBuild {
        heads[ctx.key] = Head(ctx.catalog, legacyKnobsGovern, ctx.providerCfg.isLocal)
        return ctx.copy(catalog = ctx.catalog.copy(liveWindows = LiveWindows { current(ctx.key) }))
    }

    override fun digest(): String {
        refresh()
        return published.get().running
    }

    override fun stale(): Boolean {
        refresh()
        return published.get().let { it.seen != it.running }
    }

    fun close() {
        scope.cancel()
    }

    private fun current(key: String): ModelCatalog? {
        refresh()
        return published.get().live[key]
    }

    private fun refresh() {
        val file = path ?: return
        val stamp = stampOf(file) ?: return
        if (stamp == published.get().stamp || !refreshing.compareAndSet(false, true)) return
        try {
            reread(file, stamp)
        } finally {
            refreshing.set(false)
        }
    }

    private fun stampOf(file: Path): Stamp? =
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-19 (V4-162): fail-open by design, as TopologyLoader.currentDigest: a splice.toml that cannot be stat'ed right now (an editor's rename-save in flight) keeps the windows in force, so null IS the whole reading.
        Cancellables.runCatchingCancellable { Stamp(Files.getLastModifiedTime(file), Files.size(file)) }
            .getOrNull()

    private fun reread(file: Path, stamp: Stamp) {
        val bytes = Cancellables.runCatchingCancellable { Files.readAllBytes(file) }
            .onFailure {
                log("[topology] $file unreadable (${SafeFailureText.render(it)}); context windows unchanged\n")
            }
            .getOrNull()
            ?: return remember(stamp)
        val digest = TopologyLoader.sha256Hex(bytes)
        if (digest == published.get().seen) return remember(stamp)
        val declared = Cancellables.runCatchingCancellable { TopologyLoader.parse(bytes.toString(Charsets.UTF_8)) }
            .onFailure {
                log(
                    "[topology] splice.toml does not parse (${SafeFailureText.render(it)}); context windows " +
                        "unchanged until it does — `splice doctor` names the line\n",
                )
            }
            .getOrNull()
        if (declared == null) {
            synchronized(lock) {
                val now = published.get()
                published.set(now.copy(stamp = stamp, seen = digest, pending = emptyMap(), candidate = false))
            }
            return
        }
        apply(stamp, digest, declared)
    }

    /** The same bytes, or bytes that could not be read: only the stamp moves, so the next read does
     *  not read them again. */
    private fun remember(stamp: Stamp) {
        synchronized(lock) { published.set(published.get().copy(stamp = stamp)) }
    }

    /** Publish [declared]'s windows: at once for a head whose windows moved, after its runtime's
     *  answer for a local-runtime head. */
    private fun apply(stamp: Stamp, digest: String, declared: Topology) {
        val fresh = heads.mapValues { (key, head) ->
            declaredCatalog(key, head, declared)?.let(head.boot::withWindowsOf)
        }
        val candidate = fresh.values.none { it == null } && declared.withoutWindows() == boot.withoutWindows()
        val moved = mutableListOf<String>()
        val asks = synchronized(lock) {
            val now = published.get()
            val live = now.live.toMutableMap()
            val pending = mutableMapOf<String, ModelCatalog>()
            fresh.forEach { (key, merged) -> merged?.let { place(key, it, live, pending)?.let(moved::add) } }
            val running = if (candidate && pending.isEmpty()) digest else now.running
            published.set(Published(stamp, digest, running, live, pending, candidate))
            pending.toMap()
        }
        moved.forEach(log::invoke)
        asks.forEach { (key, merged) -> ask(key, merged, digest, declared) }
    }

    /** Where [key]'s re-read [merged] catalog goes, under [lock]: nowhere when its windows did not
     *  move, to [pending] for a local-runtime head, else straight into [live] — the log line then. */
    private fun place(
        key: String,
        merged: ModelCatalog,
        live: MutableMap<String, ModelCatalog>,
        pending: MutableMap<String, ModelCatalog>,
    ): String? {
        val head = heads.getValue(key)
        val inForce = live[key] ?: head.boot
        return when {
            merged == inForce -> null
            head.local -> {
                pending[key] = merged
                null
            }
            else -> {
                live[key] = merged
                "[$key] context windows re-read from splice.toml: ${changes(inForce, merged)}\n"
            }
        }
    }

    private fun declaredCatalog(key: String, head: Head, declared: Topology): ModelCatalog? {
        val headCfg = declared.heads[key]
        val provider = headCfg?.let { declared.providers[it.provider] }
        if (headCfg == null || provider == null) {
            log("[$key] splice.toml no longer declares this head or its provider; context windows unchanged\n")
            return null
        }
        return Cancellables.runCatchingCancellable {
            catalogs.catalogFor(key, headCfg, provider, head.legacyKnobsGovern)
        }
            .onFailure { log("[$key] context windows unchanged: ${SafeFailureText.render(it)}\n") }
            .getOrNull()
    }

    /** Ask [key]'s local runtime about [merged] off the request path, then publish or refuse it —
     *  unless a newer read of the file superseded the question meanwhile. */
    private fun ask(key: String, merged: ModelCatalog, digest: String, declared: Topology) {
        val provider = declared.providers.getValue(declared.heads.getValue(key).provider)
        scope.launch {
            val refused = withContext(ProcessDispatchers().io()) { local.refusals(key, provider, merged) }
            val said = synchronized(lock) {
                val now = published.get()
                if (now.seen != digest || now.pending[key] != merged) return@launch
                val pending = now.pending - key
                if (refused.isNotEmpty()) {
                    published.set(now.copy(pending = pending, candidate = false))
                    "[$key] local runtime refuses the edited context window (${refused.joinToString("; ")}); " +
                        "the windows in force stay\n"
                } else {
                    val running = if (now.candidate && pending.isEmpty()) digest else now.running
                    val inForce = now.live[key] ?: heads.getValue(key).boot
                    published.set(now.copy(live = now.live + (key to merged), pending = pending, running = running))
                    "[$key] context windows re-read from splice.toml: ${changes(inForce, merged)}\n"
                }
            }
            log(said)
        }
    }

    /** "bonsai-27b 131072 -> 245760, default 131072 -> 245760": what an edit moved, for the log. */
    private fun changes(before: ModelCatalog, after: ModelCatalog): String {
        val rows = before.models.zip(after.models)
            .filter { (was, now) -> was.contextWindow != now.contextWindow }
            .map { (was, now) -> "${now.id} ${was.contextWindow} -> ${now.contextWindow}" }
        val rest = listOfNotNull(
            "extra_windows".takeIf { before.extraWindows != after.extraWindows },
            "window_rules".takeIf { before.windowRules != after.windowRules },
            "default ${before.defaultContextWindow} -> ${after.defaultContextWindow}"
                .takeIf { before.defaultContextWindow != after.defaultContextWindow },
        )
        return (rows + rest).joinToString(", ")
    }
}
