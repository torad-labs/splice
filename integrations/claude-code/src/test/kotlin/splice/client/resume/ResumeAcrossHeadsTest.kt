// NEW: V4-115 cross-head resume as an explicit COPY (2026-09-17). The shared projects tree is gone
// (three heads linked into the operator's vanilla ~/.claude/projects and 95 head transcripts landed
// there), so the only way a session started on one head can be resumed on another is a copy made at
// launch time, on demand, for one named id. These tests pin the properties that make that copy safe:
// the SOURCE is never written, nothing lands as a link, only the calling head's own tree is searched
// for a local hit, and the copy's assistant models follow the head that is resuming them — which is
// what stops Claude Code's "Session model X could not be restored".
package splice.client.resume

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.Keys
import splice.client.resume.originals.TranscriptOriginals
import splice.core.config.StatePaths
import splice.core.util.DirectoryListing
import splice.core.util.FilesListing
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import java.io.InputStream
import java.net.URI
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessDeniedException
import java.nio.file.AccessMode
import java.nio.file.CopyOption
import java.nio.file.DirectoryStream
import java.nio.file.FileStore
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import java.nio.file.attribute.UserPrincipalLookupService
import java.nio.file.spi.FileSystemProvider
import kotlin.io.path.isSymbolicLink

/** A daemon halt, as the copy it cuts sees it: the session gives no more bytes, and no catch in the code runs, since a
 *  halted process runs none. runCatchingCancellable lets an Error through, so nothing is cleaned up here either. */
private class Halted : Error("the daemon halted partway through the copy")

/** [real]'s bytes while [tree] has any left to give, then the halt. */
private class HaltedRead(private val real: InputStream, private val tree: HaltingTree) : InputStream() {
    override fun read(): Int {
        if (tree.left == 0) throw Halted()
        val byte = real.read()
        if (byte >= 0) tree.left--
        return byte
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (tree.left == 0) throw Halted()
        val count = real.read(bytes, offset, minOf(length, tree.left))
        if (count > 0) tree.left -= count
        return count
    }

    override fun close() = real.close()
}

/** A path of [tree]: [real], read through the tree. A name resolved in it stays in the tree, and so does the session's
 *  subdir beside its transcript, whose subagent files the copy reads too. Any other sibling is the real one, so
 *  whatever a copy writes beside its source is a real file. */
private class TreePath(val real: Path, private val tree: HaltingTree) : Path by real {
    override fun getFileSystem(): FileSystem = tree.fileSystem

    override fun resolve(other: String): Path = TreePath(real.resolve(other), tree)

    override fun resolveSibling(other: String): Path {
        val sibling = real.resolveSibling(other)
        return if (real.fileName.toString() == other + TRANSCRIPT_SUFFIX) TreePath(sibling, tree) else sibling
    }

    override fun relativize(other: Path): Path = real.relativize((other as? TreePath)?.real ?: other)

    override fun toString(): String = real.toString()
}

/** A session directory as a copy the daemon halts partway through reads it: listed, checked and read like the real one,
 *  except that its files give [left] bytes in all, the subagent files' as well as the transcript's, and the read after
 *  the last of them halts. */
private class HaltingTree(var left: Int) : FileSystemProvider() {
    private val disk = FileSystems.getDefault().provider()
    val fileSystem: FileSystem = TreeFileSystem(this)

    fun dir(path: Path): Path = TreePath(path, this)

    private fun real(path: Path): Path = (path as? TreePath)?.real ?: path

    override fun newInputStream(path: Path, vararg options: OpenOption): InputStream =
        HaltedRead(disk.newInputStream(real(path), *options), this)

