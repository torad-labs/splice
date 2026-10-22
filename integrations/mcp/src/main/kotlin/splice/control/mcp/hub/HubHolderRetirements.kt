package splice.control.mcp.hub

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.control.mcp.JsonRpcCodec
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

private const val RETIREMENT_DIR = "replacement-outcomes"
private const val RETIREMENT_STATE = "state"
private const val RETIRING = "retiring"
private const val RETIRED = "retired"
private const val ROLLED_BACK = "rolled_back"
private const val OUTCOME_UNKNOWN = "unknown"
private const val PERSISTING = "persisting"

/** The four generation-specific identities which make one retirement retryable across a daemon restart. */
internal data class HubReplacement(
    val oldSocket: String,
    val oldGeneration: String,
    val candidateSocket: String,
    val candidateTuple: String,
    val candidateGeneration: String,
) {
    fun id(): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(
            listOf(oldSocket, oldGeneration, candidateSocket, candidateTuple, candidateGeneration)
                .joinToString("\n")
                .toByteArray(StandardCharsets.UTF_8),
        ),
    )
}

internal enum class HubRetirementState { RETIRING, RETIRED, ROLLED_BACK, UNKNOWN }

private sealed class HubRetirementRead {
    data class Present(val body: JsonObject) : HubRetirementRead()

    object Missing : HubRetirementRead()

    object Unreadable : HubRetirementRead()
}

/** Durable operation outcomes prevent a lost response from replaying an ambiguous process action. */
internal class HubHolderRetirements(dir: Path) {
    private val outcomes = dir.resolve(RETIREMENT_DIR)
    private val active = ConcurrentHashMap<String, String>()

    /** Claims a new operation before its process action. A concurrent persister asks its caller to retry. */
    fun claim(replacement: HubReplacement, wanted: HubRetirementState): HubRetirementState? {
        val id = replacement.id()
        state(replacement)?.let { return it }
        if (active.putIfAbsent(id, PERSISTING) != null) return null
        return if (write(replacement, wanted)) {
            active[id] = stateName(wanted)
            wanted
        } else {
            active.remove(id, PERSISTING)
            null
        }
    }

    /** Records a settled outcome after the exact owner record confirms it. */
    fun complete(replacement: HubReplacement, state: HubRetirementState): Boolean =
        write(replacement, state).also { written ->
            if (written) active[replacement.id()] = stateName(state)
        }

    fun state(replacement: HubReplacement): HubRetirementState? {
        val id = replacement.id()
        active[id]?.let { return stateOf(it) }
        val state = when (val persisted = read(replacement)) {
            HubRetirementRead.Missing -> null
            HubRetirementRead.Unreadable -> HubRetirementState.UNKNOWN
            is HubRetirementRead.Present ->
                persisted.body
                    .takeIf { matches(it, replacement) }
                    ?.let { JsonScalars.str(it, RETIREMENT_STATE) }
                    ?.let(::stateOf)
                    ?: HubRetirementState.UNKNOWN
        }
        state?.takeUnless { it == HubRetirementState.UNKNOWN }?.let { active.putIfAbsent(id, stateName(it)) }
        return state
    }

    private fun write(replacement: HubReplacement, state: HubRetirementState): Boolean {
        if (!writable(replacement)) return false
        return try {
            Files.createDirectories(outcomes)
            SecureFile.writeAtomic0600(file(replacement), JsonWire.string(body(replacement, state)))
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun writable(replacement: HubReplacement): Boolean = when (val existing = read(replacement)) {
        HubRetirementRead.Missing -> true
        HubRetirementRead.Unreadable -> false
        is HubRetirementRead.Present -> matches(existing.body, replacement)
    }

    private fun file(replacement: HubReplacement): Path = outcomes.resolve("${replacement.id()}.json")

    private fun read(replacement: HubReplacement): HubRetirementRead = try {
        val path = file(replacement)
        when {
            Files.notExists(path) -> HubRetirementRead.Missing
            !Files.isRegularFile(path) -> HubRetirementRead.Unreadable
            else -> JsonRpcCodec().parse(Files.readString(path))
                ?.let(HubRetirementRead::Present)
                ?: HubRetirementRead.Unreadable
        }
    } catch (_: IOException) {
        HubRetirementRead.Unreadable
    }

    private fun matches(body: JsonObject, replacement: HubReplacement): Boolean =
        JsonScalars.str(body, HubFields.OLD_SOCKET) == replacement.oldSocket &&
            JsonScalars.str(body, HubFields.OLD_GENERATION) == replacement.oldGeneration &&
            JsonScalars.str(body, HubFields.CANDIDATE_SOCKET) == replacement.candidateSocket &&
            JsonScalars.str(body, HubFields.CANDIDATE_TUPLE) == replacement.candidateTuple &&
            JsonScalars.str(body, HubFields.CANDIDATE_GENERATION) == replacement.candidateGeneration

    private fun body(replacement: HubReplacement, state: HubRetirementState) = buildJsonObject {
        put(HubFields.OLD_SOCKET, replacement.oldSocket)
        put(HubFields.OLD_GENERATION, replacement.oldGeneration)
        put(HubFields.CANDIDATE_SOCKET, replacement.candidateSocket)
        put(HubFields.CANDIDATE_TUPLE, replacement.candidateTuple)
        put(HubFields.CANDIDATE_GENERATION, replacement.candidateGeneration)
        put(
            RETIREMENT_STATE,
            when (state) {
                HubRetirementState.RETIRING -> RETIRING
                HubRetirementState.RETIRED -> RETIRED
                HubRetirementState.ROLLED_BACK -> ROLLED_BACK
                HubRetirementState.UNKNOWN -> OUTCOME_UNKNOWN
            },
        )
    }

    private fun stateName(state: HubRetirementState): String = when (state) {
        HubRetirementState.RETIRING -> RETIRING
        HubRetirementState.RETIRED -> RETIRED
        HubRetirementState.ROLLED_BACK -> ROLLED_BACK
        HubRetirementState.UNKNOWN -> OUTCOME_UNKNOWN
    }

    private fun stateOf(value: String): HubRetirementState? = when (value) {
        RETIRING -> HubRetirementState.RETIRING
        RETIRED -> HubRetirementState.RETIRED
        ROLLED_BACK -> HubRetirementState.ROLLED_BACK
        OUTCOME_UNKNOWN -> HubRetirementState.UNKNOWN
        else -> null
    }
}
