// NEW: a synthetic host withholds close acknowledgments while remaining responsive to other framed requests.
package splice.codemode

import kotlinx.serialization.json.JsonObject
import splice.upstream.codemode.CodeModeCall
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal class SilentHostCloseProcess : Process() {
    var exitOnEngineClose = false
    var holdEngineOpen = false
    private val replies = PipedInputStream()
    private val writer = DataOutputStream(PipedOutputStream(replies))
    private val exited = CompletableFuture<Process>()
    private val engines = mutableSetOf<Long>()
    private val requests = object : ByteArrayOutputStream() {
        override fun flush() {
            if (size() == 0) return
            val frame = HostProtocol.parse(CodeModeWire.read(DataInputStream(ByteArrayInputStream(toByteArray()))))
            reset()
            val type = CodeModeFields.requiredString(frame.payload, "type")
            if (type == "session-open" && holdEngineOpen) return
            if (type == "session-close") {
                if (exitOnEngineClose) destroy()
                return
            }
            val reply = when (type) {
                "session-open" -> {
                    engines.add(frame.session)
                    HostProtocol.count(engines.size)
                }
                "engines" -> HostProtocol.count(engines.size)
                "start" -> if (CodeModeFrames.parseStart(frame.payload).tools.isNotEmpty()) {
                    CodeModeWire.callsFrame(listOf(CodeModeCall("1", "Read", JsonObject(emptyMap()))))
                } else {
                    CodeModeWire.completedFrame("fixture", null)
                }
                else -> CodeModeWire.completedFrame("fixture", null)
            }
            CodeModeWire.write(writer, HostProtocol.frame(frame.cell, frame.request, reply))
        }
    }

    init {
        CodeModeWire.write(writer, CodeModeWire.readyFrame())
    }

    override fun getInputStream(): InputStream = replies
    override fun getOutputStream(): OutputStream = requests
    override fun getErrorStream(): InputStream = InputStream.nullInputStream()
    override fun onExit(): CompletableFuture<Process> = exited
    override fun waitFor(): Int {
        exited.get()
        return 0
    }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited.isDone
    override fun exitValue(): Int = if (exited.isDone) 0 else throw IllegalThreadStateException()
    override fun isAlive(): Boolean = !exited.isDone
    override fun destroy() {
        writer.close()
        exited.complete(this)
    }
    override fun destroyForcibly(): Process {
        destroy()
        return this
    }
}
