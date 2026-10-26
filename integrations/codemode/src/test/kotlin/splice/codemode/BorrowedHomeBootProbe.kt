// NEW: a separate JVM whose first worker pin is taken inside a home a test lends the process, as a daemon a test boots
// there takes it, and whose next worker starts after that home is deleted.
package splice.codemode

import kotlinx.coroutines.runBlocking
import splice.core.config.UserHome
import splice.upstream.codemode.CodeModeStep
import java.nio.file.Path

internal object BorrowedHomeBootProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val borrowed = Path.of(args.single())
        UserHome.within(borrowed) { WorkerArtifacts.pinAtBoot() }
        // The test that lent the home ends, and JUnit deletes it; the process lives on and starts its next worker.
        check(borrowed.toFile().deleteRecursively()) { "the borrowed home was not deleted" }
        val said = try {
            JvmCodeModeRuntime().use { runtime ->
                val step = runBlocking { runtime.start("return 'booted';", emptySet()).advance() }
                (step as? CodeModeStep.Completed)?.output ?: "ended as $step"
            }
        } catch (error: Exception) {
            generateSequence<Throwable>(error) { it.cause }
                .joinToString(" <- ") { "${it::class.simpleName}: ${it.message}" }
        }
        println(said)
    }
}
