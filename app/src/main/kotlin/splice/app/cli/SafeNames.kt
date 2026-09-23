// NEW: v0.4.0 FEATURES.md §6 — the names the doctor report may print. Two allowlists: a VALUE is
// emitted only when it is token-shaped and secret-free (else the field is omitted), and a provider,
// head or account NAME that is not becomes a positional alias from the reserved <kind-N> namespace
// (angle brackets are not token characters, so no real name can collide). Every free-text field
// of the report is additionally SCRUBBED: the denominator is every string the SERIALIZED topology
// carries — keys and values alike, walked from the @Serializable graph, never a hand-typed list —
// plus the account labels; each unsafe one is replaced (case-insensitively, at token boundaries)
// by its alias or <omitted>, and a URL by its host, so config-injected prose cannot reach the
// report through a doctor sentence that quotes it, whatever case that sentence puts it in.
package splice.app.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.accounts.pool.HeadAccountPoolView
import splice.app.cli.doctor.DoctorRedaction
import splice.core.topology.Topology

private val TOKEN = Regex("^[A-Za-z0-9][A-Za-z0-9._:/+-]{0,63}$")
private val URL = Regex("^[A-Za-z][A-Za-z0-9+.-]*://.+")
private const val WORD_CHARS = "A-Za-z0-9_"
private const val OMITTED = "<omitted>"
private const val MIN_SCRUB_CHARS = 2
private val JSON = Json { encodeDefaults = false }

internal class SafeNames(
    private val redaction: DoctorRedaction,
    topology: Topology?,
    pools: Map<String, HeadAccountPoolView> = emptyMap(),
) {
    private val providers = aliases("provider", topology?.providers?.keys.orEmpty())

    /** Every head the report will key on: the topology's, then any the daemon still reports (a stale
     *  daemon after a config edit), so two unsafe keys never share one alias. */
    private val heads = aliases("head", topology?.heads?.keys.orEmpty() + pools.keys.sorted())
    private val labels: Map<String, String> = pools.values.flatMap { view ->
        val all = view.accounts.map { it.label }
        all.map { it to label(all, it) }
    }.toMap()

    /** Every authored string with what replaces it in free text, longest first so a value that
     *  contains another is replaced whole; a safe token needs no replacement and has none. */
    private val scrubs: List<Pair<Regex, String>> = (authored(topology) + labels.keys + pools.keys)
        .filter { it.length >= MIN_SCRUB_CHARS }
        .distinct()
        .let { values ->
            // Two authored values that differ only by case must each keep their own replacement, so
            // those match exactly; every other value is scrubbed whatever case a sentence gives it.
            val ambiguous = values.groupBy { it.lowercase() }.filterValues { it.size > 1 }.keys
            values.mapNotNull { value ->
                replacement(value)?.let { guarded(value, value.lowercase() !in ambiguous) to it }
            }
        }
        .sortedByDescending { it.first.pattern.length }

    fun provider(key: String): String = providers[key] ?: token(key) ?: "<provider-unknown>"

    fun head(key: String): String = heads[key] ?: token(key) ?: "<head-unknown>"

    /** The value itself when token-shaped and secret-free, else null: the field is omitted. */
    fun token(value: String): String? = value.takeIf { TOKEN.matches(it) && redaction.text(it) == it }

    /** An account label by its position in the pool when it is not a safe token. */
    fun label(labels: List<String>, label: String): String = token(label) ?: "<account-${labels.indexOf(label) + 1}>"

    /** Free text with every unsafe authored value replaced and every authored URL reduced to its host. */
    fun scrub(text: String): String = scrubs.fold(text) { acc, (value, safe) -> acc.replace(value, safe) }

    private fun replacement(value: String): String? = when {
        URL.matches(value) -> redaction.host(value)
        token(value) != null -> null
        else -> providers[value] ?: heads[value] ?: labels[value] ?: OMITTED
    }

    /** The value as a case-insensitive pattern that never starts mid-word (an authored "/splice"
     *  never eats the middle of ~/.config/splice) but may be followed by anything: doctor derives
     *  SK-LIVE..._API_KEY from a key-shaped head name, and that suffix must not shield it. */
    private fun guarded(value: String, ignoreCase: Boolean): Regex {
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        return Regex("(?<![$WORD_CHARS])${Regex.escape(value)}", options)
    }

    private fun aliases(kind: String, keys: Collection<String>): Map<String, String> =
        keys.withIndex().associate { (i, key) -> key to (token(key) ?: "<$kind-${i + 1}>") }

    /** The denominator, derived: every key and string value of the topology as it serializes. */
    private fun authored(topology: Topology?): List<String> = buildList {
        topology?.let { strings(JSON.encodeToJsonElement(Topology.serializer(), it), this) }
    }

    private fun strings(element: JsonElement, into: MutableList<String>) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                into += key
                strings(value, into)
            }
            is JsonArray -> element.forEach { strings(it, into) }
            is JsonPrimitive -> if (element.isString) into += element.content
        }
    }
}
