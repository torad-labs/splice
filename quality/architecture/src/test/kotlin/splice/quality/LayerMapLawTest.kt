// NEW: THE LAYER LAW (splice-lead ruling, 2026-10-07). Every Gradle module plays exactly one role
// (src/test/resources/layer-map.toml), the composition root is one named file, and the roles point
// one way. The import check reads the real sources through Konsist; the owner of an import is the
// layer that owns the longest package the import names, so an unclassified package is never silently
// allowed.
package splice.quality

import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** The architectural role of a Gradle module, by the key the map files it under. */
internal enum class Layer(val key: String) {
    DOMAIN("domain"),
    ADAPTER("adapter"),
    FEATURE("feature"),
    COMPOSITION("composition"),
    TOOLING("tooling"),
}

/** The layers a file in [layer] may import from, read off the direction of the module graph. */
internal fun mayImport(layer: Layer): Set<Layer> = when (layer) {
    Layer.DOMAIN -> setOf(Layer.DOMAIN)
    Layer.ADAPTER -> setOf(Layer.DOMAIN, Layer.ADAPTER)
    Layer.FEATURE -> setOf(Layer.DOMAIN, Layer.ADAPTER, Layer.FEATURE)
    Layer.COMPOSITION -> Layer.entries.toSet()
    // Tooling is graded like every layer: it may import tooling, and no product module.
    Layer.TOOLING -> setOf(Layer.TOOLING)
}

/** The parsed layer-map.toml: each layer's Gradle module paths, and the composition root's file. */
internal data class LayerMap(
    val modulesOf: Map<Layer, List<String>>,
    val compositionRootFile: String,
)

/** One production source file, as the layer law sees it. */
internal data class ScannedFile(
    val module: String,
    val layer: Layer,
    val name: String,
    val packageName: String?,
    val imports: List<String>,
)

internal fun parseLayerMap(text: String): LayerMap {
    val document = MiniToml.parse(text)
    val modulesOf = Layer.entries.associateWith { layer ->
        checkNotNull(document.tables[layer.key]?.strings("modules")) {
            "layer-map.toml has no [${layer.key}] table with a modules list"
        }
    }
    val root = checkNotNull(document.tables["composition-root"]?.text("file")) {
        "layer-map.toml has no [composition-root] file"
    }
    return LayerMap(modulesOf, root)
}

/** Every module the build declares must play exactly one layer, and the map may name no other module. */
internal fun classificationProblems(map: LayerMap, declared: Set<String>): List<String> {
    val layersOf = map.modulesOf.entries
        .flatMap { (layer, modules) -> modules.map { module -> module to layer } }
        .groupBy({ it.first }, { it.second })
    val unclassified = declared.sorted()
        .filter { layersOf[it].isNullOrEmpty() }
        .map { "$it is a Gradle module the layer map does not classify — add it to layer-map.toml" }
    val doubled = layersOf.filterValues { it.size > 1 }.toSortedMap()
        .map { (module, layers) ->
            "$module is classified in ${layers.map { it.key }} — a module plays exactly one layer"
        }
    val stale = layersOf.keys.filter { it !in declared }.sorted()
        .map { "$it is in layer-map.toml but the build does not declare it — delete the stale entry" }
    return unclassified + doubled + stale
}

/** The layer that owns [import]: the owner of the longest prefix of it that some source file declares as a package. */
internal fun ownerOf(import: String, owners: Map<String, Layer>): Layer? {
    var name = import
    while (true) {
        owners[name]?.let { return it }
        if ('.' !in name) return null
        name = name.substringBeforeLast('.')
    }
}

/** Each declared package belongs to one layer. A package split across layers is a breach of both. */
internal fun packageOwners(files: List<ScannedFile>): Pair<Map<String, Layer>, List<String>> {
    val layersOf = files
        .mapNotNull { file -> file.packageName?.let { it to file.layer } }
        .groupBy({ it.first }, { it.second })
    val problems = layersOf.filterValues { it.distinct().size > 1 }.toSortedMap()
        .map { (pkg, layers) ->
            "package $pkg is declared in ${layers.distinct().map { it.key }} — a package belongs to one layer"
        }
    val owners = layersOf.mapValues { (_, layers) -> layers.first() }
    return owners to problems
}

