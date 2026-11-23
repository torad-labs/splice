// NEW: desktop-policy-console. The shape each Claude Desktop policy key the console edits takes, as Desktop's own
// schema declares it (Desktop 6.28899.2's configuration schema, read from its bundle Nov 23, 2026): a switch, one word
// of a fixed list, a whole number in a range, a list of hosts, a list of plugin marketplaces, or the claude.ai import
// settings. Each kind says why a value is not one of it, so a value Desktop would drop is refused before it is kept.
package splice.client.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import splice.core.util.JsonScalars

/** What values one policy key takes, by its wire name. */
public sealed class DesktopPolicyKind(public val wire: String) {
    /** Why [value] is not a value of this kind, or null when it is. */
    public abstract fun refusal(value: JsonElement): String?

    /** On or off. */
    public data object Switch : DesktopPolicyKind("boolean") {
        override fun refusal(value: JsonElement): String? =
            if (flag(value) == null) "expected true or false" else null
    }

    /** One of [options]. */
    public data class Choice(val options: List<String>) : DesktopPolicyKind("enum") {
        override fun refusal(value: JsonElement): String? {
            val word = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            return if (word in options) null else "expected one of ${options.joinToString(", ")}"
        }
    }

    /** A whole number from [min] to [max]. */
    public data class WholeNumber(val min: Int, val max: Int) : DesktopPolicyKind("integer") {
        override fun refusal(value: JsonElement): String? {
            val n = (value as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toIntOrNull()
            return if (n == null || n !in min..max) "expected a whole number from $min to $max" else null
        }
    }

    /** Hosts Desktop's sandbox may reach: `*`, or names such as `example.com` and `*.example.com`. */
    public data object Hosts : DesktopPolicyKind("hosts") {
        override fun refusal(value: JsonElement): String? {
            val list = value as? JsonArray ?: return "expected a list of hosts"
            val bad = list.firstOrNull { entry ->
                val host = (entry as? JsonPrimitive)?.takeIf { it.isString }?.content
                host == null || host.length > MAX_HOST || !HOST.matches(host)
            }
            return when {
                list.size > MAX_LIST_ENTRIES -> "expected at most $MAX_LIST_ENTRIES hosts"
                bad != null -> "$bad is not a host name such as example.com or *.example.com"
                else -> null
            }
        }
    }

    /** Plugin marketplaces: each `{source, repo|url, ref?, path?}`, as Claude Code names them. */
    public data object Marketplaces : DesktopPolicyKind("marketplaces") {
        override fun refusal(value: JsonElement): String? {
            val list = value as? JsonArray ?: return "expected a list of marketplaces"
            if (list.size > MAX_LIST_ENTRIES) return "expected at most $MAX_LIST_ENTRIES marketplaces"
            return list.firstNotNullOfOrNull { entry -> entryRefusal(entry as? JsonObject) }
        }

        private fun entryRefusal(entry: JsonObject?): String? {
            val kind = JsonScalars.str(entry, MARKETPLACE_SOURCE)
            val addressKey = marketplaceAddressOf[kind]
            val address = addressKey?.let { JsonScalars.str(entry, it) }?.trim().orEmpty()
            val allowed = setOfNotNull(MARKETPLACE_SOURCE, addressKey, MARKETPLACE_REF, MARKETPLACE_FOLDER)
            return when {
                entry == null || addressKey == null -> "each marketplace needs a source: github, git or url"
                address.isEmpty() -> "a $kind marketplace needs its $addressKey"
                CREDENTIAL_IN_URL.containsMatchIn(address) -> "a marketplace address can't carry a credential"
                entry.keys.any { it !in allowed } -> "a marketplace holds only source, $addressKey, ref and path"
                else -> null
            }
        }
    }

    /** Desktop's claude.ai import settings, any of: `enabled`, `automatic3pImport`, `bannerBehavior`,
     *  `bannerMaxDismissals`. A key left out keeps splice's default. */
    public data object Import : DesktopPolicyKind("import") {
        private val parts: Map<String, DesktopPolicyKind> = mapOf(
            "enabled" to Switch,
            "automatic3pImport" to Switch,
            "bannerBehavior" to Choice(listOf("off", "detect", "show")),
            "bannerMaxDismissals" to WholeNumber(1, IMPORT_MAX_DISMISSALS),
        )

        override fun refusal(value: JsonElement): String? {
            val held = value as? JsonObject ?: return "expected an object of import settings"
            val unknown = held.keys.firstOrNull { it !in parts }
            if (unknown != null) return "$unknown is not an import setting"
            return held.entries.firstNotNullOfOrNull { (key, part) ->
                parts.getValue(key).refusal(part)?.let { "$key: $it" }
            }
        }
    }

    /** [value] as a JSON boolean, or null when it is none (a string "true" is none). */
    protected fun flag(value: JsonElement): Boolean? =
        (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
}

// The most entries a list key holds: Desktop reads every one at launch.
private const val MAX_LIST_ENTRIES = 200

// The longest host name DNS allows.
private const val MAX_HOST = 253

// The most times Desktop's import banner may be dismissed, as its schema bounds it.
private const val IMPORT_MAX_DISMISSALS = 10

// A host, a wildcard host, or `*` alone; an optional port.
private const val HOST_LABEL = """[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?"""
private val HOST = Regex("""^(\*|(\*\.)?$HOST_LABEL(\.$HOST_LABEL)*(:\d{1,5})?)$""")

// A URL that names a user (and so maybe a token) before its host.
private val CREDENTIAL_IN_URL = Regex("""://[^/@\s]+@""")