    /** Its entries stay in the tree, so a walk of the session's subdir reads each file in it through the halt. */
    override fun newDirectoryStream(dir: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> {
        val entries = disk.newDirectoryStream(real(dir), filter)
        return object : DirectoryStream<Path> {
            override fun iterator(): MutableIterator<Path> =
                entries.mapTo(mutableListOf<Path>()) { TreePath(it, this@HaltingTree) }.iterator()

            override fun close() = entries.close()
        }
    }

    override fun getScheme(): String = disk.scheme
    override fun newFileSystem(uri: URI, env: MutableMap<String, *>): FileSystem = disk.newFileSystem(uri, env)
    override fun getFileSystem(uri: URI): FileSystem = disk.getFileSystem(uri)
    override fun getPath(uri: URI): Path = disk.getPath(uri)
    override fun newByteChannel(
        path: Path,
        options: MutableSet<out OpenOption>,
        vararg attrs: FileAttribute<*>,
    ): SeekableByteChannel = disk.newByteChannel(real(path), options, *attrs)
    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) = disk.createDirectory(real(dir), *attrs)
    override fun delete(path: Path) = disk.delete(real(path))
    override fun copy(source: Path, target: Path, vararg options: CopyOption) =
        disk.copy(real(source), real(target), *options)
    override fun move(source: Path, target: Path, vararg options: CopyOption) =
        disk.move(real(source), real(target), *options)
    override fun isSameFile(path: Path, other: Path): Boolean = disk.isSameFile(real(path), real(other))
    override fun isHidden(path: Path): Boolean = disk.isHidden(real(path))
    override fun getFileStore(path: Path): FileStore = disk.getFileStore(real(path))
    override fun checkAccess(path: Path, vararg modes: AccessMode) = disk.checkAccess(real(path), *modes)
    override fun <V : FileAttributeView> getFileAttributeView(
        path: Path,
        type: Class<V>,
        vararg options: LinkOption,
    ): V? = disk.getFileAttributeView(real(path), type, *options)
    override fun <A : BasicFileAttributes> readAttributes(path: Path, type: Class<A>, vararg options: LinkOption): A =
        disk.readAttributes(real(path), type, *options)
    override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): MutableMap<String, Any> =
        disk.readAttributes(real(path), attributes, *options)
    override fun setAttribute(path: Path, attribute: String, value: Any, vararg options: LinkOption) =
        disk.setAttribute(real(path), attribute, value, *options)
}

/** The file system [tree]'s paths answer to: the default one, with the tree as its provider. */
private class TreeFileSystem(private val tree: HaltingTree) : FileSystem() {
    private val disk = FileSystems.getDefault()

    override fun provider(): FileSystemProvider = tree
    override fun close() = Unit
    override fun isOpen(): Boolean = true
    override fun isReadOnly(): Boolean = false
    override fun getSeparator(): String = disk.separator
    override fun getRootDirectories(): Iterable<Path> = disk.rootDirectories
    override fun getFileStores(): Iterable<FileStore> = disk.fileStores
    override fun supportedFileAttributeViews(): Set<String> = disk.supportedFileAttributeViews()
    override fun getPath(first: String, vararg more: String): Path = disk.getPath(first, *more)
    override fun getPathMatcher(syntaxAndPattern: String): PathMatcher = disk.getPathMatcher(syntaxAndPattern)
    override fun getUserPrincipalLookupService(): UserPrincipalLookupService = disk.userPrincipalLookupService
    override fun newWatchService(): WatchService = disk.newWatchService()
}

class ResumeAcrossHeadsTest {

    private val pinned = "head-model"

    @TempDir lateinit var state: Path

    private fun resumer(): ResumeAcrossHeads =
        ResumeAcrossHeads(TranscriptModelRewrite(originals = TranscriptOriginals(StatePaths(baseOverride = state))))

    private fun write(path: Path, text: String): Path {
        Files.createDirectories(path.parent)
        return Files.writeString(path, text)
    }

    /** Claude Code's projects/ subdir name: the absolute cwd with every non-alphanumeric run to `-`. */
    private fun encodedCwd(name: String): String = "-home-operator-$name"

    private fun headConfig(home: Path, key: String): Path = Files.createDirectories(home.resolve(".claude-$key"))

    /** user row, assistant row on [model], and a row that HAS a message but is not an assistant row. */
    private fun transcript(sessionId: String, model: String): String =
        """{"type":"user","sessionId":"$sessionId","message":{"role":"user","content":"hi"}}
{"type":"assistant","sessionId":"$sessionId","message":{"id":"m1","model":"$model","content":[]}}
{"type":"summary","sessionId":"$sessionId","message":{"model":"$model"}}
"""

    private fun register(home: Path, key: String, cwdDir: String, sessionId: String, model: String): Path =
        write(
            headConfig(home, key).resolve(Keys.PROJECTS).resolve(cwdDir).resolve("$sessionId.jsonl"),
            transcript(sessionId, model),
        )

