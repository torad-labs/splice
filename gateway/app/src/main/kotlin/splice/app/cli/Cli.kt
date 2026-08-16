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
        return command.run()
    }
}
