// NEW: the splice CLI verbs modeled as a closed sealed hierarchy (was `object` singletons dispatched
// by a stringly-typed map). argv parses into a typed case (Install/Login carry their arg as data) and
// run() is total — adding a verb is a compile error until every site handles it.
package splice.app.cli

import kotlinx.coroutines.runBlocking
import splice.app.AddWiring
import splice.app.DoctorWiring
import splice.app.InstallWiring
import splice.app.LifecycleWiring
import splice.app.ModelsWiring
import splice.app.PerfWiring
import splice.app.TraceWiring
import splice.app.cli.auth.KeyCommand
import splice.app.cli.auth.LoginCommand
import splice.app.cli.setup.SetupCommand
import splice.app.cli.status.DashboardCommand
import splice.app.cli.status.StatusCommand
import splice.core.GATEWAY_VERSION
import splice.core.SHIM_VERSION
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.diagnostics.logs.LogsCommand
import splice.diagnostics.wire.WireCommand
import splice.lifecycle.upgrade.UpgradeVerb
import splice.lifecycle.upgrade.VersionedRestart
import splice.sessions.list.SessionsCommand
import splice.topology.TopologyLoader

/** The splice CLI verbs as a closed, exhaustively-dispatched hierarchy: argv is parsed into a typed
 *  case (so args like the install target are data, not positional lookups), and run() is total —
 *  adding a verb is a compile error until every site handles it. Replaces the old string→object map.
 *
 *  Each case constructs its verb's class and calls the member that used to be a top-level function
 *  of the same name (Kotlin style law, 2026-08-15: main sources carry no top-level functions), so
 *  every arm below is the old line plus a receiver. */
public sealed class Command {
    public abstract fun run(): Int

    public data class Doctor(val args: List<String> = emptyList()) : Command() {
        override fun run(): Int = outcomeExitCode(DoctorWiring.command().doctor(args, EnvReader(System::getenv)))
    }
    public data object Version : Command() {
        override fun run(): Int = success { println("splice $GATEWAY_VERSION") }
    }
    public data object ShimVersion : Command() { override fun run(): Int = success { println(SHIM_VERSION) } }
    public data object Init : Command() {
        override fun run(): Int = success { InstallWiring.init(EnvReader(System::getenv)) }
    }
    public data class Install(val target: String?) : Command() {
        override fun run(): Int = outcomeExitCode(InstallWiring.command().install(target, EnvReader(System::getenv)))
    }
    public data class Uninstall(val target: String?) : Command() {
        override fun run(): Int = outcomeExitCode(InstallWiring.command().uninstall(target, EnvReader(System::getenv)))
    }

    /** v0.4.0 (FEATURES.md §11): `--label <name>` signs in a further account of the head's kind. */
    public data class Login(val head: String?, val label: String? = null) : Command() {
        override fun run(): Int = outcomeExitCode(runBlocking { LoginCommand().login(head, label) })
    }
    public data object Setup : Command() {
        override fun run(): Int = outcomeExitCode(runBlocking { SetupCommand().setup() })
    }

    /** v0.4.0 (FEATURES.md §1): `splice add <profile> [...]` — a second provider without editing TOML. */
    public data class Add(val args: List<String>) : Command() {
        override fun run(): Int = outcomeExitCode(runBlocking { AddWiring.add().add(args, EnvReader(System::getenv)) })
    }

    /** V4-34: `splice add-model` — pick OpenRouter catalog rows through the prompt toolkit. */
    public data class AddModel(val args: List<String> = emptyList()) : Command() {
        override fun run(): Int = outcomeExitCode(AddWiring.addModel().add(TopologyLoader.configPath()))
    }

    /** 2026-09-22: `splice models [provider]` — what each provider publishes, against splice.toml.
     *  Nonzero when a declared row is over a published ceiling or is no longer served. */
    public data class Models(val args: List<String> = emptyList()) : Command() {
        override fun run(): Int = outcomeExitCode(ModelsWiring.run(args))
    }

    /** v0.4.0 (FEATURES.md §5): `splice upgrade [--to vX] [--now] [--rollback]`. */
    public data class Upgrade(val args: List<String>) : Command() {
        override fun run(): Int {
            val verb = UpgradeVerb(
                TerminalOutput(::println),
                TerminalOutput(System.err::println),
                EnvReader(System::getenv),
                VersionedRestart { LifecycleWiring.restart(it) },
            )
            return outcomeExitCode(verb.upgrade(args))
        }
    }

    public data object Status : Command() { override fun run(): Int = success { StatusCommand().status() } }
    public data object Restart : Command() { override fun run(): Int = outcomeExitCode(LifecycleWiring.restart()) }
    public data object Dashboard : Command() {
        override fun run(): Int = outcomeExitCode(DashboardCommand().dashboard())
    }
    public data class Key(val args: List<String>) : Command() {
        override fun run(): Int = outcomeExitCode(KeyCommand().key(args))
    }
    public data class Logs(val args: List<String>) : Command() {
        override fun run(): Int {
            val verb = LogsCommand(TerminalOutput(::println), TerminalOutput(System.err::println))
            return outcomeExitCode(verb.logs(args, EnvReader(System::getenv)))
        }
    }
    public data object Sessions : Command() {
        override fun run(): Int {
            val verb = SessionsCommand(TerminalOutput(::println), TerminalOutput(System.err::println))
            return outcomeExitCode(verb.sessions(EnvReader(System::getenv)))
        }
    }
    public data class Perf(val args: List<String>) : Command() {
        override fun run(): Int = outcomeExitCode(PerfWiring.command().perf(args, EnvReader(System::getenv)))
    }

    /** V4-173: the upstream request bodies a head sent, when its operator opted in. */
    public data class Wire(val args: List<String>) : Command() {
        override fun run(): Int {
            val verb = WireCommand(TerminalOutput(::println), TerminalOutput(System.err::println))
            return outcomeExitCode(verb.wire(args, EnvReader(System::getenv)))
        }
    }

    /** V4-174: a head's full request/response trace, from its day files, when its operator opted in. */
    public data class Trace(val args: List<String>) : Command() {
        override fun run(): Int = outcomeExitCode(TraceWiring.run(args))
    }

    /** Verb outcome -> process exit code. Inherited by every case above, which is why each `run()`
     *  arm still calls it unqualified; it was a top-level function until the no-top-level-functions
     *  law (2026-08-15) gave it the type it always belonged to. Visibility unchanged (internal). */
    internal fun outcomeExitCode(ok: Boolean): Int = if (ok) 0 else 1

    /** A verb that cannot fail: run [block], exit 0.
     *
     *  `protected` rather than the old file-private, and that IS the narrowest that compiles: a case
     *  is a subclass, and class-`private` is not reachable from the nested arms (measured — the
     *  `Status` arm fails with "Cannot access 'fun success': it is private in Command").
     *
     *  What the widening actually costs: since Kotlin 1.5 a sealed subclass need only share the
     *  PACKAGE and the compilation module, not the file, so the reach is any direct `: Command()` in
     *  package `splice.app.cli` inside `:app` — not "this file only". Today all 13 cases are nested
     *  right here, which is the whole of it, but a new file in this package could reach it. */
    protected inline fun success(block: () -> Unit): Int {
        block()
        return 0
    }
}
