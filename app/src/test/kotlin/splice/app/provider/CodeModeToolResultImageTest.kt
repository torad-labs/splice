// An image Claude Code puts in a tool result reaches a Responses provider as the data URL the client sent: its MIME
// type and its exact base64, whatever its size and whatever text sits beside it, on the round that runs the script and
// on every later turn that replays the script's output. On Oct 26, 2026 a GPT seat's script read two PNGs of 144,920
// base64 chars each and passed them to image(). The provider got each one cut at 65,536 chars, with
// " [truncated 79430 chars]" on its end, and every later request in that conversation replayed them and failed with a
// 400. A conversation the daemon saved that way before the fix works again once the fix is installed.
package splice.app.provider

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.codemode.NativeCodeModeRuntime
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.util.LogSink
import splice.dialect.responses.ReasoningSettings
import splice.head.HeadServer
import splice.head.headDeps
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexCodeModeWiring
import splice.provider.codex.CodexProvider
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

// why: one conversation boots the native host and runs three client requests.
private const val EXAMPLE_SECONDS = 90L

// why: the property runs PROPERTY_CASES conversations of three client requests each on one native host.
private const val PROPERTY_SECONDS = 300L
private const val PROPERTY_CASES = 24
private const val PROPERTY_SEED = 20_261_026L

// why: a 200 x 200 PNG of random pixels does not compress, so it is about 120 KB, past the old 64 KiB cut.
private const val PNG_EDGE = 200
private const val MIN_PNG_BYTES = 100_000

// why: generated images run from 1 x 1 up to about 300 KB of PNG.
private const val MAX_EDGE = 320

// why: long text beside an image runs past the old 64 KiB cut, so it alone could push the image out of the value.
private const val SHORT_TEXT = 200
private const val LONG_TEXT = 90_000
private const val NEWLINE_EVERY = 4_000
private const val RGB_COLORS = 0x1000000

// why: the cut admission made before the fix, from CodeModeLimits.MAX_TEXT_BYTES as it read on Oct 26, 2026.
private const val OLD_TEXT_BYTES = 65_536

private const val MAX_CLIENT_ROUNDS = 8
private const val REQUEST_SECONDS = 60L
private const val STREAM_CHARS = 3_000
private const val LOG_CHARS = 6_000
private const val TAIL_CHARS = 28

private const val ONE_READ = "image(await tools.Read({file_path: '/shot.png'}));"

// The script's shape on Oct 26: two reads at once, each image passed to image() as it arrives.
private const val TWO_READS = "await Promise.allSettled([" +
    "tools.Read({file_path: '/first.png'}).then(image), tools.Read({file_path: '/second.png'}).then(image)]);"

