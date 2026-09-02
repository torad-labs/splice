// NEW: splice CLI dispatch (P5-CLI grows this). In cli/ so the walls exempt its runBlocking use
// (admin one-shots, not daemon hot path). :app is exempt from no-println — a terminal tool writes
// to stdout. Verbs live in Command.kt; doctor's checks in DoctorCommand.kt.
package splice.app.cli

/** The CLI entry seam: argv in, process exit code out. A class rather than a top-level function
 *  (Kotlin style law, 2026-08-15); `fun main` in Main.kt stays top-level because the JVM entry
 *  point must be static, which the law exempts. The member keeps the old function's name. */
public class Cli {

    private val parser = CommandParser()

    public fun runCli(args: Array<String>): Int {
        val command = parser.parse(args) ?: run {
            System.err.println(
                "usage: splice [setup|status|restart|dashboard|login <head>|key <set|list|unset>|" +
                    "logs [--head <key>] [--tail N] [--follow]|install|uninstall|init|doctor|daemon|version]",
            )
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
