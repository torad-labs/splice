// NEW: the laws' project map (restructure P0) — which Gradle module lives in which directory, and
// the single channel every law reads that fact through.
//
// THE DEFECT THIS REMOVES. Until this file the laws derived their subject from the SHAPE OF THE
// ROOT: ArchitectureLawsTest listed the root's immediate child directories, and ModuleLawsTest
// resolved `:<id>` to `root/<id>/build.gradle.kts`, returning emptySet() when that file was
// missing. Both readings are true only while every module is a direct child of the Gradle root. A
// module under `integrations/dialects/anthropic` or `integrations/providers/openai` would stop being graded SILENTLY, with
// every law still reporting green — the same fail-open shape DR-165 removed from the slot-header
// law one level up, where a denominator taken from the list being checked cannot fail for what the
// list omits.
//
// THE SOURCE OF TRUTH IS GRADLE'S OWN PROJECT MODEL, handed to this JVM by
// quality/architecture/build.gradle.kts as one system property ([ProjectMap.PROPERTY]) — never a
// regex over settings.gradle.kts, which is a second reading of the same fact and can disagree with
// the build that actually runs. The channel is parsed HERE and nowhere else, and it fails BY NAME
// when it is absent or malformed: a law that cannot see the modules must not pass.
package splice.quality

import java.io.File

