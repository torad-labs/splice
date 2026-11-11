// NEW: splice CLI dispatch (P5-CLI grows this). Suspend all the way down: Main.kt's process entry is the
// one runBlocking that drives it. :app is exempt from no-println — a terminal tool writes to stdout.
// Verbs live in Command.kt; doctor's checks in DoctorCommand.kt.
package splice.app.cli

/** The CLI entry seam: argv in, process exit code out. A class rather than a top-level function
 *  (Kotlin style law, 2026-08-15); `fun main` in Main.kt stays top-level because the JVM entry
 *  point must be static, which the law exempts. The member keeps the old function's name. */
public class Cli {

    private val parser = CommandParser()

    public suspend fun runCli(args: Array<String>): Int {
        // `splice` alone, or asked for help by name: what it is and the commands to start from, as an answer and
        // never as a usage error. Until Nov 11, 2026 a bare `splice` started a second daemon on the person's
        // terminal, and `splice --help` printed the one-line usage as an error.
        val start = args.isEmpty() || args.first() in START_WORDS
        val help = if (start) "$START\n\n$USAGE" else parser.help(args)
        if (help != null) {
            println(help)
            return 0
        }
        val command = parser.parse(args) ?: run {
            System.err.println(USAGE)
            return 2
        }
        return guarded { command.run() }
    }

    /** DR-99: the CLI failure boundary. status/login/install/init/setup/logs had none — a
     *  malformed splice.toml escaped as a raw TomlDecodingException stack trace, and ktoml decode
     *  text can quote the offending config line, which legally carries credential-like
     *  extra_headers values (the DR-92 class). One line through SafeFailureText (DR-65:
     *  diagnostics never quote credential/config bytes), nonzero exit, no trace. The catch set is
     *  the topology-load failure surface: IO (file read), SerializationException (ktoml decode
     *  extends it, kotlinx json too), IllegalArgumentException (preflight/validation requires).
     *  Cancellation is untouched — not in the set. Inline with a `block` parameter, the
     *  sanctioned higher-order shape. */
    internal inline fun guarded(block: () -> Int): Int = try {
        block()
    } catch (broken: java.io.IOException) {
        if (repaired(broken)) afterFix(block) else 1
    } catch (broken: kotlinx.serialization.SerializationException) {
        if (repaired(broken)) afterFix(block) else 1
    } catch (broken: IllegalArgumentException) {
        if (repaired(broken)) afterFix(block) else 1
    }

    /** Prints why a verb failed, and for a splice.toml that cannot serve prints EVERY finding in it, then offers the
     *  fix session on a terminal. True when the person fixed the file, so the verb may run again. */
    internal fun repaired(broken: Throwable): Boolean {
        val offer = ConfigFixOffer()
        val refusal = offer.refusalOf(broken)
        renderFailure(refusal ?: broken)
        return refusal != null && offer.offer()
    }

    /** The verb once more after a fix session; a second failure is rendered, never offered a second session. */
    internal inline fun afterFix(block: () -> Int): Int = try {
        block()
    } catch (broken: java.io.IOException) {
        renderFailure(broken)
    } catch (broken: kotlinx.serialization.SerializationException) {
        renderFailure(broken)
    } catch (broken: IllegalArgumentException) {
        renderFailure(broken)
    }

    internal fun renderFailure(broken: Throwable): Int {
        System.err.println("splice: ${splice.core.util.SafeFailureText.render(broken)}")
        return 1
    }
}

/** Every verb: the answer to a command splice cannot read, and the end of the start screen. Broken into lines a
 *  terminal of 80 columns shows whole; as one line of 470 characters it wrapped in the middle of a word. */
private const val USAGE =
    "usage: splice [setup|add <profile>|add-model|models [provider]|upgrade|status|\n" +
        "  sessions [relaunch]|perf|wire <head> [--last N] [--json]|peer <verb>|\n" +
        "  share [on|off|status]|remote [add|remove]|restart [--now]|console [<action>]|\n" +
        "  trace <head> [--last N] [--session S] [--turn ID] [--json] [--purge]|\n" +
        "  mcp install temple [options]|login <head> [--label <name> [--discard]]|\n" +
        "  key <set|list|unset>|rotate-key|logs [--head <key>] [--tail N] [--follow]|\n" +
        "  install|uninstall|init|doctor [--json [--with-logs] [--out FILE]]|\n" +
        "  daemon|version]"

/** The words that ask for the start screen by name. */
private val START_WORDS = setOf("--help", "-h", "help")

/** The start screen: what splice is in a line, then the four commands a person begins with. */
private const val START =
    "splice runs Claude Code on the plans you already pay for.\n\n" +
        "  splice setup      connect a plan and install its command\n" +
        "  splice status     see what's running\n" +
        "  splice console    accounts, models and usage, in a browser\n" +
        "  splice doctor     anything wrong prints its fix"