    private fun lines(text: String): List<String> = text.split("\n")

    private fun modelOf(row: String): String? =
        JsonScalars.str(Json.parseToJsonElement(row).jsonObject["message"] as? JsonObject, Keys.MODEL)

    private fun adoption(calling: Path, others: List<Path>, sessionId: String): SessionAdoption =
        resumer().adopt(calling, others, sessionId, CallingRoster(pinned, listOf(pinned)), log = {})

    @Test
    fun `a foreign session is copied in, its models follow the head, and the source is untouched`(@TempDir home: Path) {
        val calling = headConfig(home, "codex")
        val source = register(home, "kimi", encodedCwd("repo"), "abc-123", model = "k3-256k")
        val sourceText = Files.readString(source)
        val copy = calling.resolve(Keys.PROJECTS).resolve(encodedCwd("repo")).resolve("abc-123.jsonl")
        assertFalse(Files.exists(copy, NOFOLLOW_LINKS), "the picker input is this head's tree only")

        val adopted = adoption(calling, listOf(headConfig(home, "kimi")), "abc-123") as SessionAdoption.Adopted

        assertEquals(1, adopted.modelsRewritten, "exactly the assistant row carries a model")
        assertEquals(copy, adopted.into, "the copy lands in THIS head's tree, under the source's encoded cwd")
        assertEquals(source, adopted.from)
        assertFalse(copy.isSymbolicLink(), "the adoption must be a real file, never a link")
        assertEquals(1, Files.getAttribute(copy, "unix:nlink") as Int, "no hardlink back to the source")
        assertEquals(pinned, modelOf(lines(Files.readString(copy))[1]), "the assistant model follows the resuming head")
        assertEquals(sourceText, Files.readString(source), "the source transcript must be byte-identical")
        assertNotEquals(sourceText, Files.readString(copy), "the copy is rewritten, so it is not the same bytes")
    }

    @Test
    fun `rows that need no rewrite are copied byte-identical`(@TempDir home: Path) {
        val calling = headConfig(home, "codex")
        val source = register(home, "kimi", encodedCwd("repo"), "abc-123", model = pinned)
        val before = lines(Files.readString(source))

        val adopted = adoption(calling, listOf(headConfig(home, "kimi")), "abc-123") as SessionAdoption.Adopted

        assertEquals(0, adopted.modelsRewritten, "the assistant row already names this head's model")
        val after = lines(Files.readString(adopted.into))
        assertEquals(before[0], after[0], "a user row is never re-encoded")
        assertEquals(before[1], after[1], "an assistant row on the right model is never re-encoded")
        assertEquals(before[2], after[2], "a non-assistant row with a message is not an assistant message")
    }

    @Test
    fun `the calling head's own session wins, and the other head is never rewritten`(@TempDir home: Path) {
        val calling = headConfig(home, "codex")
        val mine = register(home, "codex", encodedCwd("repo"), "abc-123", model = pinned)
        val theirs = register(home, "kimi", encodedCwd("repo"), "abc-123", model = "k3-256k")
        val theirsText = Files.readString(theirs)

        val adoption = adoption(calling, listOf(headConfig(home, "kimi")), "abc-123")

        assertEquals(mine, (adoption as SessionAdoption.HeadOwned).transcript)
        assertEquals(0, adoption.modelsRewritten, "already on this head's model: nothing to move")
        assertEquals(theirsText, Files.readString(theirs), "an unadopted head's transcript is not touched")
    }

