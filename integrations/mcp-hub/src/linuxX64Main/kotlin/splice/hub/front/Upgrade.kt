// The front's in-place upgrade. On SIGUSR2 it stops reading new requests, lets every call in flight finish, writes what
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
    /** Whether the loop can hand over now: nothing read and unanswered, nothing unwritten. */
    fun settled(loop: Loop): Boolean =
        loop.all().none { it is HttpConn && !it.streaming && !it.quiet() || it is DaemonCall }

    fun drain(loop: Loop) {
        loop.all().filterIsInstance<HttpConn>().forEach { it.draining = true }
    }

    /** Writes the descriptors and execs. Returns only when the exec failed. */
    fun exec(loop: Loop, listener: HttpListener, hub: Hub, path: String) {
        hub.stopping = true
        hub.save()
        val all = loop.all()
        val state = buildJsonObject {
            put("listener", listener.fd)
            put(
                "conns",
                buildJsonArray {
                    all.filterIsInstance<HttpConn>().forEach { c ->
                        add(
                            buildJsonObject {
                                put("fd", c.fd)
                                put(INBOX, Base64.encode(c.inbox.data, 0, c.inbox.size))
                                c.streamOf?.takeIf { c.streaming }?.let { put("stream", it) }
                            },
                        )
                    }
                },
            )
            put(
                "links",
                buildJsonArray {
                    all.filterIsInstance<HolderLink>().forEach { l ->
                        add(
                            buildJsonObject {
                                put("fd", l.fd)
                                put("socket", l.socket)
                                put(INBOX, Base64.encode(l.inbox.data, 0, l.inbox.size))
                            },
                        )
                    }
                },
            )
        }
        files.saveExec(Json.encodeToString(JsonObject.serializer(), state))
        all.filter { it is HttpConn || it is HolderLink || it is HttpListener }.forEach { sys.inherit(it.fd) }
        val kept = args.filterIndexed { i, a -> a != "--resume" && (i == 0 || args[i - 1] != "--resume") }
        val argv = listOf(path) + kept + listOf("--resume", files.execPath())
        val conns = all.count { it is HttpConn }
        files.say("upgrading in place: $conns connections, ${all.count { it is HolderLink }} holders")
        memScoped {
            val c = allocArray<CPointerVar<ByteVar>>(argv.size + 1)
            argv.forEachIndexed { i, a -> c[i] = a.cstr.ptr }
            c[argv.size] = null
            execv(path, c)
        }
        files.say("the exec of $path failed; carrying on with the running code")
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

private const val PATH_MAX = 4096
private const val INBOX = "inbox"
