package splice.control.mcp.hosted

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.client.mcp.McpServerSpec
import splice.control.mcp.HostedServer
import splice.control.mcp.HostedServerRequests
import splice.control.mcp.JsonRpcCodec
import splice.control.mcp.McpChildHistory
import splice.control.mcp.McpChildLines
import splice.control.mcp.McpChildWriter
import splice.control.mcp.McpHostConfig
import splice.control.mcp.McpResult
import splice.control.mcp.McpStderr
import splice.control.mcp.Pending
import splice.core.util.LogSink
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.time.Duration

internal class HostedServerSpawn(
    private val name: String,
    private val history: McpChildHistory,
    private val ready: Readiness,
    private val launch: ChildLaunch,
    private val handshake: HandshakeResultHandler,
    private val tearDown: HostedSpawnTearDown,
) {
    internal fun interface HandshakePublication {
        suspend fun publish(
            process: Process,
            result: JsonObject,
            budget: Duration,
        ): HostedServerLifecycle.Initialization?
    }

    internal sealed class Result {
        data class Served(val initialization: HostedServerLifecycle.Initialization) : Result()
        data class Refused(val reason: String, val reply: McpResult.Refused) : Result()
    }

    internal fun interface Readiness {
        fun ready(): Boolean
    }

    internal fun interface ChildLaunch {
        fun launch(): McpResult<Process>
    }

    internal fun interface HandshakeResultHandler {
        suspend fun handshake(process: Process): McpResult<HostedServerLifecycle.Initialization>
    }

    internal fun interface HostedSpawnTearDown {
        fun tearDown(reason: String)
    }

    suspend fun start(): McpResult<HostedServerLifecycle.Initialization> {
        if (!ready.ready()) {
            return McpResult.Refused("hosted MCP server '$name' is stopping after a transport failure")
        }
        return history.admit() ?: afterLaunch()
    }

    private suspend fun afterLaunch(): McpResult<HostedServerLifecycle.Initialization> {
        val child = when (val launched = launch.launch()) {
            is McpResult.Refused -> return launched
            is McpResult.Served -> launched.value
        }
        return try {
            handshake.handshake(child)
        } catch (error: CancellationException) {
            tearDown.tearDown("cancelled during the handshake")
            throw error
        }
    }

    /** Executes the bounded initialize exchange without adding branch pressure to process lifecycle ownership. */
    internal class Handshake(
        private val name: String,
        private val config: McpHostConfig,
        private val codec: JsonRpcCodec,
        private val ids: java.util.concurrent.atomic.AtomicLong,
        private val pending: java.util.concurrent.ConcurrentHashMap<Long, Pending>,
        private val writer: McpChildWriter,
        private val publication: HandshakePublication,
    ) {
        suspend fun run(process: Process): Result {
            val deadline = HostedClientCalls.Deadline(config.clock, config.initializeTimeout())
            val id = ids.getAndIncrement()
            val slot = Pending("host", JsonPrimitive(id))
            pending[id] = slot
            val writeBudget = deadline.remaining()
            val delivered = writeBudget > Duration.ZERO &&
                writer.request(codec.initializeRequest(id), id, process, writeBudget)
            val answer = if (delivered) await(slot, deadline.remaining()) else null
            // A write refused with budget left met a child that is already gone: a failed start, not one given up on.
            if (answer == null) return unavailable(id, delivered, delivered || deadline.remaining() <= Duration.ZERO)
            val result = answer["result"] as? JsonObject
            val initialization = result?.let { publication.publish(process, it, deadline.remaining()) }
            return initialization?.let(Result::Served)
                ?: Result.Refused(
                    "handshake failed",
                    McpResult.Refused("'$name' rejected the MCP handshake (server detail withheld)"),
                )
        }

        private suspend fun await(slot: Pending, budget: Duration): JsonObject? =
            if (budget > Duration.ZERO) withTimeoutOrNull(budget) { slot.answer.await() } else null

        private fun unavailable(id: Long, delivered: Boolean, timedOut: Boolean): Result.Refused {
            pending.remove(id)
            val reason = if (delivered) "no initialize answer" else "could not write initialize"
            return Result.Refused(
                reason,
                McpResult.Refused("'$name' did not complete the MCP handshake", timedOut = timedOut),
            )
        }
    }

    internal class Error {
        @Volatile var value: String? = null
    }

    internal class ChildPump(
        private val codec: JsonRpcCodec,
        private val dispatcher: HostedServerRequests.Dispatcher,
        private val failures: HostedServerLifecycle.TransportFailure,
    ) {
        fun pump(process: Process) {
            val reader = McpChildLines(process.inputStream.reader())
            val reason = try {
                generateSequence { reader.next() }
                    .filter { it.isNotBlank() }
                    .mapNotNull(codec::parse)
                    .forEach { dispatcher.dispatch(process, it) }
                "stdout closed"
            } catch (error: IOException) {
                "read failed: ${error.message}"
            }
            failures.failed(process, reason)
        }
    }

    internal class ChildLauncher(
        private val spec: McpServerSpec,
        private val runtime: HostedServerLifecycle.Runtime,
        private val stderr: McpStderr,
        private val history: McpChildHistory,
        private val errors: Error,
        private val adoption: HostedServerLifecycle.LaunchAdoption,
        private val pump: ChildPump,
    ) : ChildLaunch {
        override fun launch(): McpResult<Process> {
            var spawnFailure: String? = null
            val child = try {
                runtime.launches.launch(spec, runtime.launcher, adoption)
            } catch (failure: IOException) {
                spawnFailure = failure.message
                null
            }
            if (child == null) {
                val reason = spawnFailure?.let { "cannot start '${spec.name}': $it" }
                    ?: "MCP host is stopping"
                spawnFailure?.let { errors.value = "spawn failed: $it" }
                return McpResult.Refused(reason)
            }
            stderr.watch(spec.name, child)
            history.started()
            Executors.defaultThreadFactory().newThread { pump.pump(child) }.apply {
                name = "mcp-host-${spec.name}"
                isDaemon = true
            }.start()
            return McpResult.Served(child)
        }
    }

    internal class Completion(
        private val name: String,
        private val log: LogSink,
        private val errors: Error,
        private val tearDown: HostedSpawnTearDown,
    ) {
        fun complete(
            process: Process,
            outcome: Result,
        ): McpResult<HostedServerLifecycle.Initialization> = when (outcome) {
            is Result.Served -> {
                errors.value = null
                log("[mcp-host] $name: hosted as pid ${process.pid()}\n")
                McpResult.Served(outcome.initialization)
            }
            is Result.Refused -> {
                tearDown.tearDown(outcome.reason)
                outcome.reply
            }
        }
    }

    internal class Starter(
        private val host: HostedServer,
        private val spawner: HostedServerSpawn,
    ) {
        suspend fun ensureStarted(): McpResult<JsonObject> = when (val started = ensureInitialized()) {
            is McpResult.Refused -> started
            is McpResult.Served -> McpResult.Served(started.value.result)
        }

        suspend fun ensureInitialized(): McpResult<HostedServerLifecycle.Initialization> =
            started?.let { McpResult.Served(it) } ?: host.spawnLock.withLock {
                if (host.closed) {
                    McpResult.Refused("hosted MCP server '${host.spec.name}' was closed")
                } else {
                    started?.let { McpResult.Served(it) } ?: spawner.start()
                }
            }

        private val started: HostedServerLifecycle.Initialization?
            get() = synchronized(host.stateLock) {
                val result = host.initResult ?: return@synchronized null
                val owned = host.generation ?: return@synchronized null
                if (host.process?.isAlive != true) null else HostedServerLifecycle.Initialization(result, owned)
            }
    }
}