class CodeModeToolResultImageTest {
    @Test
    @Timeout(EXAMPLE_SECONDS)
    fun `two read images reach the provider whole in the script's output and again on the next turn`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val first = ToolPart.Picture("image/png", TestImages.encode("png", PNG_EDGE, PNG_EDGE, Random(1), 1.0))
        val second = ToolPart.Picture("image/png", TestImages.encode("png", PNG_EDGE, PNG_EDGE, Random(2), 1.0))
        check(first.bytes >= MIN_PNG_BYTES && second.bytes >= MIN_PNG_BYTES) { "the PNGs came out under 100 KB" }
        val files = mapOf("/first.png" to first, "/second.png" to second)
        val chat = ClientConversation("image-session")
        rig(tmp) { rig ->
            rig.upstream.script = TWO_READS
            chat.say(rig, "go") { input -> JsonArray(listOf(files.getValue(input.str("file_path")).block())) }
            val ran = checkNotNull(rig.upstream.bodies.firstOrNull(::holdsScriptOutput)) { "no script output" }
            chat.say(rig, "next") { error("the next turn called a tool") }
            val urls = setOf(first.url, second.url)
            assertWhole("the round that ran the script", ran, urls)
            assertWhole("the next turn", rig.upstream.bodies.last(), urls)
        }
    }

    @Test
    @Timeout(EXAMPLE_SECONDS)
    fun `a conversation saved with the old cut image sends it whole again once the fix is installed`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val shot = ToolPart.Picture("image/png", TestImages.encode("png", PNG_EDGE, PNG_EDGE, Random(3), 1.0))
        val chat = ClientConversation("saved-image-session")
        rig(tmp) { rig ->
            rig.upstream.script = ONE_READ
            chat.say(rig, "go") { JsonArray(listOf(shot.block())) }
        }
        OldDaemonJournal.cut(tmp.resolve("records"), shot.url)
        rig(tmp) { rig ->
            chat.say(rig, "next") { error("the turn after the upgrade called a tool") }
            assertWhole("the first turn after the upgrade", rig.upstream.bodies.last(), setOf(shot.url))
        }
    }

    /** For any images, in PNG, JPEG or GIF, from 1 x 1 to about 300 KB, and any tool-result shape that carries them
     *  (one or two reads, one or two images each, with short or long text before, between or after them), every image
     *  the provider receives is one the client sent, byte for byte, and the script's output carries every one. */
    @Test
    @Timeout(PROPERTY_SECONDS)
    fun `every image any tool-result shape carries reaches the provider byte for byte`(@TempDir tmp: Path) =
        runBlocking {
            val random = Random(PROPERTY_SEED)
            rig(tmp) { rig ->
                repeat(PROPERTY_CASES) { index ->
                    val case = ImageCases.generate(random)
                    val from = rig.upstream.bodies.size
                    rig.upstream.script = case.script()
                    val chat = ClientConversation("image-property-$index")
                    chat.say(rig, "go", case::answer)
                    chat.say(rig, "next") { error("case $index: the next turn called a tool") }
                    val sent = rig.upstream.bodies.drop(from)
                    val what = "seed $PROPERTY_SEED case $index (${case.describe()})"
                    val ran = checkNotNull(sent.firstOrNull(::holdsScriptOutput)) { "$what: no script output" }
                    assertWhole("$what, the round that ran the script", ran, case.urls)
                    assertWhole("$what, the next turn", sent.last(), case.urls)
                }
            }
        }

    private suspend fun rig(tmp: Path, block: suspend (ImageRig) -> Unit) {
        val rig = ImageRig(tmp)
        try {
            rig.head.start()
            block(rig)
        } finally {
            rig.stop()
        }
    }

    /** Every image the provider was sent is one the client sent, whole, and the script's output carries each one. */
    private fun assertWhole(what: String, request: JsonObject, urls: Set<String>) {
        val sent = Wire.images(request)
        val wrong = sent.filterNot(urls::contains)
        assertTrue(wrong.isEmpty()) {
            "$what: ${wrong.size} of the ${sent.size} images the provider got are not a data URL the client sent: " +
                wrong.joinToString(transform = Wire::describe) + "; the client sent " +
                urls.joinToString(transform = Wire::describe)
        }
        val scripted = Wire.input(request).filter { Wire.type(it) == "custom_tool_call_output" }.flatMap(Wire::images)
        val missing = urls.filterNot(scripted::contains)
        assertTrue(missing.isEmpty()) {
            "$what: the script's output carried ${scripted.size} images and lacks ${missing.size} the client sent: " +
                missing.joinToString(transform = Wire::describe)
        }
    }

    private fun holdsScriptOutput(request: JsonObject): Boolean =
        Wire.input(request).any { Wire.type(it) == "custom_tool_call_output" }
}

/** A Responses head as the daemon builds one for a code-mode model: a real Codex provider over [ScriptedResponses],
 *  with code mode on the bundled native host and its records under the test's folder, so a second rig on the same
 *  folder is the same daemon after a restart. */
private class ImageRig(tmp: Path) {
    val upstream = ScriptedResponses()
    private val logs = ConcurrentLinkedQueue<String>()
    private val runtime = NativeCodeModeRuntime()
    private val bridge = CodexCodeModeBridge(
        CodeModeBridgeConfig(
            runtimes = { runtime },
            state = CodeModeStateLocation(tmp.resolve("records"), tmp.resolve("legacy.json")),
            log = { line -> logs += line },
        ),
    )
    val head = HeadServer(
        provider(upstream.url, bridge) { line -> logs += line },
        0,
        headDeps(tmp, log = { line -> logs += line }),
    )
    private val client = HttpClient(CIO) { engine { requestTimeout = TimeUnit.SECONDS.toMillis(REQUEST_SECONDS) } }

    /** Posts the conversation as Claude Code does and answers the assistant blocks the client read off the stream. */
    suspend fun send(session: String, messages: List<JsonObject>): List<JsonObject> {
        val stream = client.post("http://127.0.0.1:${head.port}/v1/messages") {
            bearerAuth("test-inference-token")
            headers.append("x-claude-code-session-id", session)
            contentType(ContentType.Application.Json)
            setBody(request(messages).toString())
        }.bodyAsText()
        check("message_stop" in stream && "event: error" !in stream) {
            "the head did not end the turn cleanly: ${stream.take(STREAM_CHARS)}; " +
                "log: ${logs.joinToString(" | ").takeLast(LOG_CHARS)}"
        }
        val blocks = AssistantBlocks()
        stream.lineSequence().filter { it.startsWith("data: ") }
            .forEach { blocks.take(Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject) }
        return blocks.blocks()
    }

