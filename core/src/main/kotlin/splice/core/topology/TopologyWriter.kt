// NEW: V4-128, FEATURES.md 4.7 and 6 — the structured writer behind PUT /api/topology.
//
// IT EDITS, IT DOES NOT REGENERATE. The console sends a whole topology; the writer compares it with
// what splice.toml declares today, key by key over the canonical JSON trees of the two, and touches
// only the statements whose value moved. Every other byte, comments and blank lines and the
// operator's own ordering included, is kept. The edits, by what the diff found:
//
//   CHANGED   a key whose value differs: the value on its line is rewritten in place and a comment
//             after it survives. A key held INSIDE an inline value (`quirks = { store = true }`) is
//             edited by rewriting that inline value whole.
//   ADDED     a key the file does not state: a scalar or array goes on a new line after the last key
//             of its table (or after its dotted siblings when the table is spelled with dotted keys),
//             and a new TABLE is appended at the end of the file as `[path]` sections.
//   REMOVED   a key the request no longer carries: its line is deleted, and a table leaves with its
//             header, its keys and every sub-table under it. Comments between tables stay.
//   ARRAYS    compare whole. An array spelled as `[[path]]` sections is replaced by the new sections
//             at the position of the first; any other array is one inline value.
//
// THE ARTIFACT IS VERIFIED, NOT THE INTENT. The composed text is parsed by the SAME loader the daemon
// boots with ([TopologyParse], TopologyLoader.parse in production) and must decode to exactly the
// requested topology. A patch that wrote something plausible but different is refused, and a refusal
// of any kind leaves splice.toml byte-identical: nothing is written until every check has passed.
//
// BACKUP FIRST, DATED, NEVER CLOBBERED. Before the move the file is copied to
// `splice.toml.bak-<utc second>-<sha-256 prefix of the old bytes>`. The name carries the content
// hash, so a second backup with the same name holds the same bytes and is left alone rather than
// overwritten. The write itself is a temp file and an ATOMIC_MOVE, after re-reading the file to
// refuse when someone else changed it since the read.
//
// :core IS ktoml-FREE (module law), which is why the parser is injected and TomlScan.kt reads TOML
// only as far as statement boundaries: tables, keys, and where each value starts and ends.
package splice.core.topology

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.model.HeadDiscoveredModels
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.HexFormat

// why: 12 hex characters of the file's SHA-256, carried as the write's precondition token. Long
// enough that two concurrent edits cannot collide by accident, short enough to pass in a header.
private const val HASH_PREFIX = 12
private const val TOPOLOGY_FILE = "splice.toml"
private const val UNEXPRESSIBLE = "the writer cannot express this edit; nothing was written"

/** splice.toml text to the topology it declares — TopologyLoader.parse in production, the loader the
 *  daemon boots with, so the writer's verdict and the next boot's cannot differ. */
public fun interface TopologyParse {
    public operator fun invoke(text: String): Topology
}

/** One reason a write was refused: the dotted path of the key it concerns and what is wrong. */
public data class TopologyFinding(val path: String, val message: String)

/** What a write did. A refusal is a value, never a throw: the route answers it as findings. */
public sealed class TopologyWriteResult {
    /** Written, with the backup taken first; null when the request matched the file and nothing moved. */
    public data class Written(val backup: Path?) : TopologyWriteResult()

    /** Refused; splice.toml is byte-identical to before the call. */
    public data class Refused(val findings: List<TopologyFinding>) : TopologyWriteResult()
}

