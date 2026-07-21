// NEW: `splice install/uninstall/init` (P5-CLI). install creates the wrapper command as an
// argv[0] symlink (~/.local/bin/<command> -> the shared splice-launch shim; ONE shim, N symlinks,
// zero per-head scripts) for each configured head, and materializes ~/.config/splice/splice.toml
// from jar defaults on init. :app is exempt from no-println — a terminal tool writes to stdout.
package splice.app.cli

import splice.app.TopologyLoader
import splice.core.SHIM_VERSION
import splice.core.config.InstallPaths
import splice.core.topology.Topology
import splice.core.topology.ambiguousHeadMessage
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
private const val SELF_COMMAND = "splice"

internal fun init(env: (String) -> String? = System::getenv) {
    val path = TopologyLoader.configPath(env)
    val existed = Files.exists(path)
    TopologyLoader.loadOrMaterialize(path)
    println(if (existed) "splice: topology already at $path" else "splice: wrote starter topology to $path")
}

internal fun install(headArg: String?, env: (String) -> String? = System::getenv): Boolean {
    val topology = TopologyLoader.loadOrMaterialize(TopologyLoader.configPath(env))
    val launchShim = launchShimPath(env)
    check(Files.exists(launchShim)) { "launch shim not found at $launchShim (run install.sh)" }
    val bin = localBin(env)
    Files.createDirectories(bin)
    // All heads for --all/no-arg, else the one named by topology key OR wrapper command (a failed
    // resolution has already printed why — unknown vs ambiguous).
    val selected = if (headArg == null || headArg == "--all") {
        topology.heads
    } else {
        val key = resolveSpecificHead(topology, headArg) ?: return false
        topology.heads.filterKeys { it == key }
    }
    // Validate the WHOLE topology's commands (+ `splice`) on EVERY install, not just this
    // invocation — otherwise sequential single-head installs silently retarget an existing
    // wrapper symlink onto a command another head already owns.
    val commandOwners = topology.heads
        .map { (key, head) -> (head.claude.command ?: key) to key }
        .plus(SELF_COMMAND to SELF_COMMAND)
        .groupBy({ it.first }, { it.second })
    val collisions = commandOwners.filterValues { it.size > 1 }
    check(collisions.isEmpty()) {
        "topology maps multiple heads to one wrapper command: " +
            collisions.entries.joinToString("; ") { (command, keys) -> "$command <- ${keys.joinToString(", ")}" }
    }
    val requested = selected.map { (key, head) -> key to (head.claude.command ?: key) }
    val commands = requested.map { it.second } + SELF_COMMAND
    commands.forEach { command -> requireReplaceableLink(bin.resolve(command)) }
    requested.forEach { (key, command) -> linkOne(bin, key, command, launchShim) }
    linkOne(bin, SELF_COMMAND, SELF_COMMAND, launchShim)
    println("splice: ensure $bin is on your PATH to use the wrappers")
    return true
}

// Resolve a specific head arg to its single topology key, printing (and returning null) for an
// unknown name OR an ambiguous one — the latter distinct so the operator fixes the topology
// collision instead of chasing a phantom head. Shared by install and uninstall.
private fun resolveSpecificHead(topology: Topology, headArg: String): String? {
    val keys = topology.resolveHeadKeys(headArg)
    if (keys.size == 1) return keys.single()
    println(
        if (keys.isEmpty()) {
            "splice: no matching head '$headArg' in the topology"
        } else {
            "splice: " + ambiguousHeadMessage(headArg, keys)
        },
    )
    return null
}

/** Link the `splice` admin command itself (so `splice dashboard/status/...` work as commands). */
internal fun installSelf(env: (String) -> String? = System::getenv): Boolean {
    val launchShim = launchShimPath(env)
    check(Files.exists(launchShim)) { "launch shim not found at $launchShim (run install.sh)" }
    val bin = localBin(env)
    Files.createDirectories(bin)
    linkOne(bin, SELF_COMMAND, SELF_COMMAND, launchShim)
    return true
}

private fun linkOne(bin: Path, headKey: String, command: String, launchShim: Path) {
    val link = bin.resolve(command)
    requireReplaceableLink(link)
    val candidate = Files.createTempFile(bin, ".$command.", ".link")
    try {
        Files.delete(candidate)
        Files.createSymbolicLink(candidate, launchShim)
        Files.move(
            candidate,
            link,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        println("splice: installed '$command' -> $launchShim (head=$headKey)")
    } catch (e: java.io.IOException) {
        Files.deleteIfExists(candidate)
        throw IllegalStateException("failed to link $command: ${e.message}", e)
    }
}

private fun requireReplaceableLink(link: Path) {
    check(!Files.exists(link, NOFOLLOW_LINKS) || link.isSymbolicLink()) {
        "$link exists and is not a symlink"
    }
}

// Which wrapper commands a `splice uninstall [head]` removes: every head command (+ `splice`
// itself) for --all, else the one head named by topology key OR wrapper command. Returns null (and
// prints) for an unknown or ambiguous specific arg, so uninstall fails loudly instead of exiting 0
// with no output; a null/unreadable topology falls back to removing by the literal name typed.
private fun uninstallTargets(headArg: String?, env: (String) -> String?): List<String>? {
    val topology = runCatching { TopologyLoader.parse(Files.readString(TopologyLoader.configPath(env))) }.getOrNull()
    if (headArg == null || headArg == "--all") {
        val headCommands = topology?.heads?.map { (k, h) -> h.claude.command ?: k } ?: listOfNotNull(headArg)
        return (headCommands + SELF_COMMAND).distinct()
    }
    if (topology == null) return listOf(headArg)
    return resolveSpecificHead(topology, headArg)?.let { key ->
        listOf(topology.heads.getValue(key).claude.command ?: key)
    }
}

internal fun uninstall(headArg: String?, env: (String) -> String? = System::getenv): Boolean {
    val commands = uninstallTargets(headArg, env) ?: return false
    val bin = localBin(env)
    var ok = true
    for (command in commands) {
        val link = bin.resolve(command)
        runCatchingCancellable {
            if (link.isSymbolicLink()) {
                Files.delete(link)
                println("splice: removed '$command'")
            }
        }.onFailure { e ->
            ok = false
            println("splice: failed to remove $command: ${e.message}")
        }
    }
    return ok
}

/** Copy the repo's launch shim into the share dir (used by install.sh / dev). */
internal fun installShim(repoShim: Path, env: (String) -> String? = System::getenv) {
    Files.createDirectories(shareDir(env))
    val dst = launchShimPath(env)
    Files.copy(repoShim, dst, StandardCopyOption.REPLACE_EXISTING)
    check(dst.toFile().setExecutable(true)) { "failed to make launch shim executable: $dst" }
    println("splice: installed launch shim to $dst")
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