    // V4-169 as the President ruled it (Oct 13): on the shared tree (V4-168) every head's -r SESSION_ID finds the
    // session in its OWN tree, whichever head wrote it, and one whose rows would move goes on as a copy beside it under
    // a new id, its rows naming that id. The original and its subdir stay byte for byte, for any other head.
    @Test
    fun `a session another head wrote goes on as a copy beside it, and the original is untouched`(@TempDir home: Path) {
        val calling = headConfig(home, "codex")
        val original = register(home, "codex", encodedCwd("repo"), "abc-123", model = "k3-256k")
        write(original.resolveSibling("abc-123").resolve("subagents/agent-1.jsonl"), transcript("abc-123", "k3-256k"))
        val before = files(home)

        val copied = adoption(calling, emptyList(), "abc-123") as SessionAdoption.Copied

        assertEquals(2, copied.modelsRewritten, "the transcript and its subdir")
        assertEquals(before, files(home).filterKeys { it in before }, "the original and its subdir are untouched")
        assertEquals(original.resolveSibling("${copied.copyId}.jsonl"), copied.into, "beside the original")
        val rows = lines(Files.readString(copied.into)).filter { it.isNotBlank() }
        assertEquals(pinned, modelOf(rows[1]), "moved onto this head's model")
        assertEquals("k3-256k", modelOf(rows[2]), "a non-assistant row keeps its model")
        val named = "\"sessionId\":\"${copied.copyId}\""
        assertTrue(rows.all { named in it }, "every row names the copy")
        val agent = copied.into.resolveSibling(copied.copyId).resolve("subagents/agent-1.jsonl")
        assertEquals(pinned, modelOf(lines(Files.readString(agent))[1]), "the subagent's rows move too")
        assertTrue(named in Files.readString(agent), "and name the copy")
    }

    // Review of PR #247, round 6 (Oct 26 CT): both copies wrote the transcript straight under its final name, and a
    // halted daemon runs no cleanup, so a halt partway left a cut-short transcript there. On a head that isolates its
    // projects the next -r resumed it as the head's own, and on the shared tree every head's picker listed it. Round 8
    // found the same for the subagent files: the transcript took its name before they were copied, so a halt among them
    // left a whole transcript beside a cut subagent file, which that head resumed from then on. A halt can land after
    // any byte, so each test reruns the -r with the copy halted after every byte the session has, its subagent file's
    // included, as a person reruns it after each failed launch, and then once more with nothing halting it.
    @Test
    fun `a copy in that the daemon halts partway leaves nothing to resume, and the next -r copies it whole`(
        @TempDir home: Path,
    ) {
        val calling = headConfig(home, "codex")
        val kimi = headConfig(home, "kimi")
        val source = register(home, "kimi", encodedCwd("repo"), "abc-123", model = "k3-256k")
        val agent = write(source.resolveSibling("abc-123/subagents/agent-1.jsonl"), transcript("abc-123", "k3-256k"))
        val into = calling.resolve(Keys.PROJECTS).resolve(encodedCwd("repo"))

        for (kept in 0 until (Files.size(source) + Files.size(agent)).toInt()) {
            halted(source.parent, kept, calling, listOf(kimi))
            assertEquals(emptyMap<String, Int>(), sessions(into), "halted after $kept bytes: nothing to resume")
        }

        val next = adoption(calling, listOf(kimi), "abc-123") as SessionAdoption.Adopted
        assertEquals(source, next.from, "the next -r copies from the intact source")
        assertEquals(sessions(source.parent), sessions(into), "all of it, its subagent file included")
    }

    @Test
    fun `a copy beside the original that the daemon halts partway leaves the picker no cut session`(
        @TempDir home: Path,
    ) {
        val calling = headConfig(home, "codex")
        val original = register(home, "codex", encodedCwd("repo"), "abc-123", model = "k3-256k")
        val agent = write(original.resolveSibling("abc-123/subagents/agent-1.jsonl"), transcript("abc-123", "k3-256k"))
        val alone = sessions(original.parent)

        for (kept in 0 until (Files.size(original) + Files.size(agent)).toInt()) {
            halted(original.parent, kept, calling, emptyList())
            assertEquals(alone, sessions(original.parent), "halted after $kept bytes: the original is the only session")
        }

        val next = adoption(calling, emptyList(), "abc-123") as SessionAdoption.Copied
        val copy = alone.mapKeys { (path, _) -> path.replace("abc-123", next.copyId) }
        assertEquals(alone + copy, sessions(original.parent), "the original and all of its copy, its subagent file too")
    }

    /** The sessions in [dir] as Claude Code's -r picker and resume find them: each transcript and every file in its
     *  subdir, by path, with the rows it holds whole, so a session listed beside a file cut short shows it. */
    private fun sessions(dir: Path): Map<String, Int> = transcripts(dir).flatMap { name ->
        val subdir = dir.resolve(name.removeSuffix(".jsonl"))
        val under = if (Files.isDirectory(subdir)) {
            Files.walk(subdir).use { paths -> paths.filter { Files.isRegularFile(it) }.toList() }
        } else {
            emptyList()
        }
        (listOf(dir.resolve(name)) + under).map { file -> dir.relativize(file).toString() to rows(file) }
    }.toMap()

