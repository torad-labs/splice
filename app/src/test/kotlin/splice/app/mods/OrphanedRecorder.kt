package splice.app.mods

import kotlinx.coroutines.runBlocking
import splice.core.config.MgmtKey
import java.nio.file.Path

/** A scratch JVM lets the test kill the owner without running any of its cooperative cleanup. */
internal object OrphanedRecorder {
    fun start(root: Path): Process = ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp",
        System.getProperty("java.class.path"),
        javaClass.name,
        root.toString(),
    ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val rig = RunnerTestRig(Path.of(args.single()))
        rig.runner.start()
        try {
            if (System.`in`.read() >= 0) {
                // Stop the Bun process for retirement, but do not leave the old control socket.
                rig.runner.beginHandover(1, MgmtKey(rig.paths))
                System.`in`.read()
            }
        } finally {
            rig.runner.stop()
        }
    }
}
