// NEW: V4-444 (heap) — where a session's transcript is, found once rather than on every Sessions poll. A 60 s JFR profile
// of the installed daemon (splice-lead, Oct 3, 6:39 PM CT) put about 28% of 0.8 GB/s of allocation on the Sessions route:
// every row's activity line located its transcript by listing every directory under each projects tree (5,393 on the
// operator's machine) and statting <dir>/<id>.jsonl in each until it hit, and a session with no transcript walked all of
// them on every poll.
//
// THE WALK IS UNCHANGED: the first projects tree in the caller's root order wins, each tree is walked once however many
// root names reach it (symlinked heads resolve to the vanilla tree), and inside a tree the first directory holding the
// file wins (TranscriptReader's header has the measured reasons). What changes is how often it runs:
//
// A FOUND TRANSCRIPT is remembered by its roots and session id. The next lookup checks that the file is still there and
// that nothing moved in the trees ahead of it in root order: each tree's own time (a new project directory) and, when the
// session's working directory was known, its project directory in each (a new transcript there). A copy appearing ahead
// would win the walk, so either change sends the lookup back to finding it. A transcript in the first tree has nothing
// ahead of it and costs one stat. One a lookup found in a later tree without the session's working directory is not
// remembered: nothing then says where a copy ahead of it would land, so that lookup walks as before.
//
// THE SESSION'S OWN PROJECT DIRECTORY is tried before any walk when the caller knows the session's working directory:
// Claude Code files a transcript under `projects/<cwd with every character that is not a letter or digit as '-'>/`
// (27 of 27 transcripts of the live sessions on the operator's machine, Oct 3). The probe trusts that placement: it takes
// the first tree whose own directory holds the transcript, and walks only when none does.
//
// A MISS is remembered with the modification times of every projects tree it walked and of the session's own project
// directory in each, and the walk runs again only when one of them moved. A new project directory moves its tree's time;
// a transcript Claude Code creates in an existing project directory moves that directory's time. So a transcript that
// appears after a miss is found on the next lookup, without walking the trees in between. A miss for a lookup that names
// no working directory is not remembered: nothing then says where a new transcript would land, so it walks as before.
// Both memories are bounded, least recently used first out.
package splice.client.transcript

import splice.client.Keys
import splice.core.util.Cancellables
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** The file operations locating a transcript costs: the seam a test counts them through. */
internal interface TranscriptFiles {
    /** The directories directly inside [dir]; none when it is absent or unreadable. */
    fun directories(dir: Path): List<Path>

    fun isFile(path: Path): Boolean

    /** When [path] was last modified, in nanoseconds since the epoch, or null when it does not exist or cannot be read.
     *  Nanoseconds, so a transcript created in the same millisecond a miss was stamped still moves its directory. */
    fun modified(path: Path): Long?

    /** [dir] with every symlink resolved, or its absolute name when it does not exist. */
    fun realPath(dir: Path): Path
}

internal object DiskTranscriptFiles : TranscriptFiles {
    override fun directories(dir: Path): List<Path> =
        // ast-grep-ignore: kt-no-silent-result-collapse -- an absent or unreadable projects tree holds no transcript, which is what Missing reports with every path it searched
        Cancellables.runCatchingCancellable { Files.newDirectoryStream(dir).use { it.filter(Files::isDirectory) } }
            .getOrDefault(emptyList())

    override fun isFile(path: Path): Boolean = Files.isRegularFile(path)

    override fun modified(path: Path): Long? =
        // ast-grep-ignore: kt-no-silent-result-collapse -- a directory that does not exist has no time; null is recorded as its state, so its creation moves the stamp
        Cancellables.runCatchingCancellable { Files.getLastModifiedTime(path).to(TimeUnit.NANOSECONDS) }.getOrNull()

    override fun realPath(dir: Path): Path =
        // ast-grep-ignore: kt-no-silent-result-collapse -- a projects dir that does not exist has no real path; its absolute name keys it, and walking it finds nothing
        Cancellables.runCatchingCancellable { dir.toRealPath() }.getOrDefault(dir.toAbsolutePath().normalize())
}

// why: well above the sessions one daemon lists at once, live and from history; past it the least recently used is
// forgotten and simply looked for again.
private const val REMEMBERED = 4096

internal class TranscriptLocator(private val files: TranscriptFiles = DiskTranscriptFiles) {
    private data class Key(val roots: List<Path>, val sessionId: String)

