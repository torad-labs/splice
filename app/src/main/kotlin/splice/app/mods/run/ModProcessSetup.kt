package splice.app.mods.run

import kotlinx.coroutines.CoroutineDispatcher
import splice.app.mods.install.InstalledMod
import splice.app.mods.install.ModBun
import splice.app.mods.install.ModPrivateDir
import splice.core.config.StatePaths
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.WallClock
import java.nio.file.Path

internal class ModProcessSetup(
    private val paths: StatePaths,
    daemonLog: LogSink,
    private val calls: ModCalls,
    val bun: ModBun,
    private val io: CoroutineDispatcher,
) {
    private val script = ModHostScript(paths.modsDir)
    private val env = ModChildEnv(paths.envReader)
    val records = ModRunRecords(paths.modsDir, daemonLog)

    fun launch(mod: InstalledMod, executable: Path, output: ModOutput): ModProcess {
        records.requireGone(mod.name)
        val data = paths.modsDir.toAbsolutePath().normalize().resolve("data").resolve(mod.name)
        ModPrivateDir.create(data)
        val command = env.command(mod, executable, script.install().toAbsolutePath().normalize(), data)
        val pipe = ModPipe(output.drops, output.log, output.clock)
        val child = ModProcessGroup.isolate(command).start()
        Cancellables.runCatchingCancellable { records.save(mod.name, child.toHandle(), grouped = true) }.onFailure {
            child.destroyForcibly()
        }.getOrThrow()
        return ModProcess(child, mod.name, calls, output, pipe, io)
    }
}

internal class ModOutput(paths: StatePaths, name: String, daemonLog: LogSink, val clock: WallClock) {
    val drops = ModDrops()
    val log = ModLog(paths.logsDir.resolve("mods").resolve("$name.log"), drops, clock, daemonLog)
}
