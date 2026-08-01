// NEW: the durable api-key store (~/.config/splice/keys.toml, 0600). Until now an api-key head
// authenticated only when the DAEMON's own process environment carried the key — export it after
// the daemon started and the shell saw it but the daemon did not (the #1 documented setup pain).
// KeyStore is the file fallback ApiKeyAuthProvider reads after env and any explicit auth.file:
// `splice key set`, `claude-openrouter login`, or a head's token-capture hook writes here once and every
// later `splice restart` picks the key up from any shell. Flat `NAME = "value"` subset of TOML —
// we are the only writer, so a tolerant line parser is enough and core stays ktoml-free.
// Lives in core/config: System.getenv is walled to this package (kt-no-system-getenv) and the
// path/env readers stay injectable for hermetic tests (StatePaths idiom — JVM cannot setenv).
// Writes route through SecureFile.writeAtomic0600 — the single credential-write primitive (#924).
package splice.core.config

import splice.core.util.SecureFile
import splice.core.util.runCatchingCancellable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

public class KeyStore(
    public val path: Path,
) {
    /** The key for [envVar], or null when absent/blank/unreadable. Last assignment wins,
     *  comments (#) and blanks are skipped, single or double quotes stripped. */
    public fun read(envVar: String): String? = entries()[envVar]

    /** Every configured env-var NAME (never the values — safe for `splice key list`). */
    public fun names(): Set<String> = entries().keys

    /** Insert or replace [envVar] = [value] (0600 atomic write). Preserves sibling entries;
     *  comments are NOT (we are the only writer — hand edits survive only as entries). */
    public fun write(envVar: String, value: String) {
        require(envVar.matches(ENV_NAME)) { "invalid env name '$envVar' (want $ENV_NAME)" }
        require(value.isNotBlank()) { "empty key for '$envVar'" }
        val next = entries().toMutableMap()
        next[envVar] = value.trim()
        persist(next)
    }

    /** Remove [envVar]; returns true when it was present. */
    public fun unset(envVar: String): Boolean {
        val next = entries().toMutableMap()
        val removed = next.remove(envVar) != null
        if (removed) persist(next)
        return removed
    }

    private fun entries(): Map<String, String> {
        if (!Files.exists(path)) return emptyMap()
        return runCatchingCancellable {
            Files.readAllLines(path)
                .mapNotNull { line ->
                    val cut = line.substringBefore('#').trim()
                    if (cut.isEmpty() || '=' !in cut) return@mapNotNull null
                    val name = cut.substringBefore('=').trim()
                    val value = cut.substringAfter('=').trim().trim('"', '\'')
                    if (name.matches(ENV_NAME) && value.isNotEmpty()) name to value else null
                }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    private fun persist(entries: Map<String, String>) {
        val text = buildString {
            appendLine("# splice api keys — password-equivalent, keep 0600, never commit.")
            appendLine("# Written by `splice key set` / `<head> login` / a head's token-capture hook.")
            entries.toSortedMap().forEach { (name, value) -> appendLine("$name = \"$value\"") }
        }
        SecureFile.writeAtomic0600(path, text)
    }

    public companion object {
        private val ENV_NAME = Regex("[A-Z][A-Z0-9_]*")

        /** keys.toml beside splice.toml: SPLICE_CONFIG's sibling when set, else XDG
         *  (~/.config/splice). Mirrors TopologyLoader.configPath so test rigs stay hermetic. */
        public fun defaultPath(envReader: (String) -> String? = System::getenv): Path {
            val override = envReader("SPLICE_CONFIG")
            if (override != null) {
                val expanded =
                    if (override.startsWith("~/")) {
                        System.getProperty("user.home") + override.substring(1)
                    } else {
                        override
                    }
                return Paths.get(expanded).resolveSibling("keys.toml")
            }
            val xdg = envReader("XDG_CONFIG_HOME")
            val base = if (xdg != null) Paths.get(xdg) else Paths.get(System.getProperty("user.home"), ".config")
            return base.resolve("splice").resolve("keys.toml")
        }
    }
}