    suspend fun stop() {
        try {
            head.stop()
        } finally {
            bridge.onProviderStop()
            runtime.close()
            client.close()
            upstream.close()
        }
    }

    private fun request(messages: List<JsonObject>): JsonObject = buildJsonObject {
        put("model", "claude-codex--gpt-5.6-sol")
        put("stream", true)
        put("max_tokens", 64)
        put("messages", JsonArray(messages))
        putJsonArray("tools") {
            add(
                buildJsonObject {
                    put("name", "Read")
                    put("description", "Read a file from the local filesystem")
                    putJsonObject("input_schema") {
                        put("type", "object")
                        putJsonObject("properties") { putJsonObject("file_path") { put("type", "string") } }
                    }
                },
            )
        }
    }

    private fun provider(url: String, bridge: CodexCodeModeBridge, log: LogSink): CodexProvider = CodexProvider(
        tuning = ProviderTuning(
            name = ProviderName(key = "codex", label = "tool-result-image-test"),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(ModelEntry("gpt-5.6-sol", "Stream", contextWindow = 272_000)),
                defaultContextWindow = 272_000,
            ),
            pinnedModel = "gpt-5.6-sol",
            auth = object : RefreshableAuthProvider {
                override suspend fun credentials(): Credentials = Credentials.Bearer("test-token")
                override suspend fun refresh(): Credentials = credentials()
                override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
            },
            locations = ProviderLocations(baseUrl = url),
            watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
        ),
        reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, null, null),
        codeMode = CodexCodeModeWiring(bridge = bridge, models = listOf("gpt-5.6-sol")),
        log = log,
    )
}

/** What Claude Code keeps and sends: every message so far, each assistant turn as it read it off the stream, and a
 *  tool_result for each tool_use, its content answered from the call's input. */
private class ClientConversation(private val session: String) {
    private val messages = mutableListOf<JsonObject>()

    suspend fun say(rig: ImageRig, text: String, answer: (JsonObject) -> JsonArray) {
        messages += message("user", JsonPrimitive(text))
        repeat(MAX_CLIENT_ROUNDS) {
            val blocks = rig.send(session, messages)
            messages += message("assistant", JsonArray(blocks))
            val uses = blocks.filter { Wire.type(it) == "tool_use" }
            if (uses.isEmpty()) return
            messages += message(
                "user",
                JsonArray(
                    uses.map { use ->
                        buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", use.str("id"))
                            put("content", answer(use.getValue("input").jsonObject))
                        }
                    },
                ),
            )
        }
        error("the turn kept calling tools past $MAX_CLIENT_ROUNDS rounds")
    }

    private fun message(role: String, content: JsonElement): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }
}

/** Folds a response's stream events back into the content blocks the client replays in its next request. */
private class AssistantBlocks {
    private val blocks = sortedMapOf<Int, MutableMap<String, JsonElement>>()
    private val partial = mutableMapOf<Int, StringBuilder>()

    fun take(event: JsonObject) {
        val index = event["index"]?.jsonPrimitive?.intOrNull ?: return
        when (Wire.type(event)) {
            "content_block_start" -> blocks[index] = event.getValue("content_block").jsonObject.toMutableMap()
            "content_block_delta" -> delta(index, event.getValue("delta").jsonObject)
        }
    }

    private fun delta(index: Int, delta: JsonObject) {
        val block = blocks[index] ?: return
        when (Wire.type(delta)) {
            "text_delta" -> block["text"] = JsonPrimitive(block.str("text") + delta.str("text"))
            "thinking_delta" -> block["thinking"] = JsonPrimitive(block.str("thinking") + delta.str("thinking"))
            "signature_delta" -> block["signature"] = delta.getValue("signature")
            "input_json_delta" -> partial.getOrPut(index) { StringBuilder() }.append(delta.str("partial_json"))
        }
    }

    fun blocks(): List<JsonObject> = blocks.map { (index, block) ->
        partial[index]?.let { block["input"] = Json.parseToJsonElement(it.toString().ifEmpty { "{}" }) }
        JsonObject(block)
    }
}

/** A Responses provider on loopback that keeps every request body it is sent. A request whose input holds no exec
 *  call yet gets [script] back as one, while there is a script; every other request gets a one-word answer. */
