package splice.control.mcp

import kotlinx.serialization.json.JsonObject
import splice.client.mcp.McpServerSpec
import splice.core.util.LogSafe
import splice.core.util.LogSink

internal class HostedServerLifecycle(private val host: HostedServer) {
    /** Adopts a child while the launch barrier still prevents daemon shutdown from passing it. */
    internal fun interface LaunchAdoption {
        fun adopt(process: Process, generation: McpProcessGeneration): Boolean
    }

    /** Starts and adopts one child while its registry still owns the right to do so. */
    internal fun interface LaunchGate {
        fun launch(spec: McpServerSpec, launcher: McpProcessLauncher, adoption: LaunchAdoption): Process?
    }

    /** The process services a hosted child needs throughout one owned lifetime. */
    internal data class Runtime(
        val config: McpHostConfig,
        val launcher: McpProcessLauncher,
        val reaped: ChildReaped,
        val launches: LaunchGate,
    )

    internal fun interface ReadyWriteTarget {
        fun target(): McpWriteTarget?
    }

    /** Keeps the replacement fence separate from the host's normal request lifecycle. */
    internal class Replacement(
        private val writer: McpChildWriter,
        private val target: ReadyWriteTarget,
    ) {
        fun fence(): McpReplacementFence? {
            val selected = target.target() ?: return null
            return if (writer.seal(selected)) McpReplacementFence(writer, selected) else null
        }
    }

    /** The initialize result and the exact child generation that produced it. */
    internal data class Initialization(
        val result: JsonObject,
        val generation: McpProcessGeneration,
    )

    /** Rechecks retained scope ownership before the server may reuse a child slot after a failed transport. */
    internal class GenerationRetention(
        private val stops: McpProcessStop,
        private val reaped: ChildReaped,
    ) {
        fun settled(
            process: Process,
            generation: McpProcessGeneration,
            stopping: List<McpProcessGeneration> = emptyList(),
        ): Boolean {
            stops.stopGenerations(stopping)
            return reaped(process) && !stops.retains(generation)
        }
    }

    /** One retired generation retains its child wire charges until its exact scope proves empty. */
    internal data class RetiredChild(
        val target: McpWriteTarget,
        val generation: McpProcessGeneration,
    )

    internal data class StoppingChild(
        val process: Process,
        val generation: McpProcessGeneration,
        val retired: McpWriteTarget? = null,
    )

    internal fun interface FailureRetirement {
        fun retire(child: Process, reason: String): RetiredChild?
    }

    internal fun interface FailureRelease {
        fun release(child: Process, generation: McpProcessGeneration): Boolean
    }

    internal fun interface PendingFailure {
        fun fail(message: String)
    }

    internal fun interface NotificationEnqueue {
        fun enqueue()
    }

    internal fun interface FailureDescription {
        fun describe(): String
    }

    internal data class TransportRuntime(
        val name: String,
        val send: McpChildWriter,
        val stops: McpProcessStop,
        val reaped: ChildReaped,
        val history: McpChildHistory,
        val log: LogSink,
    )

    internal data class TransportActions(
        val retire: FailureRetirement,
        val release: FailureRelease,
        val failPending: PendingFailure,
        val description: FailureDescription,
    )

    /** Owns bounded failure retirement without making the server's request entry point branch on process state. */
    internal class TransportFailure(
        private val runtime: TransportRuntime,
        private val actions: TransportActions,
    ) {
        fun failed(child: Process, reason: String) {
            val retired = actions.retire.retire(child, reason) ?: return
            runtime.send.retire(retired.target)
            actions.failPending.fail("hosted MCP transport failed; operation outcome unknown, not replayed")
            runtime.stops.stopGenerations(listOf(retired.generation))
            val reaped = runtime.reaped(child)
            val exactExit = reaped && !runtime.stops.retains(retired.generation)
            if (exactExit && actions.release.release(child, retired.generation)) {
                runtime.send.exited(retired.target)
                runtime.history.exited()
            }
            val exitCode = if (reaped) child.exitValue().toString() else "unknown"
            runtime.log(
                "[mcp-host] ${LogSafe.str(runtime.name)}: pid ${child.pid()} exited ${LogSafe.str(exitCode)} " +
                    "(${LogSafe.str(actions.description.describe())})\n",
            )
        }
    }

