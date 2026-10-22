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
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val PINS_DIR = "drain-pins"
private const val PIN_UNTIL = "until"
private const val PIN_PENDING = "pending"
private const val PIN_ACTIVE = "active"
private const val PIN_RELEASED = "released"

// why: the front needs a durable write interval after acquiring ownership and before its state checkpoint lands.
private const val PIN_INTENT_MS = 5_000L

/** The front's exact target and old generation before it writes a paused drain checkpoint. */
internal data class HubDrainPinRequest(
    val server: String,
    val launch: String,
    val oldSocket: String,
    val oldGeneration: String,
)

/** A durable owner claim that excludes its old generation from destructive eviction. */
internal data class HubDrainPin(
    val request: HubDrainPinRequest,
    val claim: String,
    val state: String,
    val until: Long,
) {
    /** A released claim is confirmed only for its still-durable front checkpoint's terminal replay. */
    fun confirmed(claims: Set<HubDrainClaim>): Boolean = when (state) {
        PIN_ACTIVE -> true
        PIN_RELEASED -> HubDrainClaim(request, claim) in claims
        else -> false
    }
}

/** A durable front drain checkpoint that keeps one pending owner claim beyond its initial write lease. */
internal data class HubDrainClaim(val request: HubDrainPinRequest, val claim: String)

private sealed class PinRead {
    object Missing : PinRead()
    object Unreadable : PinRead()
    data class Present(val pin: HubDrainPin) : PinRead()
}

private data class DecodedPin(
    val request: HubDrainPinRequest?,
    val claim: String?,
    val state: String?,
    val until: Long?,
) {
    fun pin(): HubDrainPin? = when {
        request == null -> null
        claim == null -> null
        state == null -> null
        !knownState(state) -> null
        until == null -> null
        else -> HubDrainPin(request, claim, state, until)
    }

    private fun knownState(state: String): Boolean = when (state) {
        PIN_PENDING, PIN_ACTIVE, PIN_RELEASED -> true
        else -> false
    }
}

/** Durable drain claims bridge the front's checkpoint interval without trusting a stale front snapshot. */
internal class HubDrainPins(dir: Path) {
    private val pins = dir.resolve(PINS_DIR)
    private val snapshot = HubDrainPinSnapshot(pins)
    private val lock = ReentrantLock()

    fun acquire(request: HubDrainPinRequest, claims: Set<HubDrainClaim>): HubDrainPin? = lock.withLock {
        val now = System.currentTimeMillis()
        when (val read = snapshot.read(file(request))) {
            PinRead.Unreadable -> null
            PinRead.Missing -> HubDrainPin(
                request,
                UUID.randomUUID().toString(),
                PIN_PENDING,
                now + PIN_INTENT_MS,
            ).takeIf(::write)
            is PinRead.Present -> if (matches(read.pin, request) && held(read.pin, claims, now)) {
                read.pin
            } else {
                HubDrainPin(
                    request,
                    UUID.randomUUID().toString(),
                    PIN_PENDING,
                    now + PIN_INTENT_MS,
                ).takeIf(::write)
            }
        }
    }

    fun confirm(request: HubDrainPinRequest, claim: String, claims: Set<HubDrainClaim>): Boolean = lock.withLock {
        when (val read = snapshot.read(file(request))) {
            is PinRead.Present -> when {
                !matches(read.pin, request) || read.pin.claim != claim -> false
                read.pin.confirmed(claims) -> true
                read.pin.state != PIN_PENDING || !held(read.pin, claims, System.currentTimeMillis()) -> false
                else -> write(read.pin.copy(state = PIN_ACTIVE))
            }
            PinRead.Unreadable, PinRead.Missing -> false
        }
    }

    fun release(request: HubDrainPinRequest, claim: String): Boolean = lock.withLock {
        when (val read = snapshot.read(file(request))) {
            is PinRead.Present -> releaseStored(read.pin, request, claim)
            PinRead.Unreadable, PinRead.Missing -> false
        }
    }

    /** An unreadable claim store keeps the holder: eviction never runs on missing ownership metadata. */
    fun holds(socket: String, generation: String, claims: Set<HubDrainClaim>): Boolean = lock.withLock {
        val pins = snapshot.all() ?: return true
        pins.any { pin ->
            pin.request.oldSocket == socket && pin.request.oldGeneration == generation &&
                held(pin, claims, System.currentTimeMillis())
        }
    }

    fun readable(): Boolean = lock.withLock { snapshot.all() != null }

