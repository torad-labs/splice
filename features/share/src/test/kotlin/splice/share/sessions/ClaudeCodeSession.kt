// NEW: LAN share, phase 2, milestone 6 (docs/specs/lan-share.md). A Claude Code session as Claude Code 2.1.309 keeps
// one, for the tests of sessions across machines: a real process for its pid, its registry record and key file written
// as the client writes them (read from its bundle and from real records on Oct 23), and its inbox socket, which keeps
// each frame a sender wrote after presenting its token. It lists and sends the way the client's ListAgents and
// SendMessage do: every record whose process is alive, and a send to a name or a `uds:` address through that record's
// key file, with the frame the client wrote to a probe's socket (tools/probes/claude-code-mirror-anchor.ts). Its socket
// is 0600, as the client leaves its own (`srw-------` in /tmp/cc-socks on this Mac, Oct 23).
package splice.share.sessions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** What a session's inbox took: the sender's `from` address, and its envelope's `from-name` and text. */
internal data class Heard(val from: String?, val fromName: String?, val text: String)

internal class ClaudeCodeSession(
    private val registry: Path,
    sockets: Path,
    val name: String,
) : AutoCloseable {
    private val process: Process = ProcessBuilder("sleep", "600").start()
    val pid: Long = process.pid()
    val socket: Path = sockets.resolve("$pid.sock")
    private val token = UUID.randomUUID().toString().replace("-", "")
    private val inbox = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    private val frames = LinkedBlockingQueue<Heard>()
    private val envelope = Regex("^<cross-session-message([^>]*)>\\n([\\s\\S]*)\\n</cross-session-message>$")
    private val attribute = Regex("([a-z-]+)=\"([^\"]*)\"")

    init {
        inbox.bind(UnixDomainSocketAddress.of(socket))
        Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------"))
        Thread.ofVirtual().start { accept() }
        val (domain, start) = identity(pid)
        val now = System.currentTimeMillis()
        val key = """{"peerToken":"$token","procStart":"$start","pidDomain":"$domain"}"""
        Files.writeString(keyFile(registry, pid, socket), key)
        val record = buildJsonObject {
            put("pid", pid)
            put("sessionId", UUID.randomUUID().toString())
            put("cwd", registry.toString())
            put("startedAt", now)
            put("procStart", start)
            put("version", "2.1.309")
            put("peerProtocol", 1)
            put("kind", "interactive")
            put("entrypoint", "cli")
            put("pidDomain", domain)
            put("messagingSocketPath", socket.toString())
            put("name", name)
            put("nameSource", "user")
            put("status", "idle")
            put("updatedAt", now)
            put("statusUpdatedAt", now)
        }
        Files.writeString(registry.resolve("$pid.json"), record.toString())
    }

    /** The names ListAgents shows this session: every record in its registry whose process is alive, itself left out. */
    fun agents(): Set<String> = registry.listDirectoryEntries("*.json").mapNotNull { file ->
        val record = runCatching { Json.parseToJsonElement(Files.readString(file)).jsonObject }.getOrNull()
        val alive = record?.get("pid")?.jsonPrimitive?.content?.toLongOrNull()
            ?.let { ProcessHandle.of(it).map(ProcessHandle::isAlive).orElse(false) } == true
        record?.get("name")?.jsonPrimitive?.content?.takeIf { alive && file.name != "$pid.json" }
    }.toSet()

    /** SendMessage to [to], a name or a `uds:` address: the auth line from the key file beside that record, then the
     *  frame. */
    fun send(to: String, text: String) {
        val target = registry.listDirectoryEntries("*.json")
            .map { Json.parseToJsonElement(Files.readString(it)).jsonObject }
            .single { record ->
                val path = record["messagingSocketPath"]?.jsonPrimitive?.content
                record["name"]?.jsonPrimitive?.content == to || "uds:$path" == to
            }
        val path = Paths.get(target.getValue("messagingSocketPath").jsonPrimitive.content)
        val targetPid = target.getValue("pid").jsonPrimitive.content.toLong()
        val key = Json.parseToJsonElement(Files.readString(keyFile(registry, targetPid, path)))
            .jsonObject.getValue("peerToken").jsonPrimitive.content
        val from = "uds:$socket"
        val frame = buildJsonObject {
            put("msgV", 1)
            put("msg_id", UUID.randomUUID().toString())
            put("type", "user")
            put("priority", "next")
            put("from", from)
            put(
                "message",
                buildJsonObject {
                    put("role", "user")
                    put(
                        "content",
                        JsonPrimitive(
                            "<cross-session-message from=\"$from\" from-name=\"$name\" from-mode=\"bypass\">\n$text\n" +
                                "</cross-session-message>",
                        ),
                    )
                },
            )
        }
        SocketChannel.open(UnixDomainSocketAddress.of(path)).use { channel ->
            val out = Channels.newOutputStream(channel)
            out.write("""{"type":"auth","token":"$key"}""".toByteArray() + '\n'.code.toByte())
            out.write(frame.toString().toByteArray() + '\n'.code.toByte())
            out.flush()
        }
    }

    /** The next message this session took within [seconds], or null. */
    fun heard(seconds: Long): Heard? = frames.poll(seconds, TimeUnit.SECONDS)

    /** The session ends as the client ends one: its process exits and it removes its record, key file and socket. */
    override fun close() {
        process.destroy()
        process.waitFor()
        Files.deleteIfExists(registry.resolve("$pid.json"))
        Files.deleteIfExists(keyFile(registry, pid, socket))
        inbox.close()
        Files.deleteIfExists(socket)
    }

    private fun accept() {
        while (inbox.isOpen) {
            val connection = runCatching { inbox.accept() }.getOrNull() ?: return
            Thread.ofVirtual().start { read(connection) }
        }
    }

    private fun read(connection: SocketChannel) = connection.use {
        val lines = Channels.newReader(connection, Charsets.UTF_8).buffered().lineSequence().iterator()
        val vouched = lines.hasNext() &&
            Json.parseToJsonElement(lines.next()).jsonObject["token"]?.jsonPrimitive?.content == token
        while (vouched && lines.hasNext()) {
            val frame = Json.parseToJsonElement(lines.next()).jsonObject
            if (frame["type"]?.jsonPrimitive?.content == "user") frames.add(heardOf(frame))
        }
    }

    private fun heardOf(frame: JsonObject): Heard {
        val content = frame.getValue("message").jsonObject.getValue("content").jsonPrimitive.content
        val match = envelope.matchEntire(content)
        val attrs = match?.groupValues?.get(1)?.let { head ->
            attribute.findAll(head).associate { it.groupValues[1] to it.groupValues[2] }
        }.orEmpty()
        return Heard(frame["from"]?.jsonPrimitive?.content, attrs["from-name"], match?.groupValues?.get(2) ?: content)
    }

    /** The pidDomain and procStart Claude Code 2.1.309 writes for [pid] on this host: on Linux,
     *  `linux:<machine-id>:<pid namespace>` and field 22 of /proc/<pid>/stat; elsewhere but Windows, "darwin" and
     *  `ps -o lstart= -p <pid>` under LC_ALL=C and TZ=UTC, trimmed. */
    private fun identity(pid: Long): Pair<String, String> {
        if (System.getProperty("os.name").orEmpty().startsWith("Linux")) {
            val machine = Files.readString(Paths.get("/etc/machine-id")).trim()
            val namespace = Files.readSymbolicLink(Paths.get("/proc/self/ns/pid"))
            val stat = Files.readString(Paths.get("/proc/$pid/stat")).substringAfterLast(')').trim().split(' ')
            return "linux:$machine:$namespace" to stat[19]
        }
        val ps = ProcessBuilder("ps", "-o", "lstart=", "-p", "$pid")
        ps.environment()["LC_ALL"] = "C"
        ps.environment()["TZ"] = "UTC"
        return "darwin" to ps.start().inputStream.use { it.readAllBytes() }.decodeToString().trim()
    }

    private fun keyFile(dir: Path, pid: Long, socket: Path): Path {
        val digest = MessageDigest.getInstance("SHA-256").digest(socket.toString().toByteArray())
        val hash = HexFormat.of().formatHex(digest)
        return dir.resolve("$pid.$hash.key")
    }
}