    /** The rows [file] holds whole: a copy cut short ends partway through one, or before the first. */
    private fun rows(file: Path): Int = Files.readAllBytes(file).count { it == '\n'.code.toByte() }

    /** A -r launch whose copy the daemon halts after [kept] bytes of the session in [sessionDir], its transcript's and
     *  its subagent files' together: that directory is listed as its head lists it, and nothing after the halt runs, so
     *  the disk is left as the halt leaves it. */
    private fun halted(sessionDir: Path, kept: Int, calling: Path, others: List<Path>) {
        val tree = HaltingTree(kept)
        val listing = DirectoryListing { dir ->
            FilesListing.list(dir).map { entry -> if (entry == sessionDir) tree.dir(entry) else entry }
        }
        val resume = ResumeAcrossHeads(resumer().rewriter, listing)
        val roster = CallingRoster(pinned, listOf(pinned))
        assertThrows(Halted::class.java) { resume.adopt(calling, others, "abc-123", roster, log = {}) }
    }

    /** The sessions in [dir] as Claude Code's -r picker and resume find them: its transcripts, by name. */
    private fun transcripts(dir: Path): List<String> = Files.list(dir).use { entries ->
        entries.map { it.fileName.toString() }.filter { it.endsWith(".jsonl") }.sorted().toList()
    }

    @Test
    fun `same encoded cwd is preferred over any other`(@TempDir home: Path) {
        val calling = headConfig(home, "codex")
        // this head knows the repo cwd: it holds a tree for it, just not this session
        write(calling.resolve(Keys.PROJECTS).resolve(encodedCwd("repo")).resolve("unrelated.jsonl"), "{}\n")
        register(home, "kimi", encodedCwd("elsewhere"), "abc-123", model = "k3-256k")
        val wanted = register(home, "kimi", encodedCwd("repo"), "abc-123", model = "k3-256k")

        val adopted = adoption(calling, listOf(headConfig(home, "kimi")), "abc-123") as SessionAdoption.Adopted

        assertEquals(encodedCwd("repo"), adopted.into.parent.fileName.toString(), "the cwd this head knows wins")
        assertEquals(wanted, adopted.from)
    }

    @Test
    fun `the launch cwd picks the copy even when this head holds no tree for it`(@TempDir home: Path) {
        val calling = headConfig(home, "codex")
        register(home, "kimi", encodedCwd("elsewhere"), "abc-123", model = "k3-256k")
        val wanted = register(home, "kimi", encodedCwd("repo"), "abc-123", model = "k3-256k")

        val adopted = resumer().adopt(
            calling,
            listOf(headConfig(home, "kimi")),
            "abc-123",
            CallingRoster(pinned, listOf(pinned), cwd = "/home/operator/repo"),
            log = {},
        ) as SessionAdoption.Adopted

        assertEquals(wanted, adopted.from, "the copy under the launch cwd wins over the one found first")
        assertEquals(encodedCwd("repo"), adopted.into.parent.fileName.toString())
    }

    @Test
    fun `the session subdir is copied, and a link inside it is copied as content`(@TempDir home: Path) {
        val calling = headConfig(home, "codex")
        val source = register(home, "kimi", encodedCwd("repo"), "abc-123", model = "k3-256k")
        val subdir = source.parent.resolve("abc-123")
        write(subdir.resolve("subagents/agent-1.jsonl"), transcript("abc-123", "k3-256k"))
        // Claude Code writes subagent transcripts as absolute symlinks; a verbatim copy would point
        // back into the source head's tree and re-open the leak this row removes.
        Files.createSymbolicLink(subdir.resolve("tool-results.jsonl"), source)

        val adopted = adoption(calling, listOf(headConfig(home, "kimi")), "abc-123") as SessionAdoption.Adopted

        val copiedSubdir = adopted.into.parent.resolve("abc-123")
        assertTrue(Files.isRegularFile(copiedSubdir.resolve("subagents/agent-1.jsonl")), "the subdir tree is copied")
        val fromLink = copiedSubdir.resolve("tool-results.jsonl")
        assertFalse(fromLink.isSymbolicLink(), "a link is copied as CONTENT, never as a link")
        assertTrue(Files.isRegularFile(fromLink, NOFOLLOW_LINKS))
        assertEquals(pinned, modelOf(lines(Files.readString(fromLink))[1]), "the linked transcript is rewritten too")
    }

