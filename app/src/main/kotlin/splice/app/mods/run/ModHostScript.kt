package splice.app.mods.run

import splice.app.mods.install.ModPrivateDir
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Path

internal class ModHostScript(modsDir: Path) {
    private val file = modsDir.resolve("host/host.ts")
    private val installed = modsDir.resolve("installed")
    private var ready = false

    /**
     * One extraction per daemon with a runnable mod, before any mod code executes. It also writes an empty
     * `tsconfig.json` above every installed copy: Bun reads the nearest one, and without it a file in some parent
     * of the state directory could alias a mod's imports to code Splice never hashed.
     */
    @Synchronized
    fun install(): Path {
        if (!ready) {
            ModPrivateDir.create(file.parent)
            ModPrivateDir.create(installed)
            extract(file)
            extract(file.resolveSibling("guardian.ts"))
            SecureFile.writeAtomic0600(installed.resolve("tsconfig.json"), "{}\n")
            ready = true
        }
        return file
    }

    private fun extract(target: Path) {
        val resource = javaClass.classLoader.getResourceAsStream("splice/mods/${target.fileName}")
            ?: throw IOException("the packaged mod file ${target.fileName} is missing")
        SecureFile.writeAtomic0600(target, resource.use { it.readBytes() })
    }
}
