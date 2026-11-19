// Every law that reads a module's declarations through Konsist reads them from ONE parse of that module, shared by the
// whole suite. Konsist keeps each declaration it hands out in a static cache keyed by the parsed element, for the life
// of the test JVM (KoDeclarationCache, konsist 0.18.1), so a fresh scope of a tree already parsed was another copy of
// that tree's syntax kept in memory to the end of the run. Until Nov 19, 2026 seven laws each parsed every module's
// production tree for themselves; the suite's live set had passed 2 GB by Oct 10 (the heap note in build.gradle.kts).
package splice.quality

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal object LawScopes {
    /** The root Konsist resolves a path against: the repository's. */
    private val repository: File = ProjectMap.fromSystemProperties().root.absoluteFile
    private val parsed = ConcurrentHashMap<String, KoScope>()

    /** The scope of [tree], a directory of [map]'s. The repository's own trees are parsed once per test JVM, since
     *  nothing rewrites them during the run; a synthetic tree a law's test writes is parsed at each call, since the
     *  test rewrites it between audits. */
    fun of(map: ProjectMap, tree: File): KoScope {
        // A tree anywhere else (a temp one) is handed to Konsist relative to the repository root.
        val relative = repository.toPath().relativize(tree.absoluteFile.toPath()).toString()
        if (map.root.absoluteFile != repository) return Konsist.scopeFromDirectory(relative)
        return parsed.computeIfAbsent(relative) { Konsist.scopeFromDirectory(it) }
    }

    /** [module]'s production Kotlin, the tree [ProjectMap.mainSources] names. */
    fun main(map: ProjectMap, module: String): KoScope = of(map, map.mainSources(module))
}