    /** Starts a child only after retained transport ownership is gone, then keeps cancellation teardown exact. */
    internal fun interface RetiredRelease {
        fun release(stopping: StoppingChild): McpWriteTarget?
    }

    /** Releases a retained generation only once its exact process and containment have exited. */
    internal class StoppedRelease(
        private val retention: GenerationRetention,
        private val send: McpChildWriter,
        private val history: McpChildHistory,
        private val release: RetiredRelease,
    ) {
        fun release(stopping: StoppingChild, generations: List<McpProcessGeneration> = emptyList()) {
            if (!retention.settled(stopping.process, stopping.generation, generations)) return
            release.release(stopping)?.let {
                send.exited(it)
                history.exited()
            }
        }
    }

    fun close(reason: String, wait: Boolean): McpProcessGeneration? {
        synchronized(host.stateLock) { host.closed = true }
        host.serverRequests.close()
        val stopped = tearDown(reason, wait)
        host.send.close()
        return stopped
    }

    val retirementSettled: Boolean
        get() {
            val stopping = synchronized(host.stateLock) {
                val child = host.process
                val owned = host.generation
                if (child == null || owned == null) null else StoppingChild(child, owned, host.retiredWriter)
            }
            stopping?.let { host.stoppedRelease.release(it, listOf(it.generation)) }
            return synchronized(host.stateLock) { host.process == null && host.generation == null }
        }

    fun tearDown(reason: String, wait: Boolean): McpProcessGeneration? {
        val stopping = synchronized(host.stateLock) {
            val child = host.process
            val owned = host.generation
                ?: child?.let(host.stops::capture)?.also { host.generation = it }
            val selected = host.writer
            host.writer = null
            if (selected != null) host.retiredWriter = selected
            host.initResult = null
            host.tools.drop()
            child?.let { running ->
                owned?.let { StoppingChild(running, it, selected) }
            }
        }
        stopping?.let {
            it.retired?.let(host.send::retire)
            failPending("hosted MCP server '${host.spec.name}' closed: $reason")
            host.log(
                "[mcp-host] ${LogSafe.str(host.spec.name)}: closing pid ${LogSafe.str(it.process.pid().toString())} " +
                    "(${LogSafe.str(reason)})\n",
            )
            if (wait) host.stoppedRelease.release(it, listOf(it.generation))
        }
        return stopping?.generation
    }

    fun adopt(child: Process, owned: McpProcessGeneration): Boolean = synchronized(host.stateLock) {
        if (host.closed) {
            false
        } else {
            host.process = child
            host.writer = McpWriteTarget(child, child.outputStream.bufferedWriter())
            host.generation = owned
            true
        }
    }

    suspend fun publish(process: Process, result: JsonObject, budget: kotlin.time.Duration): Initialization? {
        val initialized = budget > kotlin.time.Duration.ZERO && host.send.notify(
            host.codec.notification("notifications/initialized"),
            process,
            budget,
        )
        return synchronized(host.stateLock) {
            host.generation
                ?.takeIf { initialized && !host.closed && host.process === process && process.isAlive }
                ?.let { owned ->
                    host.initResult = result
                    Initialization(result, owned)
                }
        }
    }

    fun failPending(message: String, sessionId: String? = null) {
        host.pending.entries
            .filter { sessionId == null || it.value.sessionId == sessionId }
            .map { it.key }
            .forEach { id ->
                host.pending.remove(id)?.let { slot ->
                    host.reservations.remove(HostedClientCalls.RequestId(slot.sessionId, slot.clientId), slot)
                    slot.fail(host.codec, message)
                }
            }
    }

    fun enqueueIfOwned(child: Process, enqueue: NotificationEnqueue) = synchronized(host.stateLock) {
        if (host.process !== child) return@synchronized
        if (host.writer?.child !== child) return@synchronized
        if (host.generation?.process === child) enqueue.enqueue()
    }

    fun owns(initialization: Initialization): Boolean = synchronized(host.stateLock) {
        host.process === initialization.generation.process &&
            host.generation === initialization.generation &&
            host.writer?.child === host.process &&
            host.initResult === initialization.result
    }
}
