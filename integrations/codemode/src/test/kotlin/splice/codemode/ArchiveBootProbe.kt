// NEW: a separate JVM loads the daemon's archive before its install pathname is replaced.
package splice.codemode

import kotlinx.coroutines.runBlocking
import splice.codemode.host.HostLaunch
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

internal object ArchiveBootProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        check(args.isEmpty() || args.contentEquals(arrayOf("eager")))
        Class.forName("splice.codemode.CodeModeWorker")
        if (args.isNotEmpty()) WorkerArtifacts.pinAtBoot()
        println("loaded original")
        check(System.`in`.read() >= 0)
        val spawned = AtomicInteger()
        try {
            JvmCodeModeRuntime(
                launch = HostLaunch(
                    spawn = WorkerSpawn { builder ->
                        spawned.incrementAndGet()
                        builder.start()
                    },
                ),
            ).use { runtime ->
                val result = runBlocking {
                    runtime.start("return 'original archive';", emptySet()).advance()
                }
                check((result as splice.upstream.codemode.CodeModeStep.Completed).output == "original archive")
            }
            println("ran original archive")
        } catch (error: IOException) {
            val mismatch = error.message?.contains("was replaced before code mode pinned it; restart splice") == true
            val reply =
                if (spawned.get() == 0 && mismatch) "refused before spawn" else "unexpected failure: ${error.message}"
            println(reply)
        }
    }
}