    @Test
    fun `an id in no head's tree is Absent and nothing is written`(@TempDir home: Path) {
        val calling = headConfig(home, "codex")
        val other = headConfig(home, "kimi")
        register(home, "kimi", encodedCwd("repo"), "other-session", model = "k3-256k")

        val adoption = adoption(calling, listOf(other), "abc-123")

        assertEquals("abc-123", (adoption as SessionAdoption.Absent).sessionId)
        assertFalse(
            Files.exists(calling.resolve(Keys.PROJECTS), NOFOLLOW_LINKS),
            "a resume that found nothing must not create a tree or a file",
        )
    }

    @Test
    fun `an empty primary transcript is counted but never offered as a resume recipe`(@TempDir home: Path) {
        val calling = headConfig(home, "codex")
        val other = headConfig(home, "kimi")
        val id = "empty-1"
        val foreign = write(other.resolve(Keys.PROJECTS).resolve(encodedCwd("repo")).resolve("$id.jsonl"), "")
        val resume = resumer()
        assertTrue(Files.isRegularFile(foreign), "the refusal distinguishes an empty file from no file")
        assertEquals(foreign, (resume.plan(calling, listOf(other), id, log = {}) as ResumePlan.Empty).transcript)
        assertEquals(foreign, (adoption(calling, listOf(other), id) as SessionAdoption.Empty).transcript)
        assertFalse(Files.exists(calling.resolve(Keys.PROJECTS), NOFOLLOW_LINKS))

        val own = write(calling.resolve(Keys.PROJECTS).resolve(encodedCwd("repo")).resolve("$id.jsonl"), "")
        assertTrue(Files.isRegularFile(own))
        assertEquals(own, (resume.plan(calling, listOf(other), id, log = {}) as ResumePlan.Empty).transcript)
    }

    @Test
    fun `an empty own copy falls through to a sibling that has conversation bytes`(@TempDir home: Path) {
        val calling = headConfig(home, "codex")
        val id = "empty-1"
        write(calling.resolve(Keys.PROJECTS).resolve(encodedCwd("repo")).resolve("$id.jsonl"), "")
        val foreign = register(home, "kimi", encodedCwd("repo"), id, model = pinned)
        val other = headConfig(home, "kimi")
        val plan = resumer().plan(calling, listOf(other), id, log = {}) as ResumePlan.Copy
        assertEquals(foreign, plan.from)
        val adopted = adoption(calling, listOf(other), id) as SessionAdoption.Adopted
        assertTrue(Files.size(adopted.into) > 0L, "the empty own file was replaced by real conversation bytes")
    }

    // V4-320: plan() is the resolution adopt() acts on, asked without acting, so a resume recipe and the
    // `-r` launch it describes cannot disagree. On one fixture, each plan names what the adoption then
    // does, and asking every plan writes nothing.
    @Test
    fun `a plan names what the adoption then does, and writes nothing itself`(@TempDir home: Path) {
        val calling = headConfig(home, "codex")
        val kimi = headConfig(home, "kimi")
        write(calling.resolve(Keys.PROJECTS).resolve(encodedCwd("repo")).resolve("unrelated.jsonl"), "{}\n")
        register(home, "kimi", encodedCwd("elsewhere"), "abc-123", model = "k3-256k")
        val foreign = register(home, "kimi", encodedCwd("repo"), "abc-123", model = "k3-256k")
        val own = register(home, "codex", encodedCwd("repo"), "own-1", model = pinned)
        val resume = resumer()
        val before = files(home)

        val copy = resume.plan(calling, listOf(kimi), "abc-123", log = {}) as ResumePlan.Copy
        val owned = resume.plan(calling, listOf(kimi), "own-1", log = {}) as ResumePlan.Owned
        val absent = resume.plan(calling, listOf(kimi), "nowhere-9", log = {}) as ResumePlan.Absent
        assertTrue(resume.plan(calling, listOf(kimi), "../x", log = {}) is ResumePlan.Invalid)
        assertEquals(before, files(home), "a plan copies and rewrites nothing")

        assertEquals(foreign to kimi, copy.from to copy.fromHead)
        val adopted = adoption(calling, listOf(kimi), "abc-123") as SessionAdoption.Adopted
        assertEquals(copy.from to copy.into, adopted.from to adopted.into)
        assertEquals(own, owned.transcript)
        val kept = adoption(calling, listOf(kimi), "own-1") as SessionAdoption.HeadOwned
        assertEquals(owned.transcript, kept.transcript)
        val missed = adoption(calling, listOf(kimi), "nowhere-9") as SessionAdoption.Absent
        assertEquals(absent.searchedHeads, missed.searchedHeads)
    }

