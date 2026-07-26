// NEW: `splice key set|list|unset` — the operator-facing front door to KeyStore
// (~/.config/splice/keys.toml). Interactive `set` reads MASKED from the console (never echoed,
// never in shell history); agents and hooks use --stdin or --value (both are transcript-visible —
// the masked path exists precisely for humans). After a set, the next request already picks the
// key up (ApiKeyAuthProvider re-reads the store per call); `splice restart` refreshes status.
package splice.app.cli

import splice.core.config.KeyStore

private const val MASK_PROMPT = "API key: "

/** argv after `key`: set <ENV> [--value V | --stdin] | list | unset <ENV>. Store injectable
 *  for hermetic tests (the default is the operator's real ~/.config/splice/keys.toml). */
internal fun key(args: List<String>, store: KeyStore = KeyStore(KeyStore.defaultPath())): Boolean {
    return when (args.firstOrNull()) {
        "set" -> keySet(store, args.getOrNull(1), args.drop(2))
        "list" -> keyList(store)
        "unset" -> keyUnset(store, args.getOrNull(1))
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
        System.err.println("splice key set: no interactive console — use --stdin, or --value (visible in ps/history)")
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
