// NEW: Oct 12, 2026 — removing a command from Models (Marcos's rulings through the splice-lead): the read says what a removal
// would leave and who is on the command now, and the removal drops the head from splice.toml through the one topology
// writer (backup first), unlinks the wrapper no other head shares, empties its account order, and records it as retired
// so its requests, sessions and usage stay findable. Every store stays in place. A command a live session is using is
// refused, naming those sessions. The daemon keeps serving the head until it restarts.
package splice.app.head

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.accounts.order.ACCOUNT_ORDER_FILE
import splice.accounts.order.AccountOrderStore
import splice.app.control.ConsolePorts
import splice.app.control.ControlRuntime
import splice.app.control.ManagedHead
import splice.core.config.ConfigService
import splice.core.config.InstallPaths
import splice.core.config.StatePaths
import splice.core.retired.RetiredHead
import splice.core.retired.RetiredHeads
import splice.core.topology.Topology
import splice.core.topology.TopologyFinding
import splice.core.topology.TopologyWriteResult
import splice.core.topology.TopologyWriterSource
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.head.TurnsHead
import splice.head.TurnsHeadLookup
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.http.JsonReply
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isSymbolicLink

// why: the writer's own file-level refusals are filed under the file's name, and this phrase is its drift refusal.
private const val TOPOLOGY_FILE_KEY = "splice.toml"
private const val CHANGED_ON_DISK = "changed while this write was prepared"

/** Where the wrapper links live; null reads the install's own bin dir. */
internal fun interface WrapperBinSource {
    operator fun invoke(): Path?
}

