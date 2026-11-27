package splice.app.mods.run

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import splice.app.mods.install.InstalledMod
import splice.app.mods.install.ModBun
import splice.app.mods.install.ModPrivateDir
import splice.core.config.StatePaths
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.WallClock
import java.io.IOException
import java.nio.file.Path

internal class ModProcessSetup(
    private val paths: StatePaths,
    log: LogSink,
    private val calls: ModCalls,
    val bun: ModBun,
    private val io: CoroutineDispatcher,
) {
    private val script = ModHostScript(paths.modsDir)
    private val env = ModChildEnv(paths.envReader)
    val records = ModRunRecords(paths.modsDir, log)

    fun launch(
        mod: InstalledMod,
        executable: Path,
        output: ModOutput,
        pipe: ModPipe,
        stop: Deferred<String>,
    ): ModProcess? {
        if (!records.supported) throw IOException("mods run on Linux and macOS only")
        val data = paths.modsDir.toAbsolutePath().normalize().resolve("data").resolve(mod.name)
        ModPrivateDir.create(data)
        val host = script.install().toAbsolutePath().normalize()
        val command = ModProcessGroup.isolate(env.command(mod, executable, host, data), host.resolveSibling("guard.pl"))
        val turn: ModStartTurn = records.turn(mod.name)
        return turn.use {
            records.requireGone(mod.name)
            if (stop.isCompleted) null else spawn(mod, command, output, pipe)
        }
    }

    private fun spawn(mod: InstalledMod, command: ProcessBuilder, output: ModOutput, pipe: ModPipe): ModProcess {
        val child = command.start()
        val owner = Cancellables.runCatchingBestEffort {
            val identity = ModProcessIdentity.of(child.toHandle()) ?: error("could not identify the mod guardian")
            records.begin(mod.name, identity).also { ModProcessGroup.admit(child) }
        }.onFailure {
            // EOF before the admission byte releases the guardian without running mod code.
            child.outputStream.close()
        }.getOrThrow()
        return ModProcess(child, mod.name, calls, output, pipe, io, records.tree(owner))
    }
}

internal class ModOutput(paths: StatePaths, name: String, daemonLog: LogSink, val clock: WallClock) {
    val drops = ModDrops()
    val log = ModLog(paths.logsDir.resolve("mods").resolve("$name.log"), drops, clock, daemonLog)
}
