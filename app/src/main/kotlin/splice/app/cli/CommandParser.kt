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

// record-launch names its verb, PID, head, base URL, kind and origin, in that wire order.
private const val OWNER_ARG_COUNT = 6

// Kind follows the verb, PID, head and base URL.
private const val OWNER_KIND_INDEX = 4

// Origin is the last word of the six-word declaration.
private const val OWNER_ORIGIN_INDEX = 5

// pending-login requires its verb, a jar path and at least one head word.
private const val PENDING_MIN_ARGS = 3

/** `install|uninstall --all`: every head (InstallLinker, UninstallCommand), not models' --all. */
private const val EVERY_HEAD_FLAG = "--all"

private val helpFlags = setOf("--help", "-h")

/** Each table entry owns its arguments and factory; help never reaches that factory. */
private class CommandRegistration(val usage: String, private val factory: CommandFactory) : CommandFactory {
    override fun invoke(args: Array<String>): Command? =
        if (args.any { it in helpFlags }) null else factory(args)
}

private val verbs: Map<String, CommandRegistration> = mapOf(
    "doctor" to CommandRegistration(
        "[--json [--with-logs] [--out FILE]]", CommandFactory { a -> Command.Doctor(a.drop(1)) },
    ),
    "version" to CommandRegistration("", CommandFactory.Alone(Command.Version)),
    "record-launch" to CommandRegistration(
        "<pid> <head> <base-url> <session|login> <hook|other>",
        CommandFactory { a ->
            val valid = a.size == OWNER_ARG_COUNT && (a[1].toLongOrNull() ?: 0) > 0 &&
                a[2].isNotBlank() && a[OWNER_KIND_INDEX] in setOf("session", "login") &&
                a[OWNER_ORIGIN_INDEX] in setOf("hook", "other")
            if (valid) LaunchOwnerCommand(a.drop(1)) else null
        },
    ),
    "pending-login" to CommandRegistration(
        "<jar> <head>...",
        CommandFactory { a ->
            val valid = a.size >= PENDING_MIN_ARGS && a.drop(1).all { it.isNotBlank() }
            if (valid) PendingLoginCommand(a.drop(1)) else null
        },
    ),
    "shim-version" to CommandRegistration("", CommandFactory.Alone(Command.ShimVersion)),
    "init" to CommandRegistration("", CommandFactory.Alone(Command.Init)),
    "install" to CommandRegistration("[<head>|--all]", CommandFactory.OneHead { a -> Command.Install(a.getOrNull(1)) }),
    "uninstall" to CommandRegistration(
        "[<head>|--all]", CommandFactory.OneHead { a -> Command.Uninstall(a.getOrNull(1)) },
    ),
    // `login <head> [--label <name>] [--discard]` (v0.4.0, FEATURES.md §11): the value after the flag,
    // wherever it sits. --discard (V4-276) takes no value and only means something with --label.
    "login" to CommandRegistration(
        "<head> [--label <name> [--discard]]",
        CommandFactory { a ->
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
    ),
    "setup" to CommandRegistration("", CommandFactory.Alone(Command.Setup)),
    "add" to CommandRegistration(
        "<profile> [--name NAME] [--base-url URL] [--model ID]... [--command CMD] [--live] [--yes]",
        CommandFactory { a -> Command.Add(a.drop(1)) },
    ),
    // V4-34 shipped AddModelVerb and Command.AddModel but never reached this table, so `splice
    // add-model` did not parse and the feature was unreachable from argv — the tests construct the
    // verb directly, which is exactly the gap a parse table can hide. Wired 2026-09-16.
    "add-model" to CommandRegistration("", CommandFactory.Alone(Command.AddModel)),
    // 2026-09-22: `splice models [provider]` — the endpoint's own roster beside the declared one.
    "models" to CommandRegistration("[provider] [--all]", CommandFactory { a -> Command.Models(a.drop(1)) }),
    "upgrade" to CommandRegistration(
        "[--to vX.Y.Z] [--now] [--rollback]", CommandFactory { a -> Command.Upgrade(a.drop(1)) },
    ),
    "status" to CommandRegistration("", CommandFactory.Alone(Command.Status)),
    "restart" to CommandRegistration(
        "[--now]",
        CommandFactory { a ->
            when (a.drop(1)) {
                emptyList<String>() -> Command.Restart()
                listOf("--now") -> Command.Restart(now = true)
                else -> null
            }
        },
    ),
    "dashboard" to CommandRegistration("", CommandFactory.Alone(Command.Dashboard)),
    "key" to CommandRegistration(
        "set <ENV_NAME> [--value V | --stdin] | list | unset <ENV_NAME>",
        CommandFactory { a -> Command.Key(a.drop(1)) },
    ),
    "logs" to CommandRegistration(
        "[--head <key>] [--tail N] [--follow]", CommandFactory { a -> Command.Logs(a.drop(1)) },
    ),
    "sessions" to CommandRegistration("", CommandFactory.Alone(Command.Sessions)),
    "perf" to CommandRegistration("[--window 1h|24h|7d]", CommandFactory { a -> Command.Perf(a.drop(1)) }),
    "wire" to CommandRegistration(
        "<head> [--last N] [--json]", CommandFactory { a -> Command.Wire(a.drop(1)) },
    ),
    "trace" to CommandRegistration(
        "<head> [--last N] [--session S] [--turn ID] [--json] [--purge]",
        CommandFactory { a -> Command.Trace(a.drop(1)) },
    ),
)

/** argv -> Command. Was `Command.parse` on the type's own static block — the shape the same
 *  2026-08-15 style law bans — so the parse seam becomes its own tiny collaborator and the table it
 *  reads stays a file-scope val. */
internal class CommandParser {

    /** The table's own registrations, including internal verbs; no parallel coverage list. */
    internal val registeredVerbs: Set<String> get() = verbs.keys

    /** Explicit help is usage for the selected registration, never a parsed command. */
    internal fun help(args: Array<String>): String? {
        val registration = verbs[args.firstOrNull()] ?: return null
        if (args.none { it in helpFlags }) return null
        val prefix = "usage: splice ${args.first()}"
        return if (registration.usage.isEmpty()) prefix else "$prefix ${registration.usage}"
    }

    /** argv -> Command, or null for an unknown/empty verb (caller prints usage). */
    internal fun parse(args: Array<String>): Command? = verbs[args.firstOrNull()]?.invoke(args)
}
