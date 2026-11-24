// NEW: the catalog (modes/README.md, "How splice finds a mode"). Every mode's jar names its folder in the mode index
// and carries its ServiceLoader entry (the splice.mode plugin writes both); the catalog reads every folder the index
// names, checks it whole, and checks the modes against each other. A mode's own tests read it the same way from their
// classpath, and the daemon reads it once at boot from its jar.
package splice.modes.api

import java.util.ServiceConfigurationError
import java.util.ServiceLoader

// why: the ServiceLoader steps past an entry it cannot load, and this bounds the walk should one ever fail in place.
private const val MAX_LOAD_FAILURES = 1000

/** The modes found whole, sorted by name, a finding for every fault, all at once ("mode <name>: ..."), and every
 *  mode the jar lists, whole or not, by its folder name. */
public data class ModeCatalog(val modes: List<FoundMode>, val findings: List<String>, val listed: List<String>) {
    /** The whole mode named [name], or null. */
    public fun mode(name: String): FoundMode? = modes.firstOrNull { it.name == name }
}

/** Reads every mode [loader] can see. */
public class ModeCatalogReader(
    private val loader: ClassLoader,
    private val requireLocalProviders: Boolean = false,
) {
    public fun read(): ModeCatalog {
        val serviceFindings = mutableListOf<String>()
        val handlers = handlers(serviceFindings)
        val folders = folders()
        val checked = folders.map { ManifestCheck(ModeFolder(it, loader), handlers).check() }
        val modes = checked.mapNotNull { it.first }.sortedBy { it.name }
        return ModeCatalog(modes, serviceFindings + checked.flatMap { it.second } + across(modes), folders.sorted())
    }

    /** The folder names the index lists, each once, in order. */
    private fun folders(): List<String> = loader.getResources(MODE_INDEX).toList()
        .flatMap { url -> url.openStream().use { it.readBytes().decodeToString() }.lines() }
        .map(String::trim).filter(String::isNotEmpty).distinct()

    /** Built-in handler failures are reported by their manifests. An external archive must also have no broken
     *  registrations of its own, and must not name a provider from the parent classpath. */
    private fun handlers(findings: MutableList<String>): Map<String, Mode> {
        val found = LinkedHashMap<String, Mode>()
        // ast-grep-ignore: kt-no-reflection-in-production
        val providers = ServiceLoader.load(Mode::class.java, loader).stream().iterator()
        var more = true
        var failures = 0
        while (more) {
            more = try {
                if (providers.hasNext()) {
                    register(providers.next(), found, findings)
                    true
                } else {
                    false
                }
            } catch (_: ServiceConfigurationError) {
                ++failures < MAX_LOAD_FAILURES
            } catch (_: LinkageError) {
                ++failures < MAX_LOAD_FAILURES
            }
        }
        if (requireLocalProviders && failures > 0) findings += "could not load a mode service provider from the archive"
        return found
    }

    private fun register(
        provider: ServiceLoader.Provider<Mode>,
        found: MutableMap<String, Mode>,
        findings: MutableList<String>,
    ) {
        val type = provider.type()
        if (requireLocalProviders && type.classLoader !== loader) {
            findings += "service provider is not defined in its mod archive"
        } else {
            found[type.name] = provider.get()
        }
    }

    /** What two modes may not share: a name, a server, or an offered tool name. Each finding names the later mode. */
    private fun across(modes: List<FoundMode>): List<String> {
        val owned = mapOf(
            "name" to modes.map { it.name to listOf(it.name) },
            "server" to modes.map { it.name to listOf(it.manifest.server) },
            "tool" to modes.map { mode -> mode.name to mode.tools.map { it.offeredAs } },
        )
        return owned.flatMap { (what, values) -> shared(what, values) }
    }

    /** "mode <later>: <what> <value> is also mode <first>'s <what>" for each value a second mode holds. */
    private fun shared(what: String, values: List<Pair<String, List<String>>>): List<String> {
        val owner = HashMap<String, String>()
        return values.flatMap { (mode, held) ->
            held.mapNotNull { value ->
                owner.putIfAbsent(value, mode)?.let { first -> "mode $mode: $what $value is also mode $first's $what" }
            }
        }
    }
}
