package splice.head.trace.body

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.system.exitProcess

/**
 * Incident preparation only: hydrate a copied v2 client body, never restore or execute a turn.
 * Arguments: copy directory, NEW private output directory, row SHA-256, pack SHA-256, turn id, timestamp.
 * Only a completed receipt admits the private artifact. Neither output nor errors expose request contents.
 */
object TraceBodyHydrationProbe {
    // Incident bounds exceed the selected 129 KiB row and 16 MiB literal, without admitting arbitrary trace sizes.
    private const val MAX_ROW_BYTES = 1024L * 1024
    private const val MAX_LITERAL_BYTES = 32L * 1024 * 1024
    private const val MAX_BLOCK_DEPTH = 256
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val privateDirectory = PosixFilePermissions.fromString("rwx------")
    private val privateFile = PosixFilePermissions.fromString("rw-------")
    private val countedTypes = setOf(
        "tool_use",
        "tool_result",
        "redacted_thinking",
        "thinking",
        "image",
        "document",
        "tool_reference",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        var stage = "arguments"
        try {
            println(hydrate(args) { stage = it })
        } catch (failure: Throwable) {
            // This command exits on every failure, including cancellation; nothing resumes or retries a turn.
            println(
                buildJsonObject {
                    put("status", "refused")
                    put("stage", stage)
                    put("exception_class", failure.javaClass.simpleName)
                },
            )
            exitProcess(1)
        }
    }

    private fun hydrate(args: Array<String>, stage: (String) -> Unit): JsonObject {
        require(args.size == 6 && hashPattern.matches(args[2]) && hashPattern.matches(args[3]))
        require(Regex("[0-9a-f]{12}").matches(args[4]))
        val timestamp = args[5].toLong()
        val source = Path.of(args[0]).toRealPath()
        val destination = Path.of(args[1]).toAbsolutePath().normalize()
        require(destination.parent.toRealPath() == destination.parent)
        require(!destination.startsWith(source))
        val row = regular(source.resolve("failed-turn.json"))
        val pack = regular(source.resolve("trace.bodies2"))
        require(Files.size(row) in 1..MAX_ROW_BYTES)

        stage("input_hashes")
        require(hash(row) == args[2] && hash(pack) == args[3])
        val rowJson = Json.parseToJsonElement(Files.readString(row)) as JsonObject
        stage("identity_and_bounds")
        require(JsonScalars.str(rowJson["turn"]) == args[4])
        require((rowJson["ts"] as? JsonPrimitive)?.longOrNull == timestamp)
        val client = rowJson["client"] as JsonObject
        require((client["truncated"] as? JsonPrimitive)?.booleanOrNull == false)
        val reference = client["body"] as JsonObject
        require(TracePackFormat.V2.names(reference))
        val parts = reference["parts"] as JsonArray
        val literalBytes = bounds(parts, pack)

        stage("production_reader")
        val request = TraceBodyReader(pack, TracePackFormat.V2).use { it.literal(parts).content }
        stage("request_structure")
        val structure = structure(Json.parseToJsonElement(request) as JsonObject)
        stage("unchanged_inputs")
        require(hash(row) == args[2] && hash(pack) == args[3])
        stage("private_artifact")
        val metadata = buildJsonObject {
            put("parts", parts.size)
            put("literal_bytes", literalBytes)
            put("structure", structure)
        }
        return artifact(destination, request, args, metadata)
    }

    private fun artifact(
        destination: Path,
        request: String,
        args: Array<String>,
        metadata: JsonObject,
    ): JsonObject {
        Files.createDirectory(destination, PosixFilePermissions.asFileAttribute(privateDirectory))
        val artifact = destination.resolve("request.json")
        writePrivate(artifact, request)
        val receipt = buildJsonObject {
            put("status", "hydrated_only")
            put("row_sha256", args[2])
            put("pack_sha256", args[3])
            put("request_sha256", hash(artifact))
            put("request_bytes", Files.size(artifact))
            put("turn", args[4])
            put("timestamp", args[5].toLong())
            metadata.forEach { (key, value) -> put(key, value) }
            put("conversion", "not_attempted_missing_historical_inputs")
            put("prefix_reconciliation", "not_attempted")
            put("delta_encoding", "not_attempted")
            put("size_comparison", "not_established")
        }
        writePrivate(destination.resolve("receipt.json"), receipt.toString())
        return receipt
    }

    private fun regular(path: Path): Path {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        return path
    }

    /** Check scalar bounds and complete stored entries BEFORE opening the production reader. */
    private fun bounds(parts: JsonArray, pack: Path): Long {
        require(parts.isNotEmpty() && parts.size.toLong() <= MAX_LITERAL_BYTES)
        val format = TracePackFormat.V2
        var total = 0L
        FileChannel.open(pack, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            parts.forEach { element ->
                val part = element as JsonObject
                val offset = requireNotNull((part["offset"] as? JsonPrimitive)?.longOrNull)
                val bytes = requireNotNull((part["bytes"] as? JsonPrimitive)?.intOrNull)
                val digest = requireNotNull(JsonScalars.str(part["hash"]))
                require(bytes in 1..CHUNK_MAX && hashPattern.matches(digest))
                require(offset >= TRACE_PACK_START_BYTES + format.headerBytes && offset < channel.size())
                total = Math.addExact(total, bytes.toLong())
                require(total <= MAX_LITERAL_BYTES)
                val entry = requireNotNull(format.entry(channel, offset))
                require(entry.raw == bytes && entry.hash == digest)
            }
        }
        return total
    }

    private fun structure(body: JsonObject): JsonObject {
        val counts = countedTypes.associateWith { 0L }.toMutableMap()
        (body["messages"] as? JsonArray)?.forEach { message ->
            (message as? JsonObject)?.get("content")?.let { count(it, counts, 0) }
        }
        return buildJsonObject {
            put("messages", (body["messages"] as? JsonArray)?.size ?: 0)
            put("tools", (body["tools"] as? JsonArray)?.size ?: 0)
            put("block_types", buildJsonObject { counts.forEach { (type, total) -> put(type, total) } })
        }
    }

    private fun count(value: JsonElement, counts: MutableMap<String, Long>, depth: Int) {
        require(depth <= MAX_BLOCK_DEPTH)
        when (value) {
            is JsonObject -> {
                val type = JsonScalars.str(value["type"])
                if (type != null && type in countedTypes) counts[type] = counts.getValue(type) + 1
                // Only transcript block containers are counted; schemas and arbitrary tool arguments stay opaque.
                if (type == "tool_result") value["content"]?.let { count(it, counts, depth + 1) }
            }
            is JsonArray -> value.forEach { count(it, counts, depth + 1) }
            is JsonPrimitive -> Unit
        }
    }

    private fun hash(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var length = input.read(buffer)
            while (length != -1) {
                digest.update(buffer, 0, length)
                length = input.read(buffer)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun writePrivate(path: Path, content: String) {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(privateFile))
        Files.writeString(path, content, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        require(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS) == privateFile)
    }
}
