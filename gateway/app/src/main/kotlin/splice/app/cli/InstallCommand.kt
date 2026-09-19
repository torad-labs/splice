// NEW: `splice install/uninstall/init` (P5-CLI) facade. Bodies live in InstallLinker /
// UninstallCommand / InstallShim / InstallHeads / InstallLayout so this file is not billed
// as a god object (concentration HIGH, 2026-08-19). Public methods keep their names so
// Command.kt / InstallCommandTest / DoctorInstallProbes / Main.kt do not change.
package splice.app.cli

import splice.app.TopologyLoader
import splice.core.util.EnvReader
import java.nio.file.Files

/** The `install` / `uninstall` / `init` verbs as one cohesive unit of behavior (Kotlin style law,
 *  2026-08-15: main sources carry no top-level functions) — they share the wrapper-symlink and
 *  launch-shim path resolution below. Every member keeps the old function's name, so each call
 *  site's diff is a receiver insertion. */
internal class InstallCommand {

    private val linker = InstallLinker()
    private val uninstaller = UninstallCommand()
    private val shim = InstallShim()

    internal fun init(env: EnvReader = EnvReader(System::getenv)) {
        val path = TopologyLoader.configPath(env)
        val existed = Files.exists(path)
        TopologyLoader.loadOrMaterialize(path)
        println(if (existed) "splice: topology already at $path" else "splice: wrote starter topology to $path")
    }

    internal fun install(headArg: String?, env: EnvReader = EnvReader(System::getenv)): Boolean =
        linker.install(headArg, env)

    internal fun installSelf(env: EnvReader = EnvReader(System::getenv)): Boolean =
        linker.installSelf(env)

    internal fun uninstall(headArg: String?, env: EnvReader = EnvReader(System::getenv)): Boolean =
        uninstaller.uninstall(headArg, env)

    internal fun installedShimVersion(env: EnvReader = EnvReader(System::getenv)): String? =
        shim.installedShimVersion(env)

    internal fun shimStalenessWarning(env: EnvReader = EnvReader(System::getenv)): String? =
        shim.shimStalenessWarning(env)
}

/**
 * Builds one [Command] from the full argv — the value half of the verb table.
 *
 * It is handed the WHOLE `args` array, not the tail, which is the contract the raw shape hid: every
 * arm indexes from 1 (`a.getOrNull(1)`, `a.drop(1)`) because element 0 is the verb that selected it.
 * An arm written against a pre-stripped array would silently drop its first real argument.
 *
 * Named in HD-22 wave 4b, and the LAST seam the wave closed: it sits inside a generic type argument,
 * which the dormant rule's `type_projection` carve-out exempted. That carve-out was dropped at
 * promotion — measured, it was masking exactly this one declaration and nothing else.
 */
internal fun interface CommandFactory {
    /** Null when the verb's own arguments do not parse (the caller prints usage). */
    operator fun invoke(args: Array<String>): Command?
}

// FILE SCOPE ON PURPOSE: the parse table (verb -> factory) is built ONCE for the process rather than
// per parse. A map keeps parse() at trivial complexity (no 10-arm `when`, which would trip
// CyclomaticComplexMethod). The COMMANDS are the sealed type; this is just parsing.
private const val LABEL_FLAG = "--label"

private val verbs: Map<String, CommandFactory> = mapOf(
    "doctor" to CommandFactory { a -> Command.Doctor(a.drop(1)) },
    "version" to CommandFactory { Command.Version },
    "shim-version" to CommandFactory { Command.ShimVersion },
    "init" to CommandFactory { Command.Init },
    "install" to CommandFactory { a -> Command.Install(a.getOrNull(1)) },
    "uninstall" to CommandFactory { a -> Command.Uninstall(a.getOrNull(1)) },
    // `login <head> [--label <name>]` (v0.4.0, FEATURES.md §11): the value after the flag, wherever it sits.
    "login" to CommandFactory { a ->
        val flag = a.indexOf(LABEL_FLAG)
        val label = if (flag > 0) a.getOrNull(flag + 1) else null
        val positional = a.filterIndexed { i, _ -> i > 0 && (flag < 1 || i != flag && i != flag + 1) }
        // A bare --label, a second --label or a second positional is a mistake, not an unlabeled
        // login: the parse fails and usage prints, nothing is silently dropped.
        val malformed = flag > 0 && label == null || a.count { it == LABEL_FLAG } > 1 || positional.size > 1
        if (malformed) null else Command.Login(positional.firstOrNull(), label)
    },
    "setup" to CommandFactory { Command.Setup },
    "add" to CommandFactory { a -> Command.Add(a.drop(1)) },
    // V4-34 shipped AddModelVerb and Command.AddModel but never reached this table, so `splice
    // add-model` did not parse and the feature was unreachable from argv — the tests construct the
    // verb directly, which is exactly the gap a parse table can hide. Wired 2026-09-16.
    "add-model" to CommandFactory { a -> Command.AddModel(a.drop(1)) },
    "upgrade" to CommandFactory { a -> Command.Upgrade(a.drop(1)) },
    "status" to CommandFactory { Command.Status },
    "restart" to CommandFactory { Command.Restart },
    "dashboard" to CommandFactory { Command.Dashboard },
    "key" to CommandFactory { a -> Command.Key(a.drop(1)) },
    "logs" to CommandFactory { a -> Command.Logs(a.drop(1)) },
    "sessions" to CommandFactory { Command.Sessions },
    "perf" to CommandFactory { a -> Command.Perf(a.drop(1)) },
)

/** argv -> Command. Was `Command.parse` on the type's own static block — the shape the same
 *  2026-08-15 style law bans — so the parse seam becomes its own tiny collaborator and the table it
 *  reads stays a file-scope val. Lives next to Install because that verb already owns the
 *  argv-shaped cases (Install/Uninstall targets). */
internal class CommandParser {

    /** argv -> Command, or null for an unknown/empty verb (caller prints usage). */
    internal fun parse(args: Array<String>): Command? = verbs[args.firstOrNull()]?.invoke(args)
}
