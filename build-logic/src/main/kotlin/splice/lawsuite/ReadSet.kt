// NEW: the git-derived read set a law task fingerprints and its test reads, computed once.
// THE READ SET OF A LAW: the files a law test reads outside its module, computed ONCE, from git's own rule.
//
// Membership is `git ls-files --cached --others --exclude-standard` under the declared roots: a tracked file always counts, even
// inside an ignored directory, and a new untracked file counts unless .gitignore covers it. There is no hand-written exclusion
// list: a list kept beside the walk it describes is the drift this file exists to remove. The same list is fingerprinted as the
// inputs of the law task and handed to the test JVM as a file, and the test reads nothing outside it (LawReadSet, in core's
// testFixtures), so the fingerprint and the reader cannot disagree.
package splice.lawsuite

import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object ReadSet {

    /** Repo-relative files git lists under each of [roots], sorted. Git's own `--deleted` answer is subtracted: a tracked file removed
     *  from the worktree and not yet committed is listed by git and is no input. Nothing else is filtered by the producer: every
     *  path that remains must be a regular file, and one that is not (a tracked dangling link, a vanished file git does not call
     *  deleted) throws by name. A root that contributes no file to the FINAL set fails by name: a missing, renamed or fully
     *  deleted root would otherwise shrink the set in silence. */
    fun git(repo: File, roots: List<String>): List<String> {
        val perRoot = roots.associateWith { root ->
            val present = listed(repo, root, "--cached", "--others", "--exclude-standard")
            present - listed(repo, root, "--deleted").toSet()
        }
        perRoot.forEach { (root, files) ->
            check(files.isNotEmpty()) { "the read-set root '$root' contributes no file to the set in $repo" }
        }
        val set = perRoot.values.flatten().sorted()
        set.zipWithNext().firstOrNull { (a, b) -> a == b }?.let { (name) ->
            error("the read set names '$name' twice: two roots overlap, or git listed it twice in $repo")
        }
        set.forEach { path ->
            check(repo.resolve(path).isFile) { "the read set lists '$path', which is not a file in $repo and which git does not call deleted" }
        }
        return set
    }

    /** The same set for a row's glob patterns (a directory glob): git's own `:(glob)` pathspec, so the expansion is git's too. */
    fun globbed(repo: File, globs: List<String>): List<String> = git(repo, globs.map { ":(glob)$it" })

    /** The list file's text: every path ended by a NUL, so a filename holding a newline survives. The reader (LawReadSet) refuses an
     *  empty name and a repeated one, and the writer refuses them too, naming the entry, so the two cannot disagree. */
    fun encode(set: List<String>): String {
        check(set.none { it.isEmpty() }) { "the read set holds an empty name" }
        set.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.let { (name) ->
            error("the read set names '$name' twice")
        }
        return set.joinToString("") { "$it\u0000" }
    }

    private fun listed(repo: File, root: String, vararg modes: String): List<String> {
        val command = listOf("git", "ls-files") + modes + listOf("-z", "--", root)
        val process = ProcessBuilder(command).directory(repo).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val output = process.inputStream.readBytes()
        val exit = process.waitFor()
        check(exit == 0) { "git ls-files over '$root' exited $exit in $repo" }
        return names(output, root)
    }

    /** Git's -z output: NUL-ended names. A name that is not valid UTF-8 throws with its bytes in hex, never decodes lossily. */
    private fun names(output: ByteArray, root: String): List<String> {
        val names = mutableListOf<String>()
        var start = 0
        for (index in output.indices) {
            if (output[index] == 0.toByte()) {
                names += decodeStrictly(output.copyOfRange(start, index), root)
                start = index + 1
            }
        }
        check(start == output.size) { "git ls-files over '$root' ended mid-name" }
        return names
    }

    private fun decodeStrictly(name: ByteArray, root: String): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(name))
            .toString()
    } catch (failure: CharacterCodingException) {
        error("git listed a name under '$root' that is not UTF-8: ${name.joinToString("") { "%02x".format(it) }} (${failure.message})")
    }

    /** Declares [roots] as the read set of [lawTest] in [project]: fingerprints every file in it, writes the list to a file the
     *  test JVM reads, and names the repository root. */
    fun declare(project: Project, lawTest: TaskProvider<Test>, roots: List<String>) {
        val repo = project.rootProject.projectDir
        val set = git(repo, roots)
        val listFile = project.layout.buildDirectory.file("law-read-set.txt")
        val write = project.tasks.register("writeLawReadSet") {
            inputs.property("readSet", set)
            outputs.file(listFile)
            doLast { listFile.get().asFile.apply { parentFile.mkdirs() }.writeText(encode(set)) }
        }
        lawTest.configure {
            dependsOn(write)
            inputs.files(set.map { repo.resolve(it) }).withPropertyName("readSet")
            inputs.file(listFile).withPropertyName("readSetList")
            systemProperty("splice.root", repo.absolutePath)
            systemProperty("splice.lawReadSetFile", listFile.get().asFile.absolutePath)
        }
    }
}