public class TopologyWriter(
    public val path: Path,
    private val parse: TopologyParse,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    /** 2026-09-22: what each head's endpoint served at start, so an allowlist naming a discovered
     *  model validates here exactly as it resolves at boot. */
    private val discovered: HeadDiscoveredModels = HeadDiscoveredModels { emptyList() },
) {
    private val json = Json { encodeDefaults = false }
    private val keys = TomlKeys()
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    /** The topology the file declares right now, as the canonical tree the console reads and edits.
     *  Throws what the parser throws when the file does not parse; the route answers that. */
    public fun current(): JsonObject = tree(parse(Files.readString(path)))

    /** [topology] as its canonical tree: TOML key names, defaults omitted, key quoting removed. */
    public fun tree(topology: Topology): JsonObject =
        keys.canonical(json.encodeToJsonElement(Topology.serializer(), topology)).jsonObject

    public fun write(requested: Topology): TopologyWriteResult {
        val findings = TopologyChecks(requested, discovered).findings()
        if (findings.isNotEmpty()) return TopologyWriteResult.Refused(findings)
        val existing = Files.readString(path)
        val held = Cancellables.runCatchingCancellable { tree(parse(existing)) }.getOrElse { failure ->
            val why = SafeFailureText.render(failure)
            return refused("$TOPOLOGY_FILE on disk does not parse, so it cannot be edited: $why")
        }
        val wanted = tree(requested)
        return land(existing, TomlPatch(existing, wanted).compose(held), wanted)
    }

    private fun land(existing: String, composed: String, wanted: JsonObject): TopologyWriteResult {
        val landed = Cancellables.runCatchingCancellable { tree(parse(composed)) }.getOrElse { failure ->
            return refused("the edit would not parse, so nothing was written: ${SafeFailureText.render(failure)}")
        }
        val drift = keys.firstDifference(landed, wanted, emptyList())
        return when {
            drift != null -> TopologyWriteResult.Refused(
                listOf(TopologyFinding(drift.joinToString("."), UNEXPRESSIBLE)),
            )
            composed == existing -> TopologyWriteResult.Written(null)
            else -> commit(existing, composed)
        }
    }

    private fun commit(existing: String, composed: String): TopologyWriteResult {
        if (Files.readString(path) != existing) {
            return refused("$TOPOLOGY_FILE changed while this write was prepared; nothing was written, read it again")
        }
        val bytes = existing.toByteArray()
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).take(HASH_PREFIX)
        val backup = path.resolveSibling("${path.fileName}.bak-${stamp.format(Instant.ofEpochMilli(clock()))}-$hash")
        if (!Files.exists(backup)) Files.write(backup, bytes)
        val tmp = path.resolveSibling("${path.fileName}.write-${ProcessHandle.current().pid()}.tmp")
        Files.writeString(tmp, composed)
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return TopologyWriteResult.Written(backup)
    }

    private fun refused(message: String): TopologyWriteResult =
        TopologyWriteResult.Refused(listOf(TopologyFinding(TOPOLOGY_FILE, message)))
}

/** The checks the loader does not make at decode time but the daemon would trip on at boot. */
private class TopologyChecks(private val topology: Topology, private val discovered: HeadDiscoveredModels) {

    fun findings(): List<TopologyFinding> = references() + ports() + rosters()

    private fun references(): List<TopologyFinding> = topology.heads
        .filter { (_, head) -> head.provider !in topology.providers }
        .map { (key, head) ->
            TopologyFinding("heads.$key.provider", "provider '${head.provider}' is not declared under [providers]")
        }

    private fun ports(): List<TopologyFinding> {
        val invalid = topology.invalidPortHeads().map { (key, port) ->
            TopologyFinding("heads.$key.port", "port $port is outside ${validPortRange.first}-${validPortRange.last}")
        }
        val shared = topology.portCollisions().flatMap { (port, owners) ->
            owners.map { owner ->
                val at = if (owner in topology.heads) "heads.$owner.port" else "daemon.control_port"
                TopologyFinding(at, "port $port is shared by ${owners.joinToString(", ")}")
            }
        }
        return invalid + shared
    }

    private fun rosters(): List<TopologyFinding> = topology.heads.mapNotNull { (key, head) ->
        topology.providers[head.provider]?.let { provider ->
            val served = discovered.forHead(key)
            val attempt = Cancellables.runCatchingCancellable { provider.catalogFor(head, discovered = served) }
            attempt.exceptionOrNull()?.let { failure ->
                // SAFE-RENDER-EXEMPT[2026-09-18]: catalogFor reads no file; its failures are its own require() texts, composed from model ids and slot names of the requested topology, never file bytes.
                TopologyFinding("heads.$key.models", failure.message ?: "the head's model list is invalid")
            }
        }
    }
}
