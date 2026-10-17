// NEW: Oct 12, 2026 (splice-lead) — the nested repositories below the root, read as a VALUE the configuration cache compares,
// never as the directories the walk opened.
//
// The walk used to run inside quality/architecture/build.gradle.kts with File.listFiles, so Gradle recorded every directory
// it listed as a configuration input: any file added or removed anywhere under the root (a new test, a seat's scratch file)
// threw the configuration cache away, and every run stored a new entry and a new 65 MB report. As a ValueSource the walk
// still runs at the start of each build, and the cache is reused while the answer it gives is the same list.
package splice.lawsuite

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File

abstract class NestedRepositories : ValueSource<List<String>, NestedRepositories.Parameters> {
    interface Parameters : ValueSourceParameters {
        val root: DirectoryProperty

        /** Directory names the walk never enters. */
        val notSwept: ListProperty<String>
    }

    /** Each directory below the root that carries a `.git` entry, root-relative and sorted. The walk records one and never
     *  enters it: descending would read a whole second checkout to reach directories the census is about to exclude. */
    override fun obtain(): List<String> {
        val root = parameters.root.get().asFile
        val skipped = parameters.notSwept.get().toSet()
        return below(root, skipped).map { it.relativeTo(root).invariantSeparatorsPath }.sorted()
    }

    private fun below(dir: File, skipped: Set<String>): List<File> =
        dir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name !in skipped }
            .flatMap { child -> if (File(child, ".git").exists()) listOf(child) else below(child, skipped) }
}
