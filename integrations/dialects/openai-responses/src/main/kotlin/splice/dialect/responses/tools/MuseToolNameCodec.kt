package splice.dialect.responses.tools

import splice.core.util.LogSink
import java.security.MessageDigest

/** One Muse head's bounded, reversible tool-name namespace, shared by request and stream paths. */
public class MuseToolNameCodec(private val cap: Int, private val log: LogSink) {
    private val originals = HashMap<String, String>()
    private val aliases = HashMap<String, String>()
    private var fullLogged = false

    /** Keep native names where possible; resolve alias collisions without changing earlier names. */
    @Synchronized
    public fun shorten(name: String): String {
        if (cap < MIN_CAP) return name
        return aliases[name] ?: assign(name)
    }

    private fun assign(name: String): String {
        if (name.length <= cap && originals[name] == null) return record(name, name)
        if (originals.size >= MAX_ENTRIES) return refuse(name)
        return hashedAlias(name)
    }

    private fun hashedAlias(name: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(name.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }
        for (chars in MIN_DIGEST_CHARS..minOf(MAX_DIGEST_CHARS, cap - 2) step DIGEST_STEP) {
            val candidate = name.take(cap - chars - 1) + "_" + digest.take(chars)
            if (originals[candidate] == null) return record(candidate, name)
        }
        error("Muse tool-name namespace cannot assign a distinct alias")
    }

    private fun refuse(name: String): String {
        if (name.length <= cap) error("Muse tool-name namespace is full and a native name overlaps an alias")
        if (!fullLogged) {
            log("[muse-toolname] namespace full; over-cap names will be rejected by the upstream\n")
            fullLogged = true
        }
        return name
    }

    /** Names not issued by this head stay untouched; only its own aliases reverse. */
    @Synchronized
    public fun restore(name: String): String = originals[name] ?: name

    private fun record(alias: String, original: String): String {
        if (originals.size >= MAX_ENTRIES) {
            if (alias != original) error("Muse tool-name namespace is full")
            return original
        }
        originals[alias] = original
        aliases[original] = alias
        return alias
    }
}

private const val MAX_ENTRIES = 4096
private const val MIN_CAP = 10
private const val MIN_DIGEST_CHARS = 8
private const val MAX_DIGEST_CHARS = 60
private const val DIGEST_STEP = 4
private const val BYTE_MASK = 0xff