private class ScriptedResponses {
    @Volatile var script: String? = null
    val bodies = CopyOnWriteArrayList<JsonObject>()
    private val rounds = AtomicInteger()
    private val pool = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = pool
        server.createContext("/responses", ::respond)
        server.start()
    }

    private fun respond(exchange: HttpExchange) {
        val round = rounds.incrementAndGet()
        val body = Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
        bodies += body
        val source = script?.takeIf { Wire.input(body).none { Wire.type(it) == "custom_tool_call" } }
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.use { output ->
            val events = if (source == null) answer(round) else exec(round, source)
            events.forEach { output.write("data: $it\n\n".toByteArray()) }
        }
    }

    private fun exec(round: Int, source: String): List<JsonObject> {
        fun call(input: String) = buildJsonObject {
            put("type", "custom_tool_call")
            put("id", "exec-item-$round")
            put("call_id", "exec-call-$round")
            put("name", "exec")
            put("input", input)
        }
        return listOf(
            buildJsonObject {
                put("type", "response.output_item.added")
                put("output_index", 0)
                put("item", call(""))
            },
            buildJsonObject {
                put("type", "response.custom_tool_call_input.delta")
                put("output_index", 0)
                put("delta", source)
            },
            completed("exec-response-$round", buildJsonArray { add(call(source)) }),
        )
    }

    private fun answer(round: Int): List<JsonObject> {
        val item = buildJsonObject {
            put("type", "message")
            put("id", "answer-$round")
        }
        return listOf(
            buildJsonObject {
                put("type", "response.output_item.added")
                put("output_index", 0)
                put("item", item)
            },
            buildJsonObject {
                put("type", "response.output_text.delta")
                put("output_index", 0)
                put("delta", "done")
            },
            buildJsonObject {
                put("type", "response.output_item.done")
                put("output_index", 0)
                put("item", item)
            },
            completed("answer-response-$round", JsonArray(emptyList())),
        )
    }

    private fun completed(id: String, output: JsonArray): JsonObject = buildJsonObject {
        put("type", "response.completed")
        putJsonObject("response") {
            put("id", id)
            put("status", "completed")
            putJsonObject("usage") {
                put("input_tokens", 100)
                put("output_tokens", 7)
            }
            put("output", output)
        }
    }

    fun close() {
        server.stop(0)
        pool.shutdownNow()
    }
}

/** One part of a tool result's content, as Claude Code sends it. */
private sealed interface ToolPart {
    fun block(): JsonObject

    fun describe(): String

    class Words(private val text: String) : ToolPart {
        override fun block(): JsonObject = buildJsonObject {
            put("type", "text")
            put("text", text)
        }

        override fun describe(): String = "text of ${text.length} chars"
    }

    class Picture(private val mime: String, image: ByteArray) : ToolPart {
        val bytes: Int = image.size
        private val data = Base64.getEncoder().encodeToString(image)
        val url: String = "data:$mime;base64,$data"

        override fun block(): JsonObject = buildJsonObject {
            put("type", "image")
            putJsonObject("source") {
                put("type", "base64")
                put("media_type", mime)
                put("data", data)
            }
        }

        override fun describe(): String = "$mime of ${data.length} base64 chars"
    }
}

/** One generated case: one or two reads, each answered with its own tool-result content. The script splits each value
 *  into lines, as a model's script reads a value that carries text and images, and passes every data URL to image(). */
private class ImageCase(private val calls: List<List<ToolPart>>) {
    val urls: Set<String> = calls.flatten().filterIsInstance<ToolPart.Picture>().mapTo(mutableSetOf()) { it.url }

    fun script(): String {
        val reads = calls.indices.joinToString(", ") { "tools.Read({file_path: '/case/$it'})" }
        return "const values = await Promise.all([$reads]);\n" +
            "for (const value of values) for (const line of String(value).split('\\n')) {\n" +
            "  if (line.startsWith('data:')) image(line); else if (line.trim()) text(line);\n" +
            "}\n"
    }

    fun answer(input: JsonObject): JsonArray =
        JsonArray(calls[input.str("file_path").removePrefix("/case/").toInt()].map(ToolPart::block))

    fun describe(): String =
        calls.joinToString("; ") { parts -> parts.joinToString(" + ", transform = ToolPart::describe) }
}

private object ImageCases {
    private val formats = listOf("png" to "image/png", "jpeg" to "image/jpeg", "gif" to "image/gif")
    private const val LETTERS = "abcdefghijklmnopqrstuvwxyz "

    fun generate(random: Random): ImageCase = ImageCase(List(1 + random.nextInt(2)) { call(random) })

    private fun call(random: Random): List<ToolPart> {
        val pictures = List(1 + random.nextInt(2)) { picture(random) }
        val words = List(random.nextInt(3)) { words(random) }
        return (pictures + words).shuffled(random)
    }

