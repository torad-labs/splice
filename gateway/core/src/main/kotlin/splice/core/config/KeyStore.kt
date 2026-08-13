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
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

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
        withStoreLock {
            val next = entriesStrict().toMutableMap()
            next[envVar] = value.trim()
            persist(next)
        }
    }

    /** Remove [envVar]; returns true when it was present. */
    public fun unset(envVar: String): Boolean = withStoreLock {
        val next = entriesStrict().toMutableMap()
        val removed = next.remove(envVar) != null
        if (removed) persist(next)
        removed
    }

    private fun entries(): Map<String, String> {
        if (!Files.exists(path)) return emptyMap()
        return runCatchingCancellable { parseLines(Files.readAllLines(path)) }.getOrDefault(emptyMap())
    }

    /** SH-11: the MUTATION-path read. Absent = legitimately empty (safe to write); UNREADABLE =
     *  unknown state — abort rather than let persist() rebuild a one-key file over every stored
     *  key. The tolerant [entries] stays for the display paths (read/names). */
    private fun entriesStrict(): Map<String, String> {
        if (!Files.exists(path)) return emptyMap()
        return runCatchingCancellable { parseLines(Files.readAllLines(path)) }
            .getOrElse { error("keys.toml unreadable ($it) — refusing to write, existing keys preserved") }
    }

    private fun parseLines(lines: List<String>): Map<String, String> =
        lines
            .mapNotNull { line ->
                val cut = line.substringBefore('#').trim()
                if (cut.isEmpty() || '=' !in cut) return@mapNotNull null
                val name = cut.substringBefore('=').trim()
                val value = cut.substringAfter('=').trim().trim('"', '\'')
                if (name.matches(ENV_NAME) && value.isNotEmpty()) name to value else null
            }
            .toMap()

    /** SH-11: cross-process mutation lock on a sibling `.lock` (the G1 lesson applied to
     *  keys.toml — a human `splice key set` racing a token-capture hook is a real interleaving).
     *  tryLock poll: a same-JVM overlap throws instead of queueing, so held-is-held either way.
     *  Bounded, then FAILS LOUDLY — an unlocked concurrent RMW is the exact lost-update this
     *  exists to prevent, so unlike the read-mostly credential refresh there is no unlocked
     *  degrade for a WRITE. Holds are microseconds; 5s of contention means something is wedged. */
    private fun <T> withStoreLock(block: () -> T): T {
        val lockPath = path.resolveSibling("${path.fileName}.lock")
        lockPath.parent?.let { Files.createDirectories(it) }
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = acquireBounded(channel)
            try {
                return block()
            } finally {
                lock.release()
            }
        }
    }

    /** The tryLock poll half of [withStoreLock] (split: depth wall). Throws on a wedged peer. */
    private fun acquireBounded(channel: FileChannel): java.nio.channels.FileLock {
        val deadline = System.currentTimeMillis() + LOCK_WAIT_MS
        while (true) {
            val lock = try {
                channel.tryLock()
            } catch (ignored: OverlappingFileLockException) {
                null // held by this JVM via another channel — same contention, same poll
            }
            if (lock != null) return lock
            check(System.currentTimeMillis() < deadline) {
                "keys.toml locked by a peer for over ${LOCK_WAIT_MS}ms — refusing to write, existing keys preserved"
            }
            Thread.sleep(LOCK_POLL_MS)
        }
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

        // SH-11 mutation lock: holds are microseconds (parse + atomic write); 5s of contention
        // means a wedged peer, and the loud failure preserves the store.
        private const val LOCK_WAIT_MS = 5_000L
        private const val LOCK_POLL_MS = 25L

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