    /** A durable front checkpoint recovers existing ownership, including its terminal replay after old-holder exit. */
    fun recover(request: HubDrainPinRequest, claims: Set<HubDrainClaim>): HubDrainPin? = lock.withLock {
        val stored = snapshot.read(file(request)) as? PinRead.Present
        stored?.pin?.takeIf { matches(it, request) && HubDrainClaim(request, it.claim) in claims }
    }

    fun activeClaim(request: HubDrainPinRequest, claim: String): Boolean = lock.withLock {
        val stored = snapshot.read(file(request)) as? PinRead.Present
        stored?.pin?.let { matches(it, request) && it.claim == claim && it.state == PIN_ACTIVE } == true
    }

    fun hasClaim(request: HubDrainPinRequest, claim: String): Boolean = lock.withLock {
        val stored = snapshot.read(file(request)) as? PinRead.Present
        stored?.pin?.let { matches(it, request) && it.claim == claim } == true
    }

    private fun releaseStored(
        pin: HubDrainPin,
        request: HubDrainPinRequest,
        claim: String,
    ): Boolean {
        if (!matches(pin, request)) return false
        if (pin.claim != claim) return false
        return pin.state == PIN_RELEASED || write(pin.copy(state = PIN_RELEASED))
    }

    private fun write(pin: HubDrainPin): Boolean = try {
        Files.createDirectories(pins)
        SecureFile.writeAtomic0600(file(pin.request), JsonWire.string(body(pin)))
        true
    } catch (_: IOException) {
        false
    }

    private fun file(request: HubDrainPinRequest): Path = pins.resolve(
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(
                listOf(request.server, request.launch, request.oldSocket, request.oldGeneration)
                    .joinToString("\n")
                    .toByteArray(StandardCharsets.UTF_8),
            ),
        ) + ".json",
    )

    private fun matches(pin: HubDrainPin, request: HubDrainPinRequest): Boolean = pin.request == request

    private fun body(pin: HubDrainPin): JsonObject = buildJsonObject {
        put(HubFields.SERVER, pin.request.server)
        put(HubFields.LAUNCH, pin.request.launch)
        put(HubFields.OLD_SOCKET, pin.request.oldSocket)
        put(HubFields.OLD_GENERATION, pin.request.oldGeneration)
        put(HubFields.CLAIM, pin.claim)
        put(HubFields.STATE, pin.state)
        put(PIN_UNTIL, pin.until)
    }

    private fun held(pin: HubDrainPin, claims: Set<HubDrainClaim>, now: Long): Boolean = when (pin.state) {
        PIN_ACTIVE -> true
        PIN_PENDING -> HubDrainClaim(pin.request, pin.claim) in claims || pin.until >= now
        else -> false
    }
}

private class HubDrainPinSnapshot(private val pins: Path) {
    private val named = Regex("[0-9a-f]{64}\\.json")

    fun all(): List<HubDrainPin>? = files()?.let(::decoded)

    fun read(path: Path): PinRead = try {
        if (Files.notExists(path)) return PinRead.Missing
        if (!Files.isRegularFile(path)) return PinRead.Unreadable
        JsonRpcCodec().parse(Files.readString(path))?.let(::decode)?.let(PinRead::Present) ?: PinRead.Unreadable
    } catch (_: IOException) {
        PinRead.Unreadable
    } catch (_: RuntimeException) {
        PinRead.Unreadable
    }

    private fun files(): List<Path>? = try {
        when {
            Files.notExists(pins) -> emptyList()
            !Files.isDirectory(pins) -> null
            else -> Files.list(pins).use { paths ->
                paths.filter { named.matches(it.fileName.toString()) }.toList()
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun decoded(paths: List<Path>): List<HubDrainPin>? {
        val reads = paths.map(::read)
        return if (reads.any { it is PinRead.Unreadable }) {
            null
        } else {
            reads.mapNotNull { (it as? PinRead.Present)?.pin }
        }
    }

    private fun decode(body: JsonObject): HubDrainPin? = DecodedPin(
        request(body),
        JsonScalars.str(body, HubFields.CLAIM),
        JsonScalars.str(body, HubFields.STATE),
        JsonScalars.long(body, PIN_UNTIL),
    ).pin()

    private fun request(body: JsonObject): HubDrainPinRequest? {
        val server = JsonScalars.str(body, HubFields.SERVER)
        val launch = JsonScalars.str(body, HubFields.LAUNCH)
        val socket = JsonScalars.str(body, HubFields.OLD_SOCKET)
        val generation = JsonScalars.str(body, HubFields.OLD_GENERATION)
        return if (listOf(server, launch, socket, generation).any { it == null }) {
            null
        } else {
            HubDrainPinRequest(server.orEmpty(), launch.orEmpty(), socket.orEmpty(), generation.orEmpty())
        }
    }
}
