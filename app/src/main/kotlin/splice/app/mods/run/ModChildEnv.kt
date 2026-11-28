package splice.app.mods.run

import splice.app.mods.install.InstalledMod
import splice.core.util.EnvReader
import java.io.File
import java.nio.file.Path

/** Build rather than edit the inherited environment. Bun must not repopulate it from a dotenv file. */
internal class ModChildEnv(private val env: EnvReader) {
    fun command(mod: InstalledMod, bun: Path, host: Path, data: Path): ProcessBuilder =
        ProcessBuilder(bun.toString(), "--config=/dev/null", "--no-env-file", "--no-install", host.toString())
            .directory(mod.root.toFile())
            .apply {
                environment().clear()
                environment().putAll(values(mod, bun, data))
            }

    private fun values(mod: InstalledMod, bun: Path, data: Path): Map<String, String> = buildMap {
        for (key in listOf("HOME", "LANG", "TMPDIR")) env(key)?.let { put(key, it) }
        val path = listOfNotNull(bun.parent.toString(), env("PATH")?.takeIf(String::isNotBlank))
        put("PATH", path.joinToString(File.pathSeparator))
        put("SPLICE_MOD_NAME", mod.name)
        put("SPLICE_MOD_ROOT", mod.root.toString())
        put("SPLICE_MOD_DATA", data.toString())
    }
}