    /** Every file under [root] with its bytes' hash: a before and after that sees any copy or rewrite. */
    private fun files(root: Path): Map<String, Int> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList()
            .associate { root.relativize(it).toString() to Files.readAllBytes(it).contentHashCode() }
    }

    @Test
    fun `an argument that is not a session id is refused without being echoed`(@TempDir home: Path) {
        val calling = headConfig(home, "codex")

        val adoption = adoption(calling, listOf(headConfig(home, "kimi")), "../../etc/passwd")

        assertTrue(adoption is SessionAdoption.Invalid)
        assertFalse(adoption.toString().contains("passwd"), "the rejected argument is not reproduced: $adoption")
        assertFalse(Files.exists(calling.resolve(Keys.PROJECTS), NOFOLLOW_LINKS))
    }

    /** A log that keeps its lines, so a test can say what a launch named and what it stayed silent about. */
    private fun logged(): Pair<List<String>, LogSink> {
        val lines = mutableListOf<String>()
        return lines to LogSink { lines += it }
    }

    /** Oct 7 CT: a head that has never run a session has no projects dir, and every launch's search named that
     *  absence once per head. A missing projects dir is an ordinary miss for a search, so it logs nothing. */
    @Test
    fun `a head that has never run a session copies a foreign one in without naming its missing projects dir`(
        @TempDir home: Path,
    ) {
        register(home, "kimi", encodedCwd("app"), "s1", "gpt-5.6-sol")
        val calling = headConfig(home, "codex")
        val (lines, log) = logged()

        val roster = CallingRoster(pinned, listOf(pinned))
        val adoption = resumer().adopt(calling, listOf(home.resolve(".claude-kimi")), "s1", roster, log)

        assertTrue(adoption is SessionAdoption.Adopted, "$adoption")
        assertTrue(lines.none { "could not list" in it }, "a missing projects dir is an ordinary miss: $lines")
    }

    @Test
    fun `a session in no head's tree names no missing projects dir either`(@TempDir home: Path) {
        val calling = headConfig(home, "codex")
        val (lines, log) = logged()

        val roster = CallingRoster(pinned, listOf(pinned))
        val adoption = resumer().adopt(calling, listOf(headConfig(home, "kimi")), "s1", roster, log)

        assertTrue(adoption is SessionAdoption.Absent, "$adoption")
        assertTrue(lines.none { "could not list" in it }, "a missing projects dir is an ordinary miss: $lines")
    }

    @Test
    fun `a projects path that is not a directory is still named in the log`(@TempDir home: Path) {
        val calling = headConfig(home, "codex")
        write(calling.resolve(Keys.PROJECTS), "not a directory")
        val (lines, log) = logged()

        resumer().plan(calling, emptyList(), "s1", log)

        assertTrue(lines.any { "could not list" in it }, "a real failure to list is named: $lines")
    }

    /** A listing refused the way an access denial refuses it. The refusal is the double's, so the test does not
     *  depend on who runs it: a root process can read a directory with no permission bits. */
    @Test
    fun `a projects dir whose listing is denied is still named in the log`(@TempDir home: Path) {
        val calling = headConfig(home, "codex")
        val denied = DirectoryListing { dir -> throw AccessDeniedException(dir.toString()) }
        val (lines, log) = logged()
        val rewriter = TranscriptModelRewrite(originals = TranscriptOriginals(StatePaths(baseOverride = state)))

        ResumeAcrossHeads(rewriter, denied).plan(calling, emptyList(), "s1", log)

        assertTrue(lines.any { "could not list" in it }, "an access failure is named: $lines")
    }
}