/** Modules that declare main sources but yielded no scanned file, so their imports would go ungraded. */
internal fun unreadModules(withSources: List<String>, files: List<ScannedFile>): List<String> =
    withSources.filter { module -> files.none { it.module == module } }

/** Every import that reaches a layer the importing file's layer may not reach. */
internal fun importBreaches(files: List<ScannedFile>, owners: Map<String, Layer>): List<String> =
    files.flatMap { file ->
        file.imports.mapNotNull { import ->
            val owner = ownerOf(import, owners)
            when {
                owner == null || owner in mayImport(file.layer) -> null
                else -> {
                    val allowed = mayImport(file.layer).map { it.key }.sorted()
                    "${file.module}/${file.name} (${file.layer.key}) imports $import, owned by ${owner.key}; " +
                        "${file.layer.key} may import $allowed"
                }
            }
        }
    }

private const val LAYER_MAP_RESOURCE = "/layer-map.toml"
private const val COMPOSITION_MODULE = ":app"
private const val PROCESS_ENTRY = "fun main("

class LayerMapLawTest {

    // P0: the module set and every directory come from the BUILD, through the one channel that fails
    // by name when absent (ProjectMap.kt).
    private val map = ProjectMap.fromSystemProperties()

    private val layerMap: LayerMap by lazy {
        val stream = checkNotNull(LayerMapLawTest::class.java.getResourceAsStream(LAYER_MAP_RESOURCE)) {
            "no $LAYER_MAP_RESOURCE on the test classpath — the layer law has no map to read"
        }
        parseLayerMap(stream.use { it.readBytes().decodeToString() })
    }

    private fun scanned(): List<ScannedFile> = layerMap.modulesOf
        .flatMap { (layer, modules) -> modules.flatMap { scanModule(it, layer) } }

    private fun scanModule(module: String, layer: Layer): List<ScannedFile> {
        if (!map.mainSources(module).isDirectory) return emptyList()
        return Konsist.scopeFromDirectory("${map.relativeDir(module)}/src/main/kotlin").files.map { file ->
            ScannedFile(module, layer, file.name, file.packagee?.name, file.imports.map { it.name })
        }
    }

    @Test
    fun `every Gradle module plays exactly one layer`() {
        val problems = classificationProblems(layerMap, map.modules)
        assertTrue(problems.isEmpty()) { "LAYER MAP: ${problems.joinToString("; ")}" }
    }

    @Test
    fun `the composition root is one file, and it is the only process entry in app`() {
        val root = File(map.root, layerMap.compositionRootFile)
        assertTrue(root.isFile) { "composition root ${layerMap.compositionRootFile} does not exist" }
        val entries = map.mainSources(COMPOSITION_MODULE).walk()
            .filter { it.isFile && it.extension == "kt" && PROCESS_ENTRY in it.readText() }
            .map { it.relativeTo(map.root).invariantSeparatorsPath }
            .toList()
        assertEquals(listOf(layerMap.compositionRootFile), entries) {
            "the process entry in $COMPOSITION_MODULE must be exactly the composition root; found $entries"
        }
    }

    @Test
    fun `no package belongs to two layers`() {
        val (_, problems) = packageOwners(scanned())
        assertTrue(problems.isEmpty()) { "LAYER PACKAGES: ${problems.joinToString("; ")}" }
    }

    @Test
    fun `imports point one way through the layers`() {
        val files = scanned()
        val (owners, _) = packageOwners(files)
        val breaches = importBreaches(files, owners)
        assertTrue(breaches.isEmpty()) { "LAYER DIRECTION: ${breaches.joinToString("; ")}" }
    }

