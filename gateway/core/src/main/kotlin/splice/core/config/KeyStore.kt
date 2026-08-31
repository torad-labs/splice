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

import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.core.util.SecureFile
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * One read-modify-write of `keys.toml`, performed while the sibling `.lock` is held.
 *
 * Named rather than left a bare `() -> T` (HD-22) because the seam is the SH-11 invariant itself:
 * whatever runs here may re-read the file and rewrite it knowing no other process can interleave,
 * and — the part the shape cannot say — everything that reads-then-writes MUST be inside one of
 * these. A `set` that read outside the section and wrote inside it would still lose an update, so
 * this type is the boundary of the lost-update fix, not a formatting of `{ … }`.
 *
 * Deliberately private: the lock is KeyStore's own, and nothing outside this file may claim to be
 * running under it.
 */
private fun interface StoreEdit<T> {
    operator fun invoke(): T
}

private class TomlCommentScanner {
    private var quote: Char? = null
    private var escaped = false

    fun startsComment(char: Char): Boolean {
        when {
            escaped -> escaped = false
            startsEscape(char) -> escaped = true
            startsQuote(char) -> quote = char
            quote == char -> quote = null
            startsUnquotedComment(char) -> return true
        }
        return false
    }

    private fun startsEscape(char: Char): Boolean = quote == '"' && char == '\\'

    private fun startsQuote(char: Char): Boolean {
        if (quote != null) return false
        return char == '"' || char == '\''
    }

    private fun startsUnquotedComment(char: Char): Boolean = quote == null && char == '#'
}

public class KeyStore(
    public val path: Path,
    private val log: LogSink = LogSink(DaemonLog::write),
) {
    // mtime of the corrupt keys.toml version warned about in the CURRENT unreadable episode; null =
    // no active episode. One line per broken version, not one per read: read() runs on auth paths,
    // and a corrupt store must not turn the daemon log into a firehose. Long.MIN_VALUE stands for
    // "mtime itself unreadable" (an access-indeterminate store) — the old init VALUE was that same
    // sentinel, so exactly those stores had their FIRST warning swallowed (DR-40 redo). A healthy
    // read (or proven absence) clears the latch, so a later episode — same mtime or no mtime —
    // warns again. Benign race: two threads may both log the same version once.
    @Volatile
    private var warnedCorruptMtime: Long? = null

    /** The key for [envVar], or null when absent/blank/unreadable. Last assignment wins,
     *  comments (#) and blanks are skipped, single or double quotes stripped. */
    public fun read(envVar: String): String? = entries()[envVar]

    /** Every configured env-var NAME (never the values — safe for `splice key list`). */
    public fun names(): Set<String> = entries().keys

    /** Insert or replace [envVar] = [value] (0600 atomic write). Preserves sibling entries;
     *  comments are NOT (we are the only writer — hand edits survive only as entries). */
    public fun write(envVar: String, value: String) {
        require(envVar.matches(envNameRegex)) { "invalid env name '$envVar' (want $envNameRegex)" }
        require(value.isNotBlank()) { "empty key for '$envVar'" }
        // An embedded newline (survives the trim() below, which only strips leading/trailing
        // whitespace) would split into multiple assignments. The line-oriented store cannot encode
        // that shape, so reject it rather than silently persist a different credential.
        require('\n' !in value && '\r' !in value) { "key for '$envVar' contains a newline — cannot store" }
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

    /** Display-path read: tolerant, but no longer SILENT (DR-40) — an unreadable keys.toml used to
     *  be indistinguishable from an empty one, so readKey reported auth-missing and `splice key
     *  list` corroborated the misdiagnosis while the operator's keys sat intact in a file one
     *  parse error away. Corrupt-vs-empty now differ by a daemon-log line, once per file version
     *  (mtime-gated: read() runs on auth paths and must not firehose the log). Absence is proven by
     *  the read, never a Files.exists pre-gate (DR-40 redo, class law): only NoSuchFile with no
     *  NOFOLLOW path entry is the quiet empty — an untraversable parent, an inaccessible symlink
     *  target, and a dangling link all warn. */
    private fun entries(): Map<String, String> {
        val read = Cancellables.runCatchingCancellable { parseLines(Files.readAllLines(path)) }
        val failure = read.exceptionOrNull()
        val genuinelyAbsent = failure is java.nio.file.NoSuchFileException &&
            !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        if (failure == null || genuinelyAbsent) {
            warnedCorruptMtime = null
        } else {
            val mtime = Cancellables.runCatchingCancellable {
                Files.getLastModifiedTime(path).toMillis()
            }.getOrDefault(Long.MIN_VALUE)
            if (warnedCorruptMtime != mtime) {
                warnedCorruptMtime = mtime
                log(
                    "[keys] $path is UNREADABLE (${failure.message}) — treating as empty for " +
                        "display, but your keys are still in the file: fix or remove it " +
                        "(writes abort rather than clobber)\n",
                )
            }
        }
        return read.getOrDefault(emptyMap())
    }

    /** SH-11: the MUTATION-path read. PROVEN-absent = legitimately empty (safe to write);
     *  UNREADABLE = unknown state — abort rather than let persist() rebuild a one-key file over
     *  every stored key. Proof of absence is the read throwing NoSuchFile with no NOFOLLOW path
     *  entry (DR-40 redo): the old exists() pre-gate read an inaccessible store as absent, so a
     *  write would rebuild a one-key file — and atomically REPLACE a store symlink — dropping every
     *  sibling. A dangling link aborts too: its entry exists, so seeding would replace it. The
     *  tolerant [entries] stays for the display paths (read/names). */
    private fun entriesStrict(): Map<String, String> =
        Cancellables.runCatchingCancellable { parseLines(Files.readAllLines(path)) }
            .getOrElse {
                val genuinelyAbsent = it is java.nio.file.NoSuchFileException &&
                    !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                check(genuinelyAbsent) { "keys.toml unreadable ($it) — refusing to write, existing keys preserved" }
                emptyMap()
            }

    private fun parseLines(lines: List<String>): Map<String, String> =
        lines
            .mapNotNull { line ->
                val cut = stripComment(line).trim()
                if (cut.isEmpty() || '=' !in cut) return@mapNotNull null
                val name = cut.substringBefore('=').trim()
                val value = decodeValue(cut.substringAfter('=').trim())
                if (name.matches(envNameRegex) && value.isNotEmpty()) name to value else null
            }
            .toMap()

    /** `#` begins a comment only outside the single/double-quoted value. */
    private fun stripComment(line: String): String {
        val scanner = TomlCommentScanner()
        line.forEachIndexed { index, char ->
            if (scanner.startsComment(char)) return line.substring(0, index)
        }
        return line
    }

    /** Remove one matching quote pair; decode only the escapes [persist] emits. */
    private fun decodeValue(raw: String): String {
        if (raw.length < 2 || raw.first() != raw.last()) return raw
        val inner = raw.substring(1, raw.lastIndex)
        return when (raw.first()) {
            '\'' -> inner
            '"' -> decodeDoubleQuoted(inner)
            else -> raw
        }
    }

    private fun decodeDoubleQuoted(raw: String): String = buildString {
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            val next = raw.getOrNull(index + 1)
            val escape = if (char == '\\') storedEscape(next) else null
            if (escape != null) {
                append(escape)
                index += 2
            } else {
                append(char)
                index += 1
            }
        }
    }

    private fun storedEscape(next: Char?): Char? = next?.takeIf { it == '\\' || it == '"' }

    /** SH-11: cross-process mutation lock on a sibling `.lock` (the G1 lesson applied to
     *  keys.toml — a human `splice key set` racing a token-capture hook is a real interleaving).
     *  tryLock poll: a same-JVM overlap throws instead of queueing, so held-is-held either way.
     *  Bounded, then FAILS LOUDLY — an unlocked concurrent RMW is the exact lost-update this
     *  exists to prevent, so unlike the read-mostly credential refresh there is no unlocked
     *  degrade for a WRITE. Holds are microseconds; 5s of contention means something is wedged. */
    private fun <T> withStoreLock(block: StoreEdit<T>): T {
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
            entries.toSortedMap().forEach { (name, value) ->
                val encoded = value.replace("\\", "\\\\").replace("\"", "\\\"")
                appendLine("$name = \"$encoded\"")
            }
        }
        SecureFile.writeAtomic0600(path, text)
    }
}