    private fun picture(random: Random): ToolPart.Picture {
        val (format, mime) = formats.random(random)
        val noise = listOf(0.0, 0.2, 1.0).random(random)
        val width = 1 + random.nextInt(MAX_EDGE)
        val height = 1 + random.nextInt(MAX_EDGE)
        return ToolPart.Picture(mime, TestImages.encode(format, width, height, random, noise))
    }

    private fun words(random: Random): ToolPart.Words {
        val length = 1 + random.nextInt(if (random.nextBoolean()) SHORT_TEXT else LONG_TEXT)
        return ToolPart.Words(
            buildString {
                repeat(length) {
                    append(if (random.nextInt(NEWLINE_EVERY) == 0) '\n' else LETTERS[random.nextInt(LETTERS.length)])
                }
            },
        )
    }
}

private object TestImages {
    /** A real [format] image of [width] x [height]: one ground color, with a [noise] share of random pixels. */
    fun encode(format: String, width: Int, height: Int, random: Random, noise: Double): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val ground = random.nextInt(RGB_COLORS)
        for (y in 0 until height) {
            for (x in 0 until width) {
                image.setRGB(x, y, if (random.nextDouble() < noise) random.nextInt(RGB_COLORS) else ground)
            }
        }
        val bytes = ByteArrayOutputStream()
        check(ImageIO.write(image, format, bytes)) { "this JDK has no ImageIO writer for $format" }
        return bytes.toByteArray()
    }
}

/** The record as the daemon before the fix saved it. Admission cut a result's payload like its text, so an image-only
 *  result's payload was its data URL cut at 65,536 chars, and the script's image() of it put the same cut into the
 *  script's saved output and into the output's JSON text. The image's media follow-up was saved whole. */
private object OldDaemonJournal {
    fun cut(records: Path, url: String) {
        val journals = Files.list(records).use { paths -> paths.filter { "$it".endsWith(".jsonl") }.toList() }
        check(journals.isNotEmpty()) { "no code-mode journal under $records" }
        journals.forEach { journal ->
            val lines = Files.readAllLines(journal).map { line ->
                if (line.isBlank()) line else saved(Json.parseToJsonElement(line), null, url).toString()
            }
            Files.write(journal, lines)
        }
        val old = cut(url)
        check(journals.any { old in Files.readString(it) }) { "no journal holds the image as the old daemon cut it" }
    }

    /** Admission's cut before the fix: the longest prefix that, followed by " [truncated N chars]" for the N chars it
     *  drops, fits 65,536 bytes. Every value cut here is ASCII, so a char is a byte. */
    fun cut(value: String): String {
        if (value.length <= OLD_TEXT_BYTES) return value
        var kept = OLD_TEXT_BYTES
        while (kept + marker(value.length - kept).length > OLD_TEXT_BYTES) kept--
        return value.take(kept) + marker(value.length - kept)
    }

    private fun marker(gone: Int): String = " [truncated $gone chars]"

    private fun saved(element: JsonElement, key: String?, url: String): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (name, value) -> saved(value, name, url) })
        is JsonArray -> JsonArray(
            element.map { item -> if (key == "nativeOutput") scriptImage(item, url) else saved(item, null, url) },
        )
        is JsonPrimitive -> savedText(element, key, url)
    }

    /** A saved string holding the image: the result's payload was cut whole, and the script's output held its cut. */
    private fun savedText(element: JsonPrimitive, key: String?, url: String): JsonPrimitive = when {
        !element.isString || url !in element.content -> element
        key == "payload" -> JsonPrimitive(cut(element.content))
        key == "output" -> JsonPrimitive(element.content.replace(url, cut(url)))
        else -> element
    }

    private fun scriptImage(item: JsonElement, url: String): JsonElement {
        val image = item as? JsonObject ?: return item
        if (image.str("image_url") != url) return image
        return JsonObject(image + ("image_url" to JsonPrimitive(cut(url))))
    }
}

private object Wire {
    fun input(request: JsonObject): List<JsonObject> =
        (request["input"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    fun type(item: JsonObject): String = item.str("type")

    /** Every input_image's image_url anywhere under [element], in order. */
    fun images(element: JsonElement): List<String> = when (element) {
        is JsonObject -> {
            val own = if (type(element) == "input_image") listOf(element.str("image_url")) else emptyList()
            own + element.values.flatMap(::images)
        }
        is JsonArray -> element.flatMap(::images)
        is JsonPrimitive -> emptyList()
    }

    fun describe(url: String): String =
        "${url.substringBefore(',')} of ${url.length} chars ending …${url.takeLast(TAIL_CHARS)}"
}

private fun Map<String, JsonElement>.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
