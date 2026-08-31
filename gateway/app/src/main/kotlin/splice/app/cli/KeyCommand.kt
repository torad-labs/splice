// NEW: `splice key set|list|unset` — the operator-facing front door to KeyStore
// (~/.config/splice/keys.toml). Interactive `set` reads MASKED from the console (never echoed,
// never in shell history); agents and hooks use --stdin or --value (both are transcript-visible —
// the masked path exists precisely for humans). After a set, the next request already picks the
// key up (ApiKeyAuthProvider re-reads the store per call); `splice restart` refreshes status.
package splice.app.cli

import splice.core.config.KeyStore
import splice.core.config.KeyStorePath
import splice.core.util.LogSink

private const val MASK_PROMPT = "API key: "

/** Where the CLI's default KeyStore lives — the operator's real ~/.config/splice/keys.toml in
 *  production, a hermetic path in the DR-40 production-wiring arm. A fun interface (not a raw
 *  `() -> Path`) per kt-no-lambda-seam; named for the ROLE. */
internal fun interface KeyStorePathSource {
    operator fun invoke(): java.nio.file.Path
}

/** The `key` verb as a cohesive unit of behavior (Kotlin style law, 2026-08-15: main sources carry
 *  no top-level functions). Every member keeps the old function's name. */
internal class KeyCommand(
    private val storePath: KeyStorePathSource = KeyStorePathSource { KeyStorePath.defaultPath() },
) {

    /** DR-40 gap 2 (codex): where the CLI's KeyStore diagnostics land. The store's default sink is
     *  DaemonLog, which only the DAEMON process installs — in this CLI process it is a no-op, so an
     *  unreadable keys.toml made `splice key list` print "no keys stored" and silently drop the
     *  corrupt-vs-empty warning. The CLI's user interface for diagnostics is stderr; the store's
     *  lines carry their own newline, hence print not println. */
    internal fun cliStoreSink(): LogSink = LogSink { System.err.print(it) }

    /** argv after `key`: set <ENV> [--value V | --stdin] | list | unset <ENV>. Store injectable
     *  for hermetic tests; null resolves the PRODUCTION default — [storePath] + [cliStoreSink] —
     *  inside the body so the DR-40 wiring arm can drive the real chain with only the path swapped
     *  (a default-parameter expression is invisible to a test that injects, so a mutant dropping
     *  the sink there survived; resolving here makes it killable). */
    internal fun key(
        args: List<String>,
        store: KeyStore? = null,
    ): Boolean {
        val resolved = store ?: KeyStore(storePath(), log = cliStoreSink())
        return when (args.firstOrNull()) {
            "set" -> keySet(resolved, args.getOrNull(1), args.drop(2))
            "list" -> keyList(resolved)
            "unset" -> keyUnset(resolved, args.getOrNull(1))
            else -> {
                System.err.println("usage: splice key set <ENV_NAME> [--value V | --stdin] | list | unset <ENV_NAME>")
                false
            }
        }
    }

    private fun keySet(store: KeyStore, envVar: String?, flags: List<String>): Boolean {
        if (envVar == null) {
            System.err.println("splice key set: missing <ENV_NAME> (e.g. OPENROUTER_API_KEY)")
            return false
        }
        val value = readValue(flags) ?: return false
        return runCatching { store.write(envVar, value) }
            .onSuccess {
                println("$envVar stored to ${store.path} (0600).")
                println("Live daemons pick it up on the next request; `splice restart` refreshes status.")
            }
            .onFailure { System.err.println("splice key set: ${it.message}") }
            .isSuccess
    }

    private fun readValue(flags: List<String>): String? = when {
        "--stdin" in flags -> readKeyStdin()
        "--value" in flags -> flags.getOrNull(flags.indexOf("--value") + 1)
            ?: run {
                System.err.println("splice key set: --value needs an argument (prefer --stdin; --value shows in ps)")
                null
            }
        else -> readKeyMasked()
    }

    private fun readKeyStdin(): String? =
        System.`in`.bufferedReader().readText().trim().ifEmpty {
            System.err.println("splice key set: empty key on stdin")
            null
        }

    private fun readKeyMasked(): String? {
        val console = System.console() ?: run {
            System.err.println(
                "splice key set: no interactive console — use --stdin, or --value (visible in ps/history)",
            )
            return null
        }
        val chars = console.readPassword(MASK_PROMPT) ?: return null
        return String(chars).trim().ifEmpty {
            System.err.println("splice key set: empty key")
            null
        }
    }

    private fun keyList(store: KeyStore): Boolean {
        val names = store.names()
        if (names.isEmpty()) {
            println("no keys stored (${store.path})")
        } else {
            names.sorted().forEach { println("$it = stored") }
        }
        return true
    }

    private fun keyUnset(store: KeyStore, envVar: String?): Boolean {
        if (envVar == null) {
            System.err.println("splice key unset: missing <ENV_NAME>")
            return false
        }
        val removed = store.unset(envVar)
        println(if (removed) "$envVar removed from ${store.path}" else "$envVar was not stored")
        return true
    }
}