// Companion dissolved (Kotlin style law, 2026-08-16 — HD-M8). The constants keep their names at
// file scope, where `private` means file-private; the factory could not follow them there (a
// top-level function is banned) and could not become a KeyStore member either — it computes the
// path the constructor is GIVEN — so it takes the migration's pattern 5: a named object.
// FILE SCOPE ON PURPOSE: one compiled Regex for the whole file, not one per KeyStore instance.
// `internal`, not file-private (review 2026-08-28, PR 99): TokenCaptureSpec validates its
// own envVar against THIS regex rather than a second copy, because that value reaches a bare
// unquoted command word in a generated bash hook — the check has to fire where the spec is
// BUILT, not only when the generated script finally calls `splice key set`.
internal val envNameRegex = Regex("[A-Z][A-Z0-9_]*")

// SH-11 mutation lock: holds are microseconds (parse + atomic write); 5s of contention
// means a wedged peer, and the loud failure preserves the store.
private const val LOCK_WAIT_MS = 5_000L
private const val LOCK_POLL_MS = 25L

/** Where [KeyStore] lives when nothing overrides it — the `KeyStore(KeyStorePath.defaultPath())`
 *  pairing every call site already spelled out, with the companion's namespace made explicit. */
public object KeyStorePath {

    /** keys.toml beside splice.toml: SPLICE_CONFIG's sibling when set, else XDG
     *  (~/.config/splice). Mirrors TopologyLoader.configPath so test rigs stay hermetic. */
    public fun defaultPath(envReader: EnvReader = EnvReader(System::getenv)): Path {
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
