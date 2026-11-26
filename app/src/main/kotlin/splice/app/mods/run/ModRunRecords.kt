package splice.app.mods.run

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.app.mods.install.ModPrivateDir
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

@Serializable
private data class ModRunRecord(val pid: Long, val birth: String)

internal class ModRunRecords(modsDir: Path, private val log: LogSink) {
    private val directory = modsDir.resolve("run")

    fun save(name: String, handle: ProcessHandle) {
        val birth = handle.info().startInstant().orElse(null)
            ?: if (handle.isAlive) error("could not identify Bun's process birth") else return
        ModPrivateDir.create(directory)
        SecureFile.writeAtomic0600(file(name), Json.encodeToString(ModRunRecord(handle.pid(), birth.toString())))
    }

    fun clear(name: String) = clean(file(name))

    fun requireGone(name: String) {
        val path = file(name)
        if (Files.exists(path) && liveProcess(path)?.isAlive == true) {
            throw java.io.IOException("the previous Bun process is still alive")
        }
    }

    suspend fun reap(): Unit = coroutineScope {
        val files = Cancellables.runCatchingBestEffort {
            if (!Files.isDirectory(directory)) {
                emptyList()
            } else {
                Files.newDirectoryStream(directory, "*.json").use { it.toList() }
            }
        }.getOrElse {
            log("[mods] cannot read Bun process records: ${SafeFailureText.render(it)}\n")
            return@coroutineScope
        }
        for (path in files) launch { reap(path) }
    }

    private suspend fun reap(path: Path) {
        val handle = Cancellables.runCatchingBestEffort { liveProcess(path) }.getOrElse {
            log("[mods] cannot read ${path.fileName}: ${SafeFailureText.render(it)}\n")
            null
        }
        Cancellables.runCatchingBestEffort {
            if (handle != null) ModProcessTree(handle).terminate()
        }.onFailure {
            log("[mods] cannot stop Bun from ${path.fileName}: ${SafeFailureText.render(it)}\n")
        }
        if (handle?.isAlive != true) clean(path)
    }

    private fun liveProcess(path: Path): ProcessHandle? {
        val record = Json.decodeFromString<ModRunRecord>(Files.readString(path))
        if (record.pid == ProcessHandle.current().pid()) return null
        val birth = Instant.parse(record.birth)
        return ProcessHandle.of(record.pid).orElse(null)?.takeIf { it.info().startInstant().orElse(null) == birth }
    }

    private fun file(name: String): Path = directory.resolve("$name.json")

    private fun clean(path: Path) {
        Cancellables.runCatchingCancellable { Files.deleteIfExists(path) }.onFailure {
            log("[mods] cannot clear ${path.fileName}: ${SafeFailureText.render(it)}\n")
        }
    }
}