    @Test
    fun `tooling is scanned, every module with main sources is read, and the scan reads imports`() {
        val files = scanned()
        assertTrue(files.any { it.layer == Layer.TOOLING }) { "no tooling file was scanned, so it is not graded" }
        val withSources = layerMap.modulesOf.values.flatten().filter { map.mainSources(it).isDirectory }
        val unread = unreadModules(withSources, files)
        assertTrue(unread.isEmpty()) {
            "modules with main sources that yielded no file, so their imports are not graded: $unread"
        }
        assertTrue(files.sumOf { it.imports.size } > 0) {
            "the scan read no imports at all, so the import law is vacuous"
        }
    }

    @Test
    fun `a module with no read file is named`() {
        assertEquals(listOf(":core"), unreadModules(listOf(":core"), emptyList()))
        val read = ScannedFile(":core", Layer.DOMAIN, "A.kt", "splice.core", emptyList())
        assertEquals(emptyList<String>(), unreadModules(listOf(":core"), listOf(read)))
    }

    @Test
    fun `an import that reaches a layer the importer may not reach is a breach, and an allowed one is not`() {
        fun file(module: String, layer: Layer, pkg: String, vararg imports: String) =
            ScannedFile(module, layer, "F.kt", pkg, imports.toList())

        val owners = mapOf(
            "splice.core" to Layer.DOMAIN,
            "splice.up" to Layer.ADAPTER,
            "splice.head" to Layer.FEATURE,
            "splice.firchecks" to Layer.TOOLING,
        )
        // (importing file, the one import expected to breach)
        mapOf(
            file(":integrations-x", Layer.ADAPTER, "splice.x", "splice.head.HeadServer") to "splice.head.HeadServer",
            file(":core", Layer.DOMAIN, "splice.core", "splice.up.Client") to "splice.up.Client",
            file(":quality-architecture", Layer.TOOLING, "splice.firchecks.leak", "splice.core.Clock") to
                "splice.core.Clock",
        ).forEach { (importer, imported) ->
            val breaches = importBreaches(listOf(importer), owners)
            assertEquals(1, breaches.size) { "expected one breach for $imported, got $breaches" }
            assertTrue(breaches.single().contains("imports $imported")) { breaches.single() }
        }
        val allowed = listOf(
            file(":features-y", Layer.FEATURE, "splice.head", "splice.core.Clock"),
            file(":quality-architecture", Layer.TOOLING, "splice.firchecks.sub", "splice.firchecks.Registrar"),
        )
        assertEquals(emptyList<String>(), importBreaches(allowed, owners))
    }

    @Test
    fun `an unclassified module and a doubly classified module both fail by name`() {
        val map = LayerMap(
            modulesOf = mapOf(
                Layer.DOMAIN to listOf(":core"),
                Layer.ADAPTER to listOf(":core", ":integrations-x"),
                Layer.FEATURE to emptyList(),
                Layer.COMPOSITION to emptyList(),
                Layer.TOOLING to emptyList(),
            ),
            compositionRootFile = "app/Main.kt",
        )
        val problems = classificationProblems(map, setOf(":core", ":integrations-x", ":features-new"))
        assertTrue(problems.any { it.startsWith(":features-new") }) { "$problems" }
        assertTrue(problems.any { it.startsWith(":core") }) { "$problems" }
    }

    @Test
    fun `a package declared in two layers is a breach`() {
        val files = listOf(
            ScannedFile(":core", Layer.DOMAIN, "A.kt", "splice.shared", emptyList()),
            ScannedFile(":integrations-x", Layer.ADAPTER, "B.kt", "splice.shared", emptyList()),
        )
        val (_, problems) = packageOwners(files)
        assertEquals(1, problems.size) { "expected one split package, got $problems" }
    }

    @Test
    fun `the owner of an import is the longest declared package prefix`() {
        val owners = mapOf(
            "splice.core" to Layer.DOMAIN,
            "splice.core.util" to Layer.DOMAIN,
            "splice.up" to Layer.ADAPTER,
        )
        assertEquals(Layer.DOMAIN, ownerOf("splice.core.util.Json", owners))
        assertEquals(Layer.ADAPTER, ownerOf("splice.up.Client", owners))
        assertEquals(null, ownerOf("kotlinx.coroutines.Job", owners))
    }
}
