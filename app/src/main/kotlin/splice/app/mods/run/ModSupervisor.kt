package splice.app.mods.run

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import splice.app.mods.ModDiagnostics
import splice.app.mods.ModLoadFacts
import splice.app.mods.ModRuntime
import splice.app.mods.ModRuntimeState
import splice.app.mods.handover.ModDelivery
import splice.app.mods.handover.ModHandover
import splice.app.mods.handover.ModHandoverEvents
import splice.app.mods.install.HashVerdict
import splice.app.mods.install.InstalledMod
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.upstream.Waiter
import java.nio.file.Path

private const val STUCK_MS = 30_000L // why: expose a backed-up live pipe without killing a slow mod

internal class ModSupervisor(
    scope: CoroutineScope,
    prior: Job?,
    private val mod: InstalledMod,
    private val setup: ModProcessSetup,
    private val hub: ModEventHub,
    val output: ModOutput,
    private val waiter: Waiter,
) : ModProcessObserver {
    private val shutdown = CompletableDeferred<String>()
    private val crashes = ModCrashes(output.clock)
    private var intake = newPipe()
    val settled = CompletableDeferred<Unit>()
    val job = scope.launch(start = CoroutineStart.LAZY) {
        try {
            prior?.join()
            if (!shutdown.isCompleted) runMod()
        } finally {
            settled.complete(Unit)
        }
    }

    @Volatile private var current = ModRuntime(ModRuntimeState.STARTING)

    @Volatile private var process: ModProcess? = null

    @Volatile private var lastError: String? = null

    @Volatile private var bunPath: Path? = null

    fun status(): ModRuntime {
        val now = current
        val live = process
        val byEvent = output.drops.byEvent()
        val transport = output.drops.transportCount()
        val dropped = byEvent.values.sum()
        val stuck = live?.pipe?.let { pipe ->
            val began = pipe.writingSince
            pipe.pending && began > 0 && output.clock() - began >= STUCK_MS
        } == true
        return now.copy(
            state = if (now.state == ModRuntimeState.RUNNING && stuck) ModRuntimeState.STUCK else now.state,
            why = if (stuck) "not reading its events (stuck 30 s, $dropped dropped)" else now.why,
            dropped = dropped,
            diagnostics = ModDiagnostics(
                byEvent,
                transport,
                output.drops.logCount(),
                crashes.restartsInWindow(),
                live?.lastError ?: lastError,
                bunPath?.toString(),
            ),
        )
    }

    fun arm() {
        hub.bind(ModListener(mod.name, intake), ModHandoverEvents.names)
    }

    @Synchronized
    fun receive(delivery: ModDelivery) {
        if (delivery.only != null && delivery.only != mod.name) return
        if (delivery.offAll || mod.name in delivery.offNames) return
        intake.offer(delivery.copy(only = mod.name, received = true))
    }

    @Synchronized
    fun stop(reason: String, forwarding: ModHandover? = null) {
        shutdown.complete(reason)
        hub.unbind(ModListener(mod.name, intake))
        if (forwarding != null) intake.handOver(forwarding)
        intake.finish(reason)
        if (process == null) intake.close()
    }

    @Synchronized
    override fun ready(process: ModProcess, loaded: ModLoad.Ready) {
        if (shutdown.isCompleted) return
        current = ModRuntime(
            ModRuntimeState.RUNNING,
            pid = process.pid,
            loaded = ModLoadFacts(loaded.notice, loaded.events, loaded.refused, loaded.declared, loaded.version),
        )
        output.drops.register(loaded.events)
        process.pipe.loaded(loaded.events)
        hub.bind(ModListener(mod.name, process.pipe), loaded.events)
        settled.complete(Unit)
    }

    @Synchronized
    override fun disconnected(process: ModProcess) {
        hub.unbind(ModListener(mod.name, process.pipe))
    }

    private suspend fun runMod() {
        Cancellables.runCatchingBestEffort {
            while (!shutdown.isCompleted && attempt()) {
                // Next attempt is paced by the crash policy.
            }
        }.onFailure {
            fail("could not run Bun: ${SafeFailureText.render(it)}")
        }
        settled.complete(Unit)
    }

    private suspend fun attempt(): Boolean {
        val executable = executable() ?: return false
        setup.records.previous(mod.name)?.let { setup.records.awaitPrevious(it) }
        bunPath = executable
        val child = launch(executable) ?: return false
        if (current.state == ModRuntimeState.RESTARTING) crashes.restarted()
        process = child
        val exit = child.run(shutdown, this)
        lastError = child.lastError ?: lastError
        process = null
        return when (exit) {
            is ModExit.Failed -> fail("could not load: ${exit.why}")
            ModExit.Crashed -> restart(child.exitReason())
            ModExit.Stopped -> false
        }
    }

    @Synchronized
    private fun rearm() {
        if (shutdown.isCompleted) return
        intake = newPipe()
        arm()
    }

    private fun newPipe() = ModPipe(output.drops, output.log, output.clock, hub.deliveryLoss)

    private fun launch(executable: Path): ModProcess? =
        if (shutdown.isCompleted) null else setup.launch(mod, executable, output, intake)

    private fun executable(): Path? {
        val verdict = mod.verify()
        if (verdict is HashVerdict.Refused) {
            fail(verdict.why)
            return null
        }
        return setup.bun.forRun(mod.bunPath).also {
            if (it == null) fail(setup.bun.missing(mod.name))
        }
    }

    private suspend fun restart(why: String): Boolean {
        val delayMs = crashes.record() ?: return fail("exited 4 times in 10 minutes (last: $why)")
        val message = "exited unexpectedly ($why); restarting in ${delayMs / 1000} s. Events sent to it may be lost."
        current = current.copy(state = ModRuntimeState.RESTARTING, why = message, pid = null)
        output.log.write(message)
        settled.complete(Unit)
        return coroutineScope {
            val pause = async { waiter.wait(delayMs) }
            try {
                select {
                    shutdown.onAwait { false }
                    pause.onAwait { true }
                }
            } finally {
                pause.cancel()
            }
        }.also { if (it) rearm() }
    }

    private fun fail(why: String): Boolean {
        val message = "Mod ${mod.name}: $why"
        synchronized(this) {
            hub.unbind(ModListener(mod.name, intake))
            intake.close()
            current = current.copy(state = ModRuntimeState.FAILED, why = message, pid = null)
        }
        output.log.write(message)
        return false
    }
}
