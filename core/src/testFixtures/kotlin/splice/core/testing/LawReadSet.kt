// NEW: 2026-10-08 — the files a law test may read outside its module, handed to it by the build.
//
// A law that walks the repository (the launch scripts, the docs, every module's sources) reads a tree gradle cannot see unless
// the build declares it. The build computes ONE list from git's own rule (build-logic: splice.lawsuite.ReadSet), fingerprints
// every file in it as the inputs of the law task, and writes it to a file named by `splice.lawReadSetFile`, every path ended by
// a NUL so no filename can split it. The law reads only what this class hands it. A read outside the list throws and is also
// RECORDED, and LawReadGuard fails the test after it runs if anything was recorded, so a law that catches the throw still fails.
package splice.core.testing

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

private const val ROOT_PROPERTY = "splice.root"
private const val LIST_PROPERTY = "splice.lawReadSetFile"

/** The declared read set of one law task: the repo-relative paths in [listFile], under [root]. `LawReadSet()` is the set this
 *  test JVM was handed by the build: the repository root from `splice.root`, the list from `splice.lawReadSetFile`. */
class LawReadSet(
    val root: Path = Path.of(checkNotNull(System.getProperty(ROOT_PROPERTY)) { "$ROOT_PROPERTY is not set" }),
    listFile: Path = Path.of(checkNotNull(System.getProperty(LIST_PROPERTY)) { "$LIST_PROPERTY is not set" }),
) {
    private val declared: Set<String> = parse(Files.readAllBytes(listFile))

    /** The list file's contract, the same as its writer's: every path is UTF-8 and ended by a NUL. Only the last NUL may be followed by
     *  an empty field, a malformed name throws with its bytes in hex, and a repeated name throws, naming it. */
    private fun parse(bytes: ByteArray): Set<String> {
        val fields = mutableListOf<ByteArray>()
        var start = 0
        for (index in bytes.indices) {
            if (bytes[index] == 0.toByte()) {
                fields += bytes.copyOfRange(start, index)
                start = index + 1
            }
        }
        check(start == bytes.size) {
            "the read-set list does not end with a NUL: ${hex(bytes.copyOfRange(start, bytes.size))}"
        }
        val names = linkedSetOf<String>()
        for (field in fields) {
            check(field.isNotEmpty()) { "the read-set list holds an empty field before its end" }
            val name = decodeStrictly(field)
            check(names.add(name)) { "the read-set list names $name twice" }
        }
        return names
    }

    private fun decodeStrictly(field: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(field))
            .toString()
    } catch (failure: CharacterCodingException) {
        error("the read-set list holds a name that is not UTF-8: ${hex(field)} (${failure.message})")
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Every declared path, absolute, in list order. A declared path that is not a regular file throws, naming it: a list and a
     *  tree that disagree must fail loudly, never shrink the set. */
    fun files(): List<Path> = declared.sorted().map { relative ->
        val file = root.resolve(relative)
        check(Files.isRegularFile(file)) { "the read set lists $relative, which is not a regular file under $root" }
        file
    }

    private val realRoot: Path by lazy {
        if (Files.exists(root)) root.toRealPath() else root.toAbsolutePath().normalize()
    }

    /** The declared files that exist, as real paths (symlinks and `..` resolved). A declared file that is missing is files()'s
     *  to report, by name. */
    private val declaredReal: Set<Path> by lazy {
        declared.map { root.resolve(it) }.filter { Files.exists(it) }.map { it.toRealPath() }.toSet()
    }

    /** The text of [path], which must be in the declared set. The path is resolved to its real path ONCE, that real path is what is
     *  compared with the declared files, and that same Path object is what is opened: a link or a `..` cannot make the check and
     *  the read mean different files. Anything else is recorded for [LawReadGuard] and throws, naming the path. Bytes that are
     *  not UTF-8 decode lossily: a binary file in the set is read, never an error. */
    fun readText(path: Path): String {
        val requested = path.toAbsolutePath()
        val real = if (Files.exists(requested)) requested.toRealPath() else requested.normalize()
        if (real !in declaredReal) {
            val shown = realRoot.relativize(real).toString()
            UndeclaredReads.record(shown)
            error("a law read $shown, which is not in the read set the build declared for it")
        }
        return String(Files.readAllBytes(real), StandardCharsets.UTF_8)
    }
}
