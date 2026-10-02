// NEW: bounded on-demand hosts own sticky session placements and acknowledged engine retirement.
package splice.codemode.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import splice.codemode.CellChannel
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.upstream.Ticker
import splice.upstream.failure.CodeModeWorkerLostException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock as locked

// why: check idle expiry four times per retention interval so reclamation has bounded timer lag.
private const val IDLE_SWEEP_DIVISOR = 4

// why: short injected intervals must not turn the housekeeping lane into a busy loop.
private const val MIN_IDLE_SWEEP_MS = 10L

// why: production idle expiry must be observed within a quarter of the default minute-long lifetime.
private const val MAX_IDLE_SWEEP_MS = 15_000L

/** Bookkeeping never waits for a guest. Each session opens independently on its assigned host. */
internal class CodeModeHostPool(
    private val admission: CodeModePoolAdmission,
    private val scope: CoroutineScope,
    start: CodeModeHostStart,
    private val now: ElapsedClock,
    private val ticker: Ticker,
    times: CodeModePoolTimes,
    log: LogSink,
) : AutoCloseable {
    private val idleTimeoutMs = times.idleMs
    private val lock = ReentrantLock()
    private val sequence = AtomicLong()
    private val boots = CodeModeHostBoots(scope, start, lock)
    private val metrics = CodeModeHostMetrics(times.controlMs)
    private val drains = CodeModeHostDrains(scope, lock, admission, log)
    private val retirement = CodeModeSessionRetirement(scope, lock, metrics, drains, times.controlMs)
    private val hosts = mutableListOf<CodeModePoolHost>()
    private val sessions = mutableMapOf<String, CodeModePoolSession>()
    private var closed = false

    init {
        scope.launch {
            val interval = (idleTimeoutMs / IDLE_SWEEP_DIVISOR).coerceIn(MIN_IDLE_SWEEP_MS, MAX_IDLE_SWEEP_MS)
            while (isActive && ticker.awaitTick(interval)) {
                lock.locked {
                    sessions.values.filter { it.users == 0 && now() - it.lastUse >= idleTimeoutMs }
                        .toList().forEach(::retire)
                }
            }
        }
    }

    suspend fun open(key: String): CodeModePoolLease {
        val session = acquire(key)
        var opened = false
        try {
            return session.gate.withLock {
                val host = lock.locked { boots.open(session.host) }.await()
                ensureActive(session)
                if (!session.initialized) drains.admit(session, host, metrics)
                val pipe = host.cell(sequence.incrementAndGet(), session.id)
                lock.locked {
                    ensureActive(session, pipe)
                    session.pipes.add(pipe)
                }
                opened = true
                CodeModePoolLease(session, pipe)
            }
        } finally {
            if (!opened) release(session, null)
        }
    }

    private fun ensureActive(session: CodeModePoolSession, pipe: CellChannel? = null) {
        lock.locked {
            if (closed || session.closing) {
                pipe?.close()
                throw CodeModeWorkerLostException()
            }
        }
    }

    private suspend fun acquire(key: String): CodeModePoolSession {
        while (true) {
            when (val placement = lock.locked { place(key) }) {
                is CodeModePlacement.Acquired -> return placement.session
                is CodeModePlacement.Reclaim -> placement.session.retired.await()
            }
        }
    }

    private fun place(key: String): CodeModePlacement {
        check(!closed) { "Code-mode host pool is closed" }
        sessions[key]?.let {
            if (it.host.draining) throw drains.capacity()
            it.users++
            return CodeModePlacement.Acquired(it)
        }
        val host = admission.select(hosts)
        if (host == null) {
            val victim = sessions.values.filter { it.users == 0 && !it.host.draining }.minByOrNull { it.lastUse }
                ?: hosts.filterNot { it.draining }.flatMap { it.sessions }.firstOrNull { it.closing }
                ?: throw if (hosts.any { it.draining }) drains.capacity() else admission.capacity()
            retire(victim)
            return CodeModePlacement.Reclaim(victim)
        }
        val created = CodeModePoolSession(key, sequence.incrementAndGet(), host).also {
            it.users = 1
            it.lastUse = now()
        }
        host.sessions.add(created)
        sessions[key] = created
        return CodeModePlacement.Acquired(created)
    }

    fun release(lease: CodeModePoolLease) = release(lease.session, lease.pipe)

    private fun release(session: CodeModePoolSession, pipe: CellChannel?) {
        lock.locked {
            if (pipe != null && !session.pipes.remove(pipe)) return
            session.users--
            session.used(now())
        }
        drains.closeWhenDrained(session.host)
    }

    fun closeSession(key: String) {
        lock.locked { sessions[key]?.let(::retire) }
    }

    // Closing sessions still count against the host cap until the engine-close reply arrives.
    private fun retire(session: CodeModePoolSession) {
        if (session.closing) return
        session.closing = true
        sessions.remove(session.key, session)
        session.pipes.toList().forEach(CellChannel::close)
        retirement.close(session)
    }

    suspend fun metric(key: String): Int = metrics.total(lock.locked { hosts.mapNotNull(CodeModePoolHost::boot) }, key)

    override fun close() {
        lock.locked {
            closed = true
            sessions.clear()
        }
    }
}
