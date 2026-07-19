// NEW: `splice install/uninstall/init` (P5-CLI). install creates the wrapper command as an
// argv[0] symlink (~/.local/bin/<command> -> the shared splice-launch shim; ONE shim, N symlinks,
// zero per-head scripts) for each configured head, and materializes ~/.config/splice/splice.toml
// from jar defaults on init. :app is exempt from no-println — a terminal tool writes to stdout.
package splice.app.cli

import splice.app.TopologyLoader
import splice.core.SHIM_VERSION
import splice.core.config.InstallPaths
import splice.core.util.runCatchingCancellable
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isSymbolicLink

// SPLICE_BIN_DIR / SPLICE_SHARE_DIR honored via core/config (System.getenv is walled there),
// so `splice install` and install.sh always agree on where wrappers and the shim land. The env
// reader threads through every entry point (TopologyLoader.configPath idiom) so tests can pin a
// hermetic environment — the first real CI run failed on the runner's ambient XDG_CONFIG_HOME.
private fun localBin(env: (String) -> String?): Path = InstallPaths(envReader = env).binDir
private fun shareDir(env: (String) -> String?): Path = InstallPaths(envReader = env).shareDir
private fun launchShimPath(env: (String) -> String?): Path = shareDir(env).resolve("splice-launch")

internal fun init(env: (String) -> String? = System::getenv) {
    val path = TopologyLoader.configPath(env)
    val existed = Files.exists(path)
    TopologyLoader.loadOrMaterialize(path)
    println(if (existed) "splice: topology already at $path" else "splice: wrote starter topology to $path")
}

internal fun install(headArg: String?, env: (String) -> String? = System::getenv) {
    val topology = TopologyLoader.loadOrMaterialize(TopologyLoader.configPath(env))
    val launchShim = launchShimPath(env)
    if (!Files.exists(launchShim)) {
        println("splice: warning — launch shim not found at $launchShim (install.sh installs it)")
    }
    val bin = localBin(env)
    Files.createDirectories(bin)
    val heads = if (headArg == null || headArg == "--all") {
        topology.heads
    } else {
        topology.heads.filterKeys { it == headArg }
    }
    if (heads.isEmpty()) {
        println("splice: no matching head '$headArg' in the topology")
        return
    }
    for ((key, head) in heads) {
        linkOne(bin, key, head.claude.command ?: key, launchShim)
    }
    installSelf(env)
    println("splice: ensure $bin is on your PATH to use the wrappers")
}

/** Link the `splice` admin command itself (so `splice dashboard/status/...` work as commands). */
internal fun installSelf(env: (String) -> String? = System::getenv) {
    val launchShim = launchShimPath(env)
    val bin = localBin(env)
    Files.createDirectories(bin)
    linkOne(bin, "splice", "splice", launchShim)
}

private fun linkOne(bin: Path, headKey: String, command: String, launchShim: Path) {
    val link = bin.resolve(command)
    runCatchingCancellable {
        if (Files.exists(link, NOFOLLOW_LINKS)) {
            if (link.isSymbolicLink()) {
                Files.delete(link)
            } else {
                println("splice: $link exists and is not a symlink — leaving it")
                return
            }
        }
        Files.createSymbolicLink(link, launchShim)
        println("splice: installed '$command' -> $launchShim (head=$headKey)")
    }.onFailure { e ->
        println("splice: failed to link $command: ${e.message}")
    }
}

internal fun uninstall(headArg: String?, env: (String) -> String? = System::getenv) {
    val topology = runCatching { TopologyLoader.parse(Files.readString(TopologyLoader.configPath(env))) }.getOrNull()
    val commands = topology?.heads?.filterKeys { headArg == null || headArg == "--all" || it == headArg }
        ?.map { (k, h) -> h.claude.command ?: k } ?: listOfNotNull(headArg)
    val bin = localBin(env)
    for (command in commands) {
        val link = bin.resolve(command)
        runCatchingCancellable {
            if (link.isSymbolicLink()) {
                Files.delete(link)
                println("splice: removed '$command'")
            }
        }.onFailure { e ->
            println("splice: failed to remove $command: ${e.message}")
        }
    }
}

/** Copy the repo's launch shim into the share dir (used by install.sh / dev). */
internal fun installShim(repoShim: Path, env: (String) -> String? = System::getenv) {
    Files.createDirectories(shareDir(env))
    val dst = launchShimPath(env)
    runCatchingCancellable {
        Files.copy(repoShim, dst, StandardCopyOption.REPLACE_EXISTING)
        dst.toFile().setExecutable(true)
        println("splice: installed launch shim to $dst")
    }.onFailure { e ->
        println("splice: failed to install shim: ${e.message}")
    }
}

private val SHIM_VERSION_LINE = Regex("""^SPLICE_SHIM_VERSION="([^"]*)"""", RegexOption.MULTILINE)

/** The SPLICE_SHIM_VERSION marker embedded in the installed shim, or null if none/unreadable. */
internal fun installedShimVersion(env: (String) -> String? = System::getenv): String? {
    val shim = launchShimPath(env)
    if (!Files.exists(shim)) return null
    return runCatchingCancellable {
        SHIM_VERSION_LINE.find(Files.readString(shim))?.groupValues?.get(1)
    }.getOrNull()
}

/** Non-fatal staleness message for the installed shim, or null when absent/current. */
internal fun shimStalenessWarning(env: (String) -> String? = System::getenv): String? {
    val shim = launchShimPath(env)
    if (!Files.exists(shim)) return null
    val installed = installedShimVersion(env)
    if (installed == SHIM_VERSION) return null
    return "splice: WARNING — installed launch shim at $shim is STALE " +
        "(marker=${installed ?: "<missing>"}, expected=$SHIM_VERSION). " +
        "Run: splice install (or ./install.sh) to refresh it."
}
