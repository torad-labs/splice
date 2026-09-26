// NEW: argv -> Command, split out of install/InstallCommand.kt when the install verbs moved to
// features/launch (LAYOUT-01). The parse table is dispatch, which is app composition; it only ever
// sat beside Install because that verb owned the argv-shaped cases.
package splice.app.cli

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

    // V4-309: `splice restart --help` restarted the live daemon (Sep 26, 7:42 AM CT), because the arm
    // kept the one word it knew and dropped the rest. Every arm takes only the words its verb names;
    // any other word, a help flag included, is null, so usage prints and nothing runs.

    /** A verb that names no words parses alone; any word after it is refused. */
    class Alone(private val command: Command) : CommandFactory {
        override fun invoke(args: Array<String>): Command? = command.takeIf { args.size == 1 }
    }

    /** `install|uninstall [<head>|--all]` name one word, a head or --all. A flag is never a head. */
    class OneHead(private val command: CommandFactory) : CommandFactory {
        override fun invoke(args: Array<String>): Command? {
            val head = args.getOrNull(1)
            val named = args.size <= 2 && (head == null || head == EVERY_HEAD_FLAG || !head.startsWith("-"))
            return if (named) command(args) else null
        }
    }
}

// FILE SCOPE ON PURPOSE: the parse table (verb -> factory) is built ONCE for the process rather than
// per parse. A map keeps parse() at trivial complexity (no 10-arm `when`, which would trip
// CyclomaticComplexMethod). The COMMANDS are the sealed type; this is just parsing.
private const val LABEL_FLAG = "--label"
private const val DISCARD_FLAG = "--discard"

/** `install|uninstall --all`: every head (InstallLinker, UninstallCommand), not models' --all. */
private const val EVERY_HEAD_FLAG = "--all"

private val verbs: Map<String, CommandFactory> = mapOf(
    "doctor" to CommandFactory { a -> Command.Doctor(a.drop(1)) },
    "version" to CommandFactory.Alone(Command.Version),
    "shim-version" to CommandFactory.Alone(Command.ShimVersion),
    "init" to CommandFactory.Alone(Command.Init),
    "install" to CommandFactory.OneHead { a -> Command.Install(a.getOrNull(1)) },
    "uninstall" to CommandFactory.OneHead { a -> Command.Uninstall(a.getOrNull(1)) },
    // `login <head> [--label <name>] [--discard]` (v0.4.0, FEATURES.md §11): the value after the flag,
    // wherever it sits. --discard (V4-276) takes no value and only means something with --label.
    "login" to CommandFactory { a ->
        val flag = a.indexOf(LABEL_FLAG)
        val label = if (flag > 0) a.getOrNull(flag + 1) else null
        val discard = DISCARD_FLAG in a
        val positional = a.filterIndexed { i, word ->
            i > 0 && word != DISCARD_FLAG && (flag < 1 || i != flag && i != flag + 1)
        }
        // A bare --label, a second --label or a second positional is a mistake, not an unlabeled
        // login: the parse fails and usage prints, nothing is silently dropped. So is --discard
        // without --label, or twice.
        val malformed = flag > 0 && label == null || a.count { it == LABEL_FLAG } > 1 || positional.size > 1 ||
            discard && (label == null || a.count { it == DISCARD_FLAG } > 1)
        if (malformed) null else Command.Login(positional.firstOrNull(), label, discard)
    },
    "setup" to CommandFactory.Alone(Command.Setup),
    "add" to CommandFactory { a -> Command.Add(a.drop(1)) },
    // V4-34 shipped AddModelVerb and Command.AddModel but never reached this table, so `splice
    // add-model` did not parse and the feature was unreachable from argv — the tests construct the
    // verb directly, which is exactly the gap a parse table can hide. Wired 2026-09-16.
    "add-model" to CommandFactory.Alone(Command.AddModel),
    // 2026-09-22: `splice models [provider]` — the endpoint's own roster beside the declared one.
    "models" to CommandFactory { a -> Command.Models(a.drop(1)) },
    "upgrade" to CommandFactory { a -> Command.Upgrade(a.drop(1)) },
    "status" to CommandFactory.Alone(Command.Status),
    "restart" to CommandFactory { a ->
        when (a.drop(1)) {
            emptyList<String>() -> Command.Restart()
            listOf("--now") -> Command.Restart(now = true)
            else -> null
        }
    },
    "dashboard" to CommandFactory.Alone(Command.Dashboard),
    "key" to CommandFactory { a -> Command.Key(a.drop(1)) },
    "logs" to CommandFactory { a -> Command.Logs(a.drop(1)) },
    "sessions" to CommandFactory.Alone(Command.Sessions),
    "perf" to CommandFactory { a -> Command.Perf(a.drop(1)) },
    "wire" to CommandFactory { a -> Command.Wire(a.drop(1)) },
    "trace" to CommandFactory { a -> Command.Trace(a.drop(1)) },
)

/** argv -> Command. Was `Command.parse` on the type's own static block — the shape the same
 *  2026-08-15 style law bans — so the parse seam becomes its own tiny collaborator and the table it
 *  reads stays a file-scope val. */
internal class CommandParser {

    /** argv -> Command, or null for an unknown/empty verb (caller prints usage). */
    internal fun parse(args: Array<String>): Command? = verbs[args.firstOrNull()]?.invoke(args)
}