internal class HeadRemoval(
    private val writer: TopologyWriterSource,
    private val wrapperBin: WrapperBinSource,
    private val statePaths: StatePaths,
    private val facts: HeadRemovalFacts,
    private val log: LogSink,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val retired = RetiredHeads(statePaths.stateDir)

    /** What stays, and who is on the command now. */
    fun read(key: String): JsonReply {
        val topology = stored() ?: return refusal(HttpStatusCode.ServiceUnavailable, "topology is not wired")
        if (key !in topology.heads) return refusal(HttpStatusCode.NotFound, "unknown head: $key")
        return JsonReply(
            HttpStatusCode.OK,
            buildJsonObject {
                put("requests", facts.requestsOf(key))
                put("sessions", facts.sessionsOf(key))
            }.toString(),
        )
    }

    /** The commands removed from splice.toml whose history stays: what Requests and Sessions list beside the live ones. */
    fun retiredList(): JsonReply = JsonReply(
        HttpStatusCode.OK,
        buildJsonObject {
            putJsonArray("retired") {
                retired.all().forEach { head ->
                    addJsonObject {
                        put("key", head.key)
                        put("command", head.command)
                    }
                }
            }
        }.toString(),
    )

    fun remove(key: String): JsonReply {
        val topology = stored()
        return when {
            topology == null -> refusal(HttpStatusCode.ServiceUnavailable, "topology is not readable")
            key !in topology.heads -> refusal(HttpStatusCode.NotFound, "unknown head: $key")
            facts.liveOn(key).isNotEmpty() -> inUse(key)
            else -> drop(key, topology)
        }
    }

    private fun inUse(key: String): JsonReply = JsonReply(
        HttpStatusCode.Conflict,
        buildJsonObject {
            put("error", "in use")
            put("live_sessions", facts.sessionList(facts.liveOn(key)))
        }.toString(),
    )

    private fun drop(key: String, topology: Topology): JsonReply {
        val command = commandOf(key, topology)
        // The record comes first: a head gone from splice.toml with no record would lose its history's door.
        retired.retire(RetiredHead(key, command, facts.transcriptRoot(key)?.toString()))
        val result = writer()?.write(without(topology, key))
        if (result is TopologyWriteResult.Refused) {
            retired.rejoin(key)
            return refused(key, result.findings)
        }
        if (result !is TopologyWriteResult.Written) {
            retired.rejoin(key)
            return refusal(HttpStatusCode.ServiceUnavailable, "topology is not wired")
        }
        unlink(command, topology.heads.filterKeys { it != key }.keys, topology)
        AccountOrderStore(statePaths.stateDir.resolve(ACCOUNT_ORDER_FILE)).set(key, emptyList())
        return JsonReply(HttpStatusCode.OK, "{}")
    }

    /** The writer's refusal, said and logged: a file that changed since splice read it, or a finding the remaining
     *  config trips, each logged with its key path so the cause is in the daemon log at every refusal. */
    private fun refused(key: String, findings: List<TopologyFinding>): JsonReply {
        findings.forEach { log("[models] removing $key refused: ${it.text()}") }
        val changed = findings.any { it.path == TOPOLOGY_FILE_KEY && it.message.contains(CHANGED_ON_DISK) }
        return JsonReply(
            HttpStatusCode.UnprocessableEntity,
            buildJsonObject {
                put("error", "not removed")
                put("reason", if (changed) "changed" else "config")
                putJsonArray("findings") { findings.forEach { add(JsonPrimitive(it.text())) } }
            }.toString(),
        )
    }

    private fun stored(): Topology? {
        val current = writer() ?: return null
        return Cancellables.runCatchingCancellable {
            json.decodeFromJsonElement(Topology.serializer(), current.current())
        }.fold(onSuccess = { it }, onFailure = { null })
    }

    private fun without(topology: Topology, key: String): Topology = topology.copy(
        heads = topology.heads - key,
        projects = topology.projects.mapValues { (_, project) -> project.copy(heads = project.heads - key) },
    )

    private fun commandOf(key: String, topology: Topology): String = topology.heads[key]?.claude?.command ?: key

    /** The wrapper link goes unless another head still answers to the same command; a link that will not go stays. */
    private fun unlink(command: String, others: Set<String>, topology: Topology) {
        if (others.any { commandOf(it, topology) == command }) return
        val bin = wrapperBin() ?: InstallPaths().binDir
        val link = bin.resolve(command)
        if (!isOwnLink(bin, link, command)) return
        if (Cancellables.runCatchingCancellable { Files.delete(link) }.isFailure) {
            log("[models] the $command wrapper link at $link could not be removed; it stays")
        }
    }

    private fun isOwnLink(bin: Path, link: Path, command: String): Boolean =
        command.isNotEmpty() && link.normalize().parent == bin.normalize() && link.isSymbolicLink()

    private fun refusal(status: HttpStatusCode, message: String): JsonReply =
        JsonReply(status, buildJsonObject { put("error", message) }.toString())
}

/** Assembles the removal from what the control server already holds, so the server names none of its parts. */
internal class HeadRemovalAssembly(
    private val config: ConfigService,
    private val ports: ConsolePorts,
    private val runtime: ControlRuntime,
    private val heads: Map<String, ManagedHead>,
    private val log: LogSink,
) {
    fun build(): HeadRemoval = HeadRemoval(
        TopologyWriterSource { ports.topology },
        WrapperBinSource { ports.wrapperBin },
        config.statePaths,
        HeadRemovalFacts(config.statePaths, heads, RegisteredSessions { runtime.sessions }),
        log,
    )
}

/** The turns routes' head lookup, extended with the commands removed from Models: their trace files stay in place, so a
 *  removed command's requests open and its prompts and answers search, as they did while it ran. */
internal class RetiredTurnsHeads(
    private val retired: RetiredHeads,
    private val running: TurnsHeadLookup,
) : TurnsHeadLookup {
    override fun byName(name: String): List<TurnsHead> = running.byName(name).ifEmpty {
        retired.all().filter { it.key == name || it.command == name }.map { head ->
            TurnsHead(
                head.key,
                object : HeadCompactSource {
                    override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
                },
            )
        }
    }
}
