// NEW: corrupt reply addresses fail the waiting caller instead of silently abandoning it.
package splice.codemode

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.codemode.host.HostLaunch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class HostReplyAddressTest {
    @Test
    fun `an unknown request id fails the caller instead of hanging`() {
        assertAddressFailure(wrongRequest = true)
    }

    @Test
    fun `a wrong cell id fails the caller instead of corrupting sibling results`() {
        assertAddressFailure(wrongRequest = false)
    }

    @Test
    fun `a failed boot closes its host before an undispatched caller can request a replacement`() = runBlocking {
        SharedWorkerChannel(WrongAddressProcess(wrongRequest = true, ready = false), this).use { host ->
            val observed = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                try {
                    host.awaitReady()
                    false
                } catch (_: IOException) {
                    host.isClosed
                }
            }
            assertTrue(
                withTimeout(30_000) { observed.await() },
                "a failed generation must be closed before a waiting caller resumes",
            )
        }
    }

    @Test
    fun `a reply proof belongs only to its issued cell request and host generation`() {
        val addresses = HostReplyAddresses()
        val request = HostFrame(1, 1, HostProtocol.command("synthetic"))
        val signed = request.copy(replyKey = addresses.key(request.cell, request.request))
        assertTrue(addresses.matches(signed))
        assertFalse(addresses.matches(signed.copy(cell = 2)))
        assertFalse(addresses.matches(signed.copy(request = 2)))
        assertFalse(HostReplyAddresses().matches(signed), "a replacement generation rejects the old proof")
        assertFalse(addresses.matches(request), "a missing proof cannot claim a released slot")
        assertFalse(addresses.matches(signed.copy(replyKey = "0".repeat(checkNotNull(signed.replyKey).length))))
    }

    private fun assertAddressFailure(wrongRequest: Boolean) {
        assertThrows(IOException::class.java) {
            runBlocking {
                JvmCodeModeRuntime(
                    launch = HostLaunch(spawn = WorkerSpawn { WrongAddressProcess(wrongRequest) }),
                ).use { runtime ->
                    withTimeout(30_000) { runtime.start("return 'never';", emptySet()) }
                }
            }
        }
    }

    private class WrongAddressProcess(private val wrongRequest: Boolean, ready: Boolean = true) : Process() {
        private val replies = PipedInputStream()
        private val writer = DataOutputStream(PipedOutputStream(replies))
        private val exited = CompletableFuture<Process>()
        private val requests = object : ByteArrayOutputStream() {
            override fun flush() {
                if (size() == 0) return
                val frame = CodeModeWire.read(DataInputStream(ByteArrayInputStream(toByteArray())))
                val request = HostProtocol.parse(frame)
                reset()
                CodeModeWire.write(
                    writer,
                    HostProtocol.frame(
                        request.cell + if (wrongRequest) 0 else 1,
                        request.request + if (wrongRequest) 1 else 0,
                        CodeModeWire.completedFrame("wrong", null),
                        request.session,
                        request.replyKey,
                    ),
                )
            }
        }

        init {
            CodeModeWire.write(writer, if (ready) CodeModeWire.readyFrame() else CodeModeWire.completedFrame("", null))
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
}
