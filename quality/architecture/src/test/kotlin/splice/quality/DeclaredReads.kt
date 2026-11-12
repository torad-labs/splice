// A law reads the tree only through the roots the build declares as inputs of this task. The build writes ONE list, hands it to
// the test JVM as `splice.declaredReadRoots` (';'-separated, repo-relative), and fingerprints the same list, so a read outside
// it cannot hide behind a cached green: it throws, by path, as a failing test.
package splice.quality

import splice.core.testing.LawReadSet
import java.io.File
import java.nio.file.Path

internal const val DECLARED_READ_ROOTS_PROPERTY: String = "splice.declaredReadRoots"

/** [file] when it lies under one of [roots] (resolved against [repoRoot]); otherwise throws naming the file and the roots. */
internal fun declaredRead(file: File, repoRoot: File, roots: List<String>): File {
    val target = file.canonicalFile
    val declared = roots.map { repoRoot.resolve(it).canonicalFile }
    check(declared.any { target.startsWith(it) }) {
        "a law read ${target.path}, which is outside the roots the build declares as inputs ($roots). Declare the " +
            "root in quality/architecture/build.gradle.kts, or the next edit to that file will not re-run the law."
    }
    return file
}

private fun property(name: String): String = checkNotNull(System.getProperty(name)) { "$name is not set" }

/** [declaredRead] against the roots this test JVM was handed. */
internal fun declaredRead(file: File): File {
    val repoRoot = File(property(ProjectMap.ROOT_PROPERTY))
    val roots = property(DECLARED_READ_ROOTS_PROPERTY).split(';').filter { it.isNotEmpty() }
    return declaredRead(file, repoRoot, roots)
}

internal const val CONVENTIONAL_CANDIDATES_PROPERTY: String = "splice.conventionalCandidatesFile"

/** The exact files the build declared as the conventional-type law's inputs, read through the one strict reader of the list
 *  (LawReadSet: UTF-8, a NUL after every name, no empty field, no repeat). A read of any other file throws, by path. */
internal fun declaredCandidates(): LawReadSet =
    LawReadSet(File(property(ProjectMap.ROOT_PROPERTY)).toPath(), Path.of(property(CONVENTIONAL_CANDIDATES_PROPERTY)))
