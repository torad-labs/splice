// NEW: the splice CLI verbs modeled as a closed sealed hierarchy (was `object` singletons dispatched
// by a stringly-typed map). argv parses into a typed case (Install/Login carry their arg as data) and
// run() is total — adding a verb is a compile error until every site handles it.
package splice.app.cli

import kotlinx.coroutines.runBlocking
import splice.core.GATEWAY_VERSION
import splice.core.SHIM_VERSION

/** The splice CLI verbs as a closed, exhaustively-dispatched hierarchy: argv is parsed into a typed
 *  case (so args like the install target are data, not positional lookups), and run() is total —
 *  adding a verb is a compile error until every site handles it. Replaces the old string→object map. */
public sealed class Command {
    public abstract fun run(): Int

    public data object Doctor : Command() { override fun run(): Int = success { doctor() } }
    public data object Version : Command() {
        override fun run(): Int = success { println("splice $GATEWAY_VERSION") }
    }
    public data object ShimVersion : Command() { override fun run(): Int = success { println(SHIM_VERSION) } }
    public data object Init : Command() { override fun run(): Int = success { init() } }
    public data class Install(val target: String?) : Command() {
        override fun run(): Int = outcomeExitCode(install(target))
    }
    public data class Uninstall(val target: String?) : Command() {
        override fun run(): Int = outcomeExitCode(uninstall(target))
    }
    public data class Login(val head: String?) : Command() {
        override fun run(): Int = outcomeExitCode(runBlocking { login(head) })
    }
    public data object Setup : Command() {
        override fun run(): Int = outcomeExitCode(runBlocking { setup() })
    }
    public data object Status : Command() { override fun run(): Int = success { status() } }
    public data object Dashboard : Command() { override fun run(): Int = outcomeExitCode(dashboard()) }

    public companion object {
        // parse table (verb -> factory): a map keeps parse() at trivial complexity (no 10-arm `when`,
        // which would trip CyclomaticComplexMethod). The COMMANDS are the sealed type; this is just parsing.
        private val verbs: Map<String, (Array<String>) -> Command> = mapOf(
            "doctor" to { Doctor }, "version" to { Version }, "shim-version" to { ShimVersion },
            "init" to { Init },
            "install" to { a -> Install(a.getOrNull(1)) },
            "uninstall" to { a -> Uninstall(a.getOrNull(1)) },
            "login" to { a -> Login(a.getOrNull(1)) },
            "setup" to { Setup }, "status" to { Status }, "dashboard" to { Dashboard },
        )

        /** argv -> Command, or null for an unknown/empty verb (caller prints usage). */
        public fun parse(args: Array<String>): Command? = verbs[args.firstOrNull()]?.invoke(args)
    }
}

internal fun outcomeExitCode(ok: Boolean): Int = if (ok) 0 else 1

private inline fun success(block: () -> Unit): Int {
    block()
    return 0
}