    /** The time of each directory a transcript appearing later would move, as it was when a lookup began: a projects
     *  tree's own (a new project directory) and, when the session's project directory name [own] was known, that
     *  directory in the tree (a new transcript in it). Null is a directory that did not exist; its creation moves it. */
    private data class Stamp(val times: Map<Path, Long?>, val own: String?)

    /** A transcript found, with the [stamp] of every tree ahead of it in root order: a copy appearing there would win. */
    private data class Found(val path: Path, val stamp: Stamp)

    private data class Hit(val tree: Int, val path: Path)

    private val found = Remembered<Key, Found>()
    private val missed = Remembered<Key, Stamp>()

    /** The first candidate in root order (the header says how it is found and when the walk runs). */
    fun locate(roots: List<Path>, sessionId: String, cwd: String? = null): Path? {
        val key = Key(roots, sessionId)
        val own = cwd?.let(::projectName)
        found[key]?.let { known -> if (trusted(known, own)) return known.path else found.remove(key) }
        if (missed[key]?.let { it.own == own && holds(it) } == true) return null
        return search(key, own)
    }

    /** The walk, with each tree's [Stamp] taken before it, so a transcript appearing during the walk is found by it or
     *  moves the stamp. */
    private fun search(key: Key, own: String?): Path? {
        val trees = trees(key.roots)
        val times = trees.map { tree -> stampOf(tree, own) }
        val hit = own?.let { ownDirectory(trees, it, key.sessionId) } ?: walk(trees, key.sessionId)
        if (hit != null) {
            missed.remove(key)
            if (hit.tree == 0 || own != null) found[key] = Found(hit.path, Stamp(merged(times.take(hit.tree)), own))
        } else if (own != null) {
            missed[key] = Stamp(merged(times), own)
        }
        return hit?.path
    }

    private fun merged(times: List<Map<Path, Long?>>): Map<Path, Long?> =
        times.fold(emptyMap()) { all, one -> all + one }

    /** A remembered transcript stands while it is a file and nothing moved ahead of it. One judged without the session's
     *  directory is judged again by a lookup that names it, so the copy a later directory holds is not missed. */
    private fun trusted(known: Found, own: String?): Boolean =
        (own == null || known.stamp.own == own) && files.isFile(known.path) && holds(known.stamp)

    private fun holds(stamp: Stamp): Boolean = stamp.times.all { (dir, at) -> files.modified(dir) == at }

    private fun stampOf(tree: Path, own: String?): Map<Path, Long?> =
        listOfNotNull(tree, own?.let(tree::resolve)).associateWith(files::modified)

    /** Each projects tree once, in root order: the symlinked heads' trees resolve to the vanilla one and are dropped. */
    private fun trees(roots: List<Path>): List<Path> {
        val walked = HashSet<Path>()
        return roots.map { it.resolve(Keys.PROJECTS) }.filter { walked.add(files.realPath(it)) }
    }

    private fun ownDirectory(trees: List<Path>, own: String, sessionId: String): Hit? =
        trees.withIndex().firstNotNullOfOrNull { (index, tree) ->
            tree.resolve(own).resolve("$sessionId.jsonl").takeIf(files::isFile)?.let { Hit(index, it) }
        }

    private fun walk(trees: List<Path>, sessionId: String): Hit? = trees.withIndex().asSequence()
        .flatMap { (index, tree) -> candidates(index, tree, sessionId) }
        .firstOrNull { files.isFile(it.path) }

    /** Where [sessionId]'s transcript would be in each project directory of [tree], listed when the walk reaches it. */
    private fun candidates(index: Int, tree: Path, sessionId: String): Sequence<Hit> =
        files.directories(tree).asSequence().map { Hit(index, it.resolve("$sessionId.jsonl")) }

    /** Claude Code's project directory name for a working directory: every character not a letter or digit as '-'. */
    private fun projectName(cwd: String): String = cwd.replace(nonAlphanumeric, "-")

    private val nonAlphanumeric = Regex("[^A-Za-z0-9]")

    /** A bounded map, least recently used first out, safe across concurrent Sessions requests. */
    private class Remembered<K : Any, V : Any> {
        private val entries = object : LinkedHashMap<K, V>(REMEMBERED, LOAD, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > REMEMBERED
        }

        operator fun get(key: K): V? = synchronized(entries) { entries[key] }

        operator fun set(key: K, value: V) {
            synchronized(entries) { entries[key] = value }
        }

        fun remove(key: K) {
            synchronized(entries) { entries.remove(key) }
        }
    }
}

// why: the JDK's default load factor; LinkedHashMap takes access order only through the constructor that also
// asks for it.
private const val LOAD = 0.75f
