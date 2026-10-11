// deterministic native-auth pipes and child lifetime without executing a login or provider request.
package splice.app.auth.claude

import kotlinx.coroutines.CompletableDeferred
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class NativeLoginTestProcess(text: String = "") : Process() {
    private val ended = CompletableFuture<Int>()
    private val output = ByteArrayInputStream(text.toByteArray())
    val stdin = ByteArrayOutputStream()
    val exitObserved = CompletableDeferred<Unit>()

    fun finish(code: Int) {
        ended.complete(code)
    }

    override fun getOutputStream(): OutputStream = stdin
    override fun getInputStream(): InputStream = output
    override fun getErrorStream(): InputStream = InputStream.nullInputStream()
    override fun waitFor(): Int = ended.get()
    override fun exitValue(): Int {
        if (!ended.isDone) throw IllegalThreadStateException("fixture child still running")
        return ended.get()
    }

    override fun isAlive(): Boolean = !ended.isDone

    override fun destroy() {
        finish(143)
    }

    override fun destroyForcibly(): Process {
        destroy()
        return this
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = try {
        ended.get(timeout, unit)
        true
    } catch (_: TimeoutException) {
        false
    }

    override fun onExit(): CompletableFuture<Process> {
        exitObserved.complete(Unit)
        return ended.thenApply { this }
    }
}
