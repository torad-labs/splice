package splice.app.modes

import splice.core.topology.TopologyFinding
import splice.core.topology.mods.ModJar
import splice.core.topology.mods.ModsConfig
import splice.core.util.Cancellables
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.TopologyRefusal
import splice.modes.api.FoundMode
import splice.modes.api.ModeCatalog
import splice.modes.api.ModeCatalogReader
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** Adds independently verified catalogs, keeping their source row for conflicts and cache writes. */
internal class ExternalModes(
    private val parent: ClassLoader,
    private val builtIn: ModeCatalog,
    private val mods: ModsConfig,
    private val configDir: Path,
    private val stateDir: Path,
) {
    private val directory = ModDirectory(mods, configDir)
    private val artifacts = ModArtifacts(stateDir)

    fun read(): LoadedMods {
        val findings = mutableListOf<TopologyFinding>()
        val loaded = mods.jars.mapNotNull { mod ->
            if (mod.path != null && directory.root.isFailure) null else readJar(mod, findings)
        }
        directory.root.exceptionOrNull()?.let { failure ->
            val reason = (failure as? ModFileFailure)?.reason ?: "directory cannot be read"
            findings += TopologyFinding("mods.dir", reason, mods.dirLine)
        }
        val names = ModeClaimsAcrossJars(builtIn)
        loaded.forEach { jar ->
            jar.catalog.findings.mapTo(findings) { jar.mod.finding(it) }
            findings += names.add(jar)
        }
        val catalogs = listOf(builtIn) + loaded.map { it.catalog }
        val catalog = ModeCatalog(catalogs.flatMap { it.modes }.sortedBy { it.name }, builtIn.findings, names.listed())
        return LoadedMods(catalog, findings, loaded)
    }

    private fun readJar(mod: ModJar, findings: MutableList<TopologyFinding>): LoadedMod? = try {
        val artifact = artifacts.acquire(mod, directory)
        val entries = Cancellables.runCatchingBestEffort { ModJarEntries.read(artifact.bytes) }.getOrElse { failure ->
            if (failure is ModFileFailure) throw failure
            throw ModFileFailure("archive is not a valid JAR", if (mod.path == null) "url" else "path")
        }
        val loader = ModJarClassLoader(parent, entries)
        val catalog = ModeCatalogReader(loader, requireLocalProviders = true).read()
        if (catalog.listed.isEmpty()) findings += mod.finding("archive has no modes in splice-modes/index")
        LoadedMod(mod, artifact.path, artifact.takeIf { it.downloaded }, catalog)
    } catch (failure: ModFileFailure) {
        findings += mod.finding(failure.reason, failure.field)
        null
    } catch (_: NoSuchFileException) {
        findings += mod.finding("JAR file does not exist", "path")
        null
    } catch (_: IOException) {
        val field = if (mod.path == null) "url" else "path"
        findings += mod.finding("could not read mod source", field)
        null
    }
}

/** Shared mode/tool namespaces span shipped and external catalogs even though class loaders are isolated. */
private class ModeClaimsAcrossJars(builtIn: ModeCatalog) {
    private val names = builtIn.listed.toMutableSet()
    private val servers = builtIn.modes.map { it.manifest.server }.toMutableSet()
    private val tools = builtIn.modes.flatMap(::toolsOf).toMutableSet()

    fun listed(): List<String> = names.sorted()

    fun add(jar: LoadedMod): List<TopologyFinding> = buildList {
        jar.catalog.listed.filterNot(names::add).forEach { name ->
            add(jar.mod.finding("mode name '$name' is already provided by a built-in mode or another mod"))
        }
        jar.catalog.modes.forEach { mode ->
            if (!servers.add(mode.manifest.server)) {
                val server = mode.manifest.server
                add(jar.mod.finding("mode '${mode.name}' uses an already claimed server name '$server'"))
            }
            toolsOf(mode).filterNot(tools::add).forEach { tool ->
                add(jar.mod.finding("mode '${mode.name}' uses an already claimed tool name '$tool'"))
            }
        }
    }

    private fun toolsOf(mode: FoundMode): List<String> = mode.tools.map { it.offeredAs }
}

internal class LoadedMods(
    val catalog: ModeCatalog,
    val findings: List<TopologyFinding>,
    jars: List<LoadedMod>,
) {
    private val paths = jars.map { it.path to it.mod.sha256 }
    private val downloads = jars.mapNotNull { mod -> mod.download?.let { mod.mod to it } }.toMutableList()

    fun persist() {
        val failures = downloads.distinctBy { it.second.path }.mapNotNull { (mod, artifact) ->
            Cancellables.runCatchingCancellable { artifact.cache() }.exceptionOrNull()
                ?.let { mod.finding("could not write the verified JAR to the state cache", "url") }
        }
        downloads.clear()
        if (failures.isNotEmpty()) throw TopologyRefusal(failures)
    }

    fun log(log: LogSink) {
        paths.forEach { (path, pin) -> log("loaded mod JAR ${LogSafe.str(path.toString())} (SHA-256 $pin)") }
    }
}

internal data class LoadedMod(val mod: ModJar, val path: Path, val download: PinnedMod?, val catalog: ModeCatalog)
