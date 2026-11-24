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

    /** Hosts Desktop's sandbox may reach: `*`, or names such as `example.com` and `*.example.com`, each with an
     *  optional port from 1 to 65535. */
    public data object Hosts : DesktopPolicyKind("hosts") {
        override fun refusal(value: JsonElement): String? {
            val list = value as? JsonArray ?: return "expected a list of hosts"
            val bad = list.firstOrNull { !host(it) }
            return when {
                list.size > MAX_LIST_ENTRIES -> "expected at most $MAX_LIST_ENTRIES hosts"
                bad != null -> "$bad is not a host name such as example.com or *.example.com"
                else -> null
            }
        }

        private fun host(entry: JsonElement): Boolean {
            val name = text(entry)?.takeIf { it.length <= MAX_HOST }
            val match = name?.let(HOST::matchEntire)
            val port = match?.groups?.get(HOST_PORT_GROUP)?.value?.toInt()
            return match != null && (port == null || port in 1..HOST_MAX_PORT)
        }
    }

    /** Plugin marketplaces: each `{source, repo|url, ref?, path?}`, as Claude Code names them, every part a string.
     *  A GitHub repository is `owner/repo`; a hosted file an http(s) URL; a git remote an http(s), ssh or `git@`
     *  address. An address that names a user and a password, or a token before an http(s) host, is refused: Desktop's
     *  configuration is no place for a credential. */
    public data object Marketplaces : DesktopPolicyKind("marketplaces") {
        private val addresses: Map<String, Regex> = mapOf(
            "github" to Regex("""^[\w.-]+/[\w.-]+$"""),
            "url" to Regex("""^https?://\S+$"""),
            "git" to Regex("""^(https?://|ssh://|git@)\S+$"""),
        )

        override fun refusal(value: JsonElement): String? {
            val list = value as? JsonArray ?: return "expected a list of marketplaces"
            if (list.size > MAX_LIST_ENTRIES) return "expected at most $MAX_LIST_ENTRIES marketplaces"
            return list.firstNotNullOfOrNull { entry -> entryRefusal(entry as? JsonObject) }
        }

        /** Whether [address] carries a credential: a user before an http(s) host (a token is spelled that way), or a
         *  user with a password before any host. */
        public fun credentialed(address: String): Boolean {
            val named = if (address.startsWith(SSH_SCHEME)) PASSWORD_IN_URL else CREDENTIAL_IN_URL
            return named.containsMatchIn(address)
        }

        private fun entryRefusal(entry: JsonObject?): String? {
            val kind = entry?.get(MARKETPLACE_SOURCE)?.let(::text)
            val addressKey = marketplaceAddressOf[kind]
            if (entry == null || addressKey == null) {
                return "each marketplace needs a source: github, git or url"
            }
            val allowed = setOf(MARKETPLACE_SOURCE, addressKey, MARKETPLACE_REF, MARKETPLACE_FOLDER)
            val optional = listOf(MARKETPLACE_REF, MARKETPLACE_FOLDER).mapNotNull { entry[it] }
            return when {
                entry.keys.any { it !in allowed } -> "a marketplace holds only source, $addressKey, ref and path"
                optional.any { text(it).isNullOrBlank() } -> "a marketplace's ref and path are words"
                else -> addressRefusal(kind, entry[addressKey]?.let(::text))
            }
        }

        /** Why [address] is no address of a [kind] marketplace, or null when it is one. */
        private fun addressRefusal(kind: String?, address: String?): String? = when {
            address == null || addresses[kind]?.matches(address) != true -> "not a $kind address: ${address.orEmpty()}"
            credentialed(address) -> "a marketplace address can't carry a credential"
            else -> null
        }
    }

    /** Desktop's claude.ai import settings, any of: `enabled`, `automatic3pImport`, `bannerBehavior`,
     *  `bannerMaxDismissals`, at least one. A key left out keeps splice's default. */
    public data object Import : DesktopPolicyKind("import") {
        private val parts: Map<String, DesktopPolicyKind> = mapOf(
            "enabled" to Switch,
            "automatic3pImport" to Switch,
            "bannerBehavior" to Choice(listOf("off", "detect", "show")),
            "bannerMaxDismissals" to WholeNumber(1, IMPORT_MAX_DISMISSALS),
        )

        override fun refusal(value: JsonElement): String? {
            val held = (value as? JsonObject)?.takeIf { it.isNotEmpty() }
                ?: return "expected an object of import settings"
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

    /** [value] as a JSON string, or null when it is none (a number or a boolean is none). */
    protected fun text(value: JsonElement): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
}

// The most entries a list key holds: Desktop reads every one at launch.
private const val MAX_LIST_ENTRIES = 200

// The longest host name DNS allows.
private const val MAX_HOST = 253

// why: the highest TCP port; a host's port runs from 1 to it.
private const val HOST_MAX_PORT = 65535

// The most times Desktop's import banner may be dismissed, as its schema bounds it.
private const val IMPORT_MAX_DISMISSALS = 10

// A host, a wildcard host, or `*` alone; an optional port, in the named group.
private const val HOST_LABEL = """[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?"""
private const val HOST_PORT_GROUP = "port"
private val HOST = Regex("""^(\*|(\*\.)?$HOST_LABEL(\.$HOST_LABEL)*)(:(?<$HOST_PORT_GROUP>\d{1,5}))?$""")

// A URL that names a user (and so maybe a token) before its host, whatever the user holds, `@` included.
private val CREDENTIAL_IN_URL = Regex("""://[^/\s]+@""")

// A URL that names a user and a password before its host.
private val PASSWORD_IN_URL = Regex("""://[^/\s:]*:[^/\s]*@""")

// The scheme whose user is only a login name (`ssh://git@host`).
private const val SSH_SCHEME = "ssh://"
