// The front's in-place upgrade. On SIGUSR2 it stops admitting new operations, lets every call in flight finish, writes what
// each open descriptor is, and execs the binary now at its own path, which an install has replaced. The pid, the
// listening socket, every session's connection and stream, and every holder link carry over, so no session sees a
// close. A request that arrives meanwhile waits in the kernel and is read by the new code.
@file:OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)

package splice.hub.front

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.posix.execv
import platform.posix.readlink
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class Upgrade(private val sys: Sys, private val files: HubFiles, private val args: List<String>) {
    /** Whether the loop can hand over now: no retained wire id, no unanswered request, and nothing unwritten. */
    fun settled(loop: Loop, wireOutstanding: Int): Boolean =
        wireOutstanding == 0 && quiet(loop)

    private fun quiet(loop: Loop): Boolean = loop.all().none { watched ->
        when (watched) {
            is HttpConn -> watched.pendingOutput || (!watched.streaming && !watched.quiet())
            is HolderLink -> watched.pendingOutput
            is DaemonCall -> true
            else -> false
        }
    }

    /** Writes the descriptors and execs. Returns only when the exec failed. */
    fun exec(loop: Loop, listener: HttpListener, hub: Hub, path: String) {
        hub.stopping = true
        if (!hub.save()) return handoffFailed(hub, "the hub state could not be durably saved")
        val handoff = UpgradeHandoff(listener, loop.all())
        if (!handoff.save(files)) return handoffFailed(hub, "the descriptor handoff could not be durably saved")
        handoff.inherit(sys)
        try {
            replacing(path, handoff)
        } finally {
            handoff.protect(sys)
        }
        handoffFailed(hub, "the exec of $path failed")
    }

    private fun replacing(path: String, handoff: UpgradeHandoff) {
        val kept = args.filterIndexed { i, a -> a != "--resume" && (i == 0 || args[i - 1] != "--resume") }
        val argv = listOf(path) + kept + listOf("--resume", files.execPath())
        handoff.announced(files)
        memScoped {
            val c = allocArray<CPointerVar<ByteVar>>(argv.size + 1)
            argv.forEachIndexed { i, a -> c[i] = a.cstr.ptr }
            c[argv.size] = null
            execv(path, c)
        }
    }

    private fun handoffFailed(hub: Hub, words: String) {
        hub.stopping = false
        files.say("$words; carrying on with the running code")
    }

    /** The binary at this process's own path, which an install has replaced by now. */
    fun ownPath(): String = memScoped {
        val buf = allocArray<ByteVar>(PATH_MAX)
        val n = readlink("/proc/self/exe", buf, (PATH_MAX - 1).convert()).toInt()
        if (n <= 0) return@memScoped ""
        buf[n] = 0
        buf.toKString().removeSuffix(" (deleted)")
    }

    /** Puts back what [exec] wrote: the listener's fd, and every connection and link. */
    fun resume(file: String, listener: (Int) -> HttpListener, hub: Hub): HttpListener? {
        val state = files.readJson(file) ?: return null
        files.remove(file)
        val l = listener(state["listener"]!!.jsonPrimitive.int)
        state["conns"]?.jsonArray?.forEach { e ->
            val o = e.jsonObject
            val conn = l.adopt(sys.nonblocking(o["fd"]!!.jsonPrimitive.int))
            conn.resume(Base64.decode(o[INBOX]!!.jsonPrimitive.content), o["stream"]?.jsonPrimitive?.content)
            if (conn.streaming) hub.adoptStream(conn) else conn.parse()
        }
        state["links"]?.jsonArray?.forEach { e ->
            val o = e.jsonObject
            hub.adoptLink(
                sys.nonblocking(o["fd"]!!.jsonPrimitive.int),
                o["socket"]!!.jsonPrimitive.content,
                Base64.decode(o[INBOX]!!.jsonPrimitive.content),
            )
        }
        val conns = state["conns"]?.jsonArray?.size ?: 0
        files.say("resumed in place: $conns connections, ${state["links"]?.jsonArray?.size ?: 0} holders")
        return l
    }
}

private class UpgradeHandoff(
    private val listener: HttpListener,
    private val all: List<Watched>,
) {
    private val inherited = all.filter { it is HttpConn || it is HolderLink || it is HttpListener }

    fun save(files: HubFiles): Boolean = files.saveExec(Json.encodeToString(JsonObject.serializer(), state()))

    fun inherit(sys: Sys) {
        inherited.forEach { sys.inherit(it.fd) }
    }

    fun protect(sys: Sys) {
        inherited.forEach { sys.protect(it.fd) }
    }

    fun announced(files: HubFiles) {
        files.say(
            "upgrading in place: ${all.count { it is HttpConn }} connections, " +
                "${all.count { it is HolderLink }} holders",
        )
    }

    private fun state(): JsonObject = buildJsonObject {
        put("listener", listener.fd)
        put("conns", connections())
        put("links", links())
    }

    private fun connections() = buildJsonArray {
        all.filterIsInstance<HttpConn>().forEach { conn ->
            add(
                buildJsonObject {
                    put("fd", conn.fd)
                    put(INBOX, Base64.encode(conn.inbox.data, 0, conn.inbox.size))
                    conn.streamOf?.takeIf { conn.streaming }?.let { put("stream", it) }
                },
            )
        }
    }

    private fun links() = buildJsonArray {
        all.filterIsInstance<HolderLink>().forEach { link ->
            add(
                buildJsonObject {
                    put("fd", link.fd)
                    put("socket", link.socket)
                    put(INBOX, Base64.encode(link.inbox.data, 0, link.inbox.size))
                },
            )
        }
    }
}

private const val PATH_MAX = 4096
private const val INBOX = "inbox"
