// NEW: `splice install/uninstall/init` (P5-CLI). install creates the wrapper command as an
// argv[0] symlink (~/.local/bin/<command> -> the shared splice-launch shim; ONE shim, N symlinks,
// zero per-head scripts) for each configured head, and materializes ~/.config/splice/splice.toml
// from jar defaults on init. :app is exempt from no-println — a terminal tool writes to stdout.
package splice.app.cli

import splice.app.TopologyLoader
import splice.core.util.runCatchingCancellable
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.isSymbolicLink

private fun home(): Path = Paths.get(System.getProperty("user.home"))
private fun localBin(): Path = home().resolve(".local").resolve("bin")
private fun shareDir(): Path = home().resolve(".local").resolve("share").resolve("splice")

internal fun init() {
    val path = TopologyLoader.configPath()
    val existed = Files.exists(path)
    TopologyLoader.loadOrMaterialize(path)
    println(if (existed) "splice: topology already at $path" else "splice: wrote starter topology to $path")
}

internal fun install(headArg: String?) {
    val topology = TopologyLoader.loadOrMaterialize(TopologyLoader.configPath())
    val launchShim = shareDir().resolve("splice-launch")
    if (!Files.exists(launchShim)) {
        println("splice: warning — launch shim not found at $launchShim (install.sh installs it)")
    }
    Files.createDirectories(localBin())
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
        linkOne(key, head.claude.command ?: key, launchShim)
    }
    installSelf()
    println("splice: ensure ${localBin()} is on your PATH to use the wrappers")
}

/** Link the `splice` admin command itself (so `splice dashboard/status/...` work as commands). */
internal fun installSelf() {
    val launchShim = shareDir().resolve("splice-launch")
    Files.createDirectories(localBin())
    linkOne("splice", "splice", launchShim)
}

private fun linkOne(headKey: String, command: String, launchShim: Path) {
    val link = localBin().resolve(command)
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

internal fun uninstall(headArg: String?) {
    val topology = runCatching { TopologyLoader.parse(Files.readString(TopologyLoader.configPath())) }.getOrNull()
    val commands = topology?.heads?.filterKeys { headArg == null || headArg == "--all" || it == headArg }
        ?.map { (k, h) -> h.claude.command ?: k } ?: listOfNotNull(headArg)
    for (command in commands) {
        val link = localBin().resolve(command)
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
internal fun installShim(repoShim: Path) {
    Files.createDirectories(shareDir())
    val dst = shareDir().resolve("splice-launch")
    runCatchingCancellable {
        Files.copy(repoShim, dst, StandardCopyOption.REPLACE_EXISTING)
        dst.toFile().setExecutable(true)
        println("splice: installed launch shim to $dst")
    }.onFailure { e ->
        println("splice: failed to install shim: ${e.message}")
    }
}