/** Gradle path (`:daemon-head`) -> the module's directory relative to the Gradle root (`daemon/head`). */
internal class ProjectMap private constructor(
    /** The Gradle root every relative directory in this map resolves against. */
    val root: File,
    private val directories: Map<String, String>,
    /** Directory NAMES the unmapped-source sweep never enters: generated output and tool state.
     *  Handed in by the build ([CENSUS_PROPERTY]), never a constant here, because the build
     *  fingerprints exactly this tree as the task's census input. A name the build excluded and
     *  this sweep entered is a tree whose new sources leave the task UP-TO-DATE — the sweep never
     *  runs, and the unclaimed tree passes in silence. */
    private val notSwept: Set<String>,
) {
    /** Every module the BUILD declares — the denominator every module-shaped law counts against. */
    val modules: Set<String> = directories.keys

    /** The module's directory relative to [root]: the form violation messages name. */
    fun relativeDir(module: String): String = directories[module] ?: error(
        "the project map has no $module — the laws grade the modules the build declares, so a name " +
            "this file asks for and the build does not include is a stale entry, not a gap.",
    )

    /** The module's directory. */
    fun dir(module: String): File = File(root, relativeDir(module))

    /** The module's production sources, which a module need not ship (a harness ships none). */
    fun mainSources(module: String): File = File(dir(module), "src/main/kotlin")

    /** The module's build file — a HARD FAILURE when it is missing. The resolver this replaces
     *  returned emptySet() for a build file it could not find, so a module that moved into a
     *  subdirectory lost its dependency grading entirely while the law still reported green. */
    fun buildFile(module: String): File {
        val file = File(dir(module), "build.gradle.kts")
        check(file.isFile) {
            "the project map places $module at ${relativeDir(module)}, which ships no " +
                "build.gradle.kts — a module whose build file cannot be read is UNGRADED, not " +
                "compliant. Fix the module's directory or restore its build file."
        }
        return file
    }

    /** Directories that ship production Kotlin and that NO module in this map claims, as violation
     *  lines. The other half of the silent-loss pair: a source tree the build never included is a
     *  tree every law walks past, and nothing in the tree says so. */
    fun unmappedProductionDirViolations(): List<String> =
        swept(root)
            .filter { it.isDirectory && it.invariantSeparatorsPath.endsWith(MAIN_SOURCES) }
            .filter { main -> swept(main).any { it.isFile && it.extension == "kt" } }
            .map { it.parentFile.parentFile.parentFile.relativeTo(root).invariantSeparatorsPath }
            .filterNot { it in directories.values }
            .sorted()
            .map { directory ->
                "$directory ships production Kotlin under src/main/kotlin and the project map " +
                    "claims no module there — include it in settings.gradle.kts so every law " +
                    "grades it, or delete the sources; a module the build never included is " +
                    "silently ungoverned."
            }
            .toList()

    /** THE ONE TRAVERSAL both walks use — never below a directory whose name is on the list, and
     *  never into another repository's tree. It is the same bound the build's census input applies,
     *  so a file the build cannot see (Kotlin under a `build/` inside a source root, say) cannot
     *  decide this sweep's verdict either: an incremental run and a forced run must agree about
     *  every file.
     *
     *  THE `.git` RULE (PR 2 review). A directory BELOW [from] that carries a `.git` entry — a FILE
     *  reading `gitdir: …` for a worktree, a DIRECTORY for a submodule or a vendored clone — is
     *  where another repository starts, and its sources are not this build's to grade. With the
     *  Gradle root at the repository root this sweep reaches the whole checkout, and the real one
     *  carries `.claude/worktrees/<name>/` with a complete gateway/core/src/main/kotlin inside it:
     *  the law named `.claude/worktrees/v0.4.0/gateway/core` against mapped `gateway/core`. [from]
     *  itself is exempt because the repository root carries `.git` too, and pruning it would blank
     *  the sweep — a law that reports nothing is the fail-open shape this file exists against.
     *  quality/architecture/build.gradle.kts applies the same rule to the task's census fingerprint;
     *  a tree one of them reads and the other does not is an UP-TO-DATE green over an unrun sweep. */
    private fun swept(from: File): Sequence<File> =
        from.walkTopDown().onEnter { it.name !in notSwept && (it == from || !File(it, ".git").exists()) }

    internal companion object {
        /** The channel: `:path=directory` pairs, `;`-separated, written by quality/architecture/build.gradle.kts.kts. */
        const val PROPERTY: String = "splice.projectMap"

        /** The absolute Gradle root the laws read the tree from — the REPOSITORY root since the
         *  build root moved there (restructure PR 2), which is why the property is not `gateway.*`. */
        const val ROOT_PROPERTY: String = "splice.root"

        /** The census channel: the directory names the build's census input excludes, `;`-separated,
         *  written by the same build script as [PROPERTY]. */
        const val CENSUS_PROPERTY: String = "splice.censusNotSwept"

        /** The map this test JVM was handed. */
        fun fromSystemProperties(): ProjectMap {
            val root = System.getProperty(ROOT_PROPERTY)
            check(!root.isNullOrBlank()) {
                "no -D$ROOT_PROPERTY: the laws read the source tree through it, and a law that " +
                    "cannot find the tree must fail rather than grade nothing."
            }
            return parse(File(root), System.getProperty(PROPERTY), notSweptFrom(System.getProperty(CENSUS_PROPERTY)))
        }

        /** PURE, so every way the channel can break is provable against synthetic input rather than
         *  only against a build that happens to be configured correctly. */
        fun parse(root: File, raw: String?, notSwept: Set<String>): ProjectMap {
            check(!raw.isNullOrBlank()) {
                "no -D$PROPERTY: the laws grade the modules the BUILD declares, and an absent map " +
                    "grades nothing while every law still reports green. " +
                    "quality/architecture/build.gradle.kts is what supplies it."
            }
            val directories = mutableMapOf<String, String>()
            raw.split(ENTRY_SEPARATOR).forEach { entry ->
                val module = entry.substringBefore(PAIR_SEPARATOR, "")
                val directory = entry.substringAfter(PAIR_SEPARATOR, "")
                check(module.startsWith(":") && directory.isNotBlank() && !directory.startsWith("/")) {
                    "$PROPERTY entry '$entry' is not ':<gradle path>=<directory relative to the " +
                        "repository root>' — a half-read map is a map that drops modules silently."
                }
                val clash = directories.put(module, directory)
                check(clash == null) {
                    "$PROPERTY maps $module twice ('$clash' and '$directory') — the build cannot " +
                        "place one module in two directories; the channel is corrupt."
                }
            }
            return ProjectMap(root, directories.toMap(), notSwept)
        }

        /** PURE, like [parse]: the census channel's own failure is provable against synthetic input.
         *  Absent or blank fails BY NAME — a sweep without the build's list would either walk trees
         *  the build never fingerprints (a stale UP-TO-DATE green) or invent a list of its own. */
        fun notSweptFrom(raw: String?): Set<String> {
            check(!raw.isNullOrBlank()) {
                "no -D$CENSUS_PROPERTY: the unmapped-source sweep skips exactly the directory names " +
                    "the build's census input excludes, and a sweep that cannot see that list would " +
                    "walk trees the build never fingerprints. quality/architecture/build.gradle.kts " +
                    "is what supplies it."
            }
            return raw.split(ENTRY_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }

        private const val ENTRY_SEPARATOR = ";"
        private const val PAIR_SEPARATOR = "="
        private const val MAIN_SOURCES = "/src/main/kotlin"
    }
}
