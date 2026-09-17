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
                "usage: splice [setup|add <profile>|upgrade|status|sessions|perf|restart|dashboard|" +
                    "login <head> [--label <name>]|key <set|list|unset>|logs [--head <key>] [--tail N] [--follow]|" +
                    "install|uninstall|init|doctor [--json [--with-logs] [--out FILE]]|daemon|version]",
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
     *  sanctioned higher-order shape.
     *
     *  Review 2026-09-17 (5): AddRefused was outside the set, so `splice add-model` refusing an
     *  unparseable or unrecognised roster printed a raw JVM stack trace at the operator. It is a
     *  REFUSAL, not a breakage: its own sentence is the whole explanation, so it renders verbatim
     *  rather than through SafeFailureText, which would withhold it. */
    internal inline fun guarded(block: () -> Int): Int = try {
        block()
    } catch (refused: AddRefused) {
        // SAFE-RENDER-EXEMPT[2026-09-17]: an AddRefused message is a sentence splice composed for
        // the operator, never upstream or file bytes; the one refusal built around a caught
        // throwable renders that cause through SafeFailureText before constructing this message
        // (AddModels.kt refuseUnparseable). The exemption ends the day one embeds a raw cause.
        System.err.println("splice: ${refused.message}")
        1
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
