// NEW: load the topology TOML (~/.config/splice/splice.toml, XDG) into the :core schema, with
// jar-bundled defaults materialized on first run (mirrors how ensureMgmtKey lazily writes state).
// ktoml adopted per spike P0-TOML. Loaded ONCE at daemon start — adding a provider/head is an
// operator action that implies a restart (no hot topology). V4-162: the context windows are the
// exception, re-read by TopologyWindows while the daemon runs.
package splice.topology

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.exceptions.TomlDecodingException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import splice.core.GATEWAY_VERSION
import splice.core.SHIM_VERSION
import splice.core.config.UserHome
import splice.core.perf.HISTORY_DEFAULT_DAYS
import splice.core.topology.Topology
import splice.core.topology.TopologyFinding
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SecureFile
import splice.core.util.TopologyRefusal
import splice.core.util.TopologyTypeFailure
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** DR-66 redo: the first-run claim as a seam (the DR-67 WrapperClaim precedent) — the
 *  no-concurrent-clobber property (the claim LOSES to a creator that lands between the
 *  proven-absence read and the write, and the winner's bytes are read back) is only testable on
 *  the production path if a test can interleave that creator before the claim. */
internal fun interface StarterWrite {
    fun claim(path: java.nio.file.Path, starter: ByteArray)
}

/** The production claim: CREATE_NEW — exclusive by construction, never a truncate — and owner-only
 *  from creation (V4-275): the operator edits this file by hand, secrets included, and an editor keeps
 *  the mode it finds. */
internal object ExclusiveStarterWrite : StarterWrite {
    override fun claim(path: java.nio.file.Path, starter: ByteArray) {
        SecureFile.createNew0600(path, starter)
    }
}

internal data class WrongTopologyType(val key: String, val line: Int, val expected: TopologyTypeFailure.Expected)

/** Diagnose only a type mismatch the schema and masked source BOTH prove. The mask hides values and
 *  comments but preserves line numbers; parser exception text is never used, since it may quote a
 *  header credential. Called only after ktoml refuses the input, so valid TOML is never regraded. */
internal object TopologyTypeMismatch {
    private val table = Regex("^[ \\t]*\\[{1,2}(.+?)\\]{1,2}[ \\t]*$")
    private val segments = Regex("\\\"[^\\\"]+\\\"|'[^']+'|[A-Za-z0-9_-]+")
    private val assignment = Regex("^[ \\t]*([A-Za-z0-9_.-]+|\\?[ \\t]*)[ \\t]*=[ \\t]*(\\S)")
    private const val BROKEN_SECTION = "?"
    private val inlineEntry = Regex("[{},][ \\t]*([A-Za-z0-9_-]+)[ \\t]*=[ \\t]*(\\S)")

    fun first(text: String): WrongTopologyType? {
        scan(text) { section, line, source, at -> diagnoseLine(section, line, source, at)?.let { return it } }
        return null
    }

    /** Each line of [text] that is not a table header, with the table it sits under, over the masked text so a string
     *  or a comment can neither fake nor hide a key. A header the parser cannot read makes its section [BROKEN_SECTION]
     *  and its keys are not judged at all: they belong to no table anyone can name, and the parser's own error names
     *  that line. */
    private inline fun scan(text: String, visit: (String, String, String, Int) -> Unit) {
        var section = ""
        val sourceLines = text.lineSequence().iterator()
        TomlStructureMasker(text).mask().lineSequence().forEachIndexed { at, line ->
            val source = sourceLines.next()
            val header = table.matchEntire(line)
            if (header != null) {
                section = source.substring(checkNotNull(header.groups[1]).range)
            } else if (line.trimStart().startsWith("[")) {
                section = BROKEN_SECTION
            } else if (section != BROKEN_SECTION) {
                visit(section, line, source, at + 1)
            }
        }
    }

    /** EVERY finding the schema and the masked source prove: each wrong type and each key the schema does not
     *  declare, with its line. The decode stops at its first failure; a person fixing a file needs the rest. */
    fun all(text: String): List<TopologyFinding> {
        val found = mutableListOf<TopologyFinding>()
        scan(text) { section, line, source, at ->
            val wrong = diagnoseLine(section, line, source, at)?.let { typeFinding(it) }
            (unknownKey(section, line, source, at) ?: wrong)?.let { found += it }
        }
        return found
    }

    private fun typeFinding(wrong: WrongTopologyType): TopologyFinding {
        val failure = TopologyTypeFailure(wrong.key, wrong.line, wrong.expected)
        return TopologyFinding(wrong.key, "expects ${wrong.expected.label}: ${failure.fix()}", wrong.line)
    }

    private fun unknownKey(section: String, line: String, source: String, lineNumber: Int): TopologyFinding? {
        val found = assignment.find(line) ?: return null
        val member = source.substring(checkNotNull(found.groups[1]).range).trim()
        var parent = fieldDescriptor(section) ?: return null
        while (parent.kind == StructureKind.LIST) parent = parent.getElementDescriptor(0)
        val name = member.substringBefore('.').replace("\"", "").replace("'", "")
        val known = (0 until parent.elementsCount).any { parent.getElementName(it) == name }
        return if (known || !declaresKeys(parent, member)) {
            null
        } else {
            val key = listOf(section, member).filter(String::isNotEmpty).joinToString(".")
            TopologyFinding(key, "is not a splice.toml setting; remove the line", lineNumber)
        }
    }

    private fun declaresKeys(parent: SerialDescriptor, member: String): Boolean =
        parent.kind == StructureKind.CLASS && !member.startsWith("?")

    private fun diagnoseLine(section: String, line: String, source: String, lineNumber: Int): WrongTopologyType? {
        val found = assignment.find(line)
        return if (found == null) {
            null
        } else {
            val member = source.substring(checkNotNull(found.groups[1]).range).trim()
            val key = listOf(section, member).filter(String::isNotEmpty).joinToString(".")
            val expected = expected(key)
            val actual = actual(found.groupValues[2].single())
            val numeric = expected == TopologyTypeFailure.Expected.NUMBER &&
                actual == TopologyTypeFailure.Expected.INTEGER
            when {
                expected == null || actual == null -> null
                actual != expected && !numeric -> WrongTopologyType(key, lineNumber, expected)
                actual == TopologyTypeFailure.Expected.TABLE ->
                    inlineMismatch(key, lineNumber, line.substring(found.range.last))
                else -> null
            }
        }
    }

    /** Inline tables can hold map values, e.g. extra_headers = { x = { ... } }. Inspect only
     *  masked entry keys and each value's first structural character, never the value's text. */
    private fun inlineMismatch(parent: String, line: Int, value: String): WrongTopologyType? {
        for (entry in inlineEntry.findAll(value)) {
            val key = "$parent.${entry.groupValues[1]}"
            val expected = expected(key)
            val actual = actual(entry.groupValues[2].single())
            if (actual != null && expected != null) {
                if (actual != expected) return WrongTopologyType(key, line, expected)
            }
        }
        return null
    }

    /** The TOML shape each serial kind is written as; a kind not listed (a byte, a char, an object, a polymorphic or contextual
     *  value) has no single spelling, so it names none. A lookup, not a `when`: the kind type is open to new cases. */
    private val expectedByKind: Map<SerialKind, TopologyTypeFailure.Expected> = mapOf(
        PrimitiveKind.STRING to TopologyTypeFailure.Expected.QUOTED_STRING,
        SerialKind.ENUM to TopologyTypeFailure.Expected.QUOTED_STRING,
        PrimitiveKind.INT to TopologyTypeFailure.Expected.INTEGER,
        PrimitiveKind.LONG to TopologyTypeFailure.Expected.INTEGER,
        PrimitiveKind.BOOLEAN to TopologyTypeFailure.Expected.BOOLEAN,
        PrimitiveKind.FLOAT to TopologyTypeFailure.Expected.NUMBER,
        PrimitiveKind.DOUBLE to TopologyTypeFailure.Expected.NUMBER,
        StructureKind.MAP to TopologyTypeFailure.Expected.TABLE,
        StructureKind.CLASS to TopologyTypeFailure.Expected.TABLE,
        StructureKind.LIST to TopologyTypeFailure.Expected.ARRAY,
    )

    private fun expected(key: String): TopologyTypeFailure.Expected? =
        fieldDescriptor(key)?.kind?.let { expectedByKind[it] }

    private fun fieldDescriptor(key: String): SerialDescriptor? {
        var descriptor: SerialDescriptor = Topology.serializer().descriptor
        for (segment in segments.findAll(key).map { it.value.replace("\"", "").replace("'", "") }) {
            while (descriptor.kind == StructureKind.LIST) descriptor = descriptor.getElementDescriptor(0)
            val kind = descriptor.kind
            descriptor = if (kind == StructureKind.MAP) {
                descriptor.getElementDescriptor(1)
            } else if (kind == StructureKind.CLASS) {
                val index = (0 until descriptor.elementsCount)
                    .firstOrNull { descriptor.getElementName(it) == segment } ?: return null
                descriptor.getElementDescriptor(index)
            } else {
                return null
            }
        }
        return descriptor
    }

    private fun actual(first: Char): TopologyTypeFailure.Expected? = when (first) {
        '?' -> TopologyTypeFailure.Expected.QUOTED_STRING
        '{' -> TopologyTypeFailure.Expected.TABLE
        '[' -> TopologyTypeFailure.Expected.ARRAY
        't', 'f' -> TopologyTypeFailure.Expected.BOOLEAN
        in '0'..'9', '-', '+' -> TopologyTypeFailure.Expected.INTEGER
        else -> null
    }
}

public object TopologyLoader {

    private const val DEFAULT_TOML = """
[daemon]
control_port = 3096
# Reasoning display (edit + restart; env/PATCH still override):
show_reasoning = "text"
summary = "detailed"
replay_reasoning = false

[defaults]
# How far back splice keeps this install's history: the hourly spend totals and the request
# records, as one window. Days, or "forever". Change it here, or on Settings > Your data in the
# console, where shortening it says how many turns it would delete and deletes nothing until you
# say yes. An install made before this line existed keeps the 90 days it already had.
historyRetentionDays = "$HISTORY_DEFAULT_DAYS"

# No provider or head is selected on first run. Connect a plan with `splice setup`, or add a
# specific profile with `splice add <profile>`. The ChatGPT, Grok, Kimi and Muse sign-in examples
# are in splice.example.toml.
"""

    public fun configPath(env: EnvReader = EnvReader(System::getenv)): Path {
        val override = env("SPLICE_CONFIG")
        if (override != null) return Paths.get(UserHome.expand(override, env))
        val xdg = env("XDG_CONFIG_HOME")
        val base = if (xdg != null) Paths.get(xdg) else UserHome.dir(env).resolve(".config")
        return base.resolve("splice").resolve("splice.toml")
    }

    public fun loadOrMaterialize(path: Path): Topology = loadOrMaterializeWithDigest(path).topology

    /** JW-04: the parsed topology PLUS the sha-256 of the exact bytes it came from. The digest
     *  rides /health so shim/doctor/dashboard can tell "the file changed since boot" — topology
     *  stays deliberately non-hot-reloadable; this only makes the required restart visible. The
     *  one exception is the context windows (V4-162): TopologyWindows re-reads those, and /health
     *  then publishes the version the daemon RUNS, which a window-only edit moves. */
    public data class LoadedTopology(val topology: Topology, val digest: String)

    public fun loadOrMaterializeWithDigest(path: Path): LoadedTopology =
        loadOrMaterializeWithDigest(path, ExclusiveStarterWrite)

    internal fun loadOrMaterializeWithDigest(path: Path, write: StarterWrite): LoadedTopology {
        val bytes = readOrMaterialize(path, write)
        return LoadedTopology(parse(bytes.toString(Charsets.UTF_8)), sha256Hex(bytes))
    }

    /** Boot and restart: the topology, or a [TopologyRefusal] listing EVERY problem in the file at once. */
    public fun loadForBoot(path: Path): LoadedTopology {
        val bytes = readOrMaterialize(path, ExclusiveStarterWrite)
        val stateBase = path.toAbsolutePath().parent ?: path
        return when (val read = ConfigFindings.read(bytes.toString(Charsets.UTF_8), stateBase)) {
            is ConfigRead.Ready -> LoadedTopology(read.topology, sha256Hex(bytes))
            is ConfigRead.Refused -> throw TopologyRefusal(read.findings)
        }
    }

    private fun readOrMaterialize(path: Path, write: StarterWrite): ByteArray {
        // DR-66: the read is the probe. Only proven absence (NoSuch + no NOFOLLOW entry) is a
        // first run; an unreadable existing file — or a dangling dotfiles symlink — aborts loud
        // instead of being clobbered with (or written through by) the starter.
        return Cancellables.runCatchingCancellable { Files.readAllBytes(path) }
            .getOrElse { failure ->
                val genuinelyAbsent = failure is java.nio.file.NoSuchFileException &&
                    !Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                if (!genuinelyAbsent) throw failure
                materializeStarter(path, write)
            }
    }

    /** First-run creation NEVER truncates an unobserved path: the exclusive claim loses to any
     *  concurrent creator, whose file then wins and is read back instead. */
    private fun materializeStarter(path: Path, write: StarterWrite): ByteArray {
        val starter = (DEFAULT_TOML.trimIndent() + "\n").toByteArray(Charsets.UTF_8)
        path.parent?.let(Files::createDirectories)
        return Cancellables.runCatchingCancellable {
            write.claim(path, starter)
            starter
        }.getOrElse { failure ->
            if (failure is java.nio.file.FileAlreadyExistsException) Files.readAllBytes(path) else throw failure
        }
    }

    /** Digest of the file as it is on disk RIGHT NOW; null when unreadable (fail open — an
     *  unreadable file must degrade the staleness signal, never break /health or a launch). */
    public fun currentDigest(path: Path): String? = try {
        sha256Hex(Files.readAllBytes(path))
    } catch (_: IOException) {
        null
    }

    /** sha-256 of splice.toml bytes, the one spelling of a topology digest: boot, [currentDigest] and
     *  the live window re-read (V4-162, TopologyWindows) all hash through here. */
    public fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    public fun parse(text: String): Topology {
        // Structural guards ktoml lacks (duplicate models keys, reopened tables, string rosters)
        // live in TomlStructurePreflight — extracted with its masker, 2026-08-31 concentration.
        TomlStructurePreflight.check(text)
        return decode(text)
    }

    /** The decode alone, without the structural guards: ktoml's type failure becomes the safe one-key diagnosis. */
    internal fun decode(text: String): Topology {
        return try {
            Toml.decodeFromString(text)
        } catch (failure: TomlDecodingException) {
            val mismatch = TopologyTypeMismatch.first(text) ?: throw failure
            throw TopologyTypeFailure(mismatch.key, mismatch.line, mismatch.expected)
        }
    }

    public fun expandHome(raw: String): String =
        UserHome.expand(raw)

    // Version seams so CLI files can drop a splice.core import (median 1.0) without
    // taking the floor. Same pattern as DaemonHealth.cliVersion / ControlPayloads.gatewayVersion.
    public fun gatewayVersion(): String = GATEWAY_VERSION
    public fun shimVersion(): String = SHIM_VERSION
}
