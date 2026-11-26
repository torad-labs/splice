package splice.app.mods.run

import splice.app.mods.install.ModPrivateDir
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Path

internal class ModHostScript(modsDir: Path) {
    private val file = modsDir.resolve("host/host.ts")
    private var installed = false

    /** One extraction per daemon with a runnable mod, before any mod code executes. */
    @Synchronized
    fun install(): Path {
        if (!installed) {
            val resource = javaClass.classLoader.getResourceAsStream("splice/mods/host.ts")
                ?: throw IOException("the mod host is missing from this Splice build")
            val bytes = resource.use { it.readBytes() }
            ModPrivateDir.create(file.parent)
            SecureFile.writeAtomic0600(file, bytes)
            installed = true
        }
        return file
    }
}
