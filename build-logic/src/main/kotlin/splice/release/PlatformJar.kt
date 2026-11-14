// NEW: Nov 12, 2026: a release publishes one jar per platform beside the jar that holds all four. Each code-mode
// isolate library is 140 to 150 MB and each native host about 27 MB, so the jar of all four is about 476 MB where one
// platform's is about 210 MB, and an install or an upgrade needs only its own.
//
// A PLATFORM'S JAR IS THE JAR OF ALL FOUR WITH THE OTHER THREE CUT OUT, entry for entry what a build told to ship that
// platform alone holds: the other platforms' isolate libraries, their native hosts, their two provider classes, and
// their line in the one service file the fat jar merged them into. That line matters: ServiceLoader fails on a provider
// it is told of and cannot load, so a jar that kept the line and dropped the class could not start an engine.
// The jar's two reports of its own contents are cut the same way (PlatformReports).
package splice.release

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** The service file a fat jar merges every isolate's provider into, one class name to a line. */
private const val PROVIDERS = "META-INF/services/com.oracle.truffle.api.provider.InternalResourceProvider"

/** Where GraalVM keeps a platform's two provider classes, by its system and processor. */
private const val PROVIDER_CLASSES = "com/oracle/truffle/isolate/resource/js/"

/** The two reports of its own contents a fat jar carries. */
private const val BOM = "META-INF/bom.cdx.json"
private const val LICENSES = "META-INF/dependency-licenses.json"

// why: a megabyte a read keeps the copy of a 146 MB library to a few hundred reads.
private const val COPY_BUFFER = 1 shl 20

/** One platform's cut of a fat jar that holds every platform: which entries stay, and what three of them then say. */
class PlatformCut(private val platform: String) {
    private val others = IsolatePlatforms.every - platform

    init {
        require(platform in IsolatePlatforms.every) { "no isolate library is published for $platform" }
    }

    /** The folders that hold nothing but another platform's files. A system no kept platform runs on goes whole. */
    private val dropped: List<String> = others.flatMap { other ->
        val system = other.substringBefore('-')
        listOf(
            "META-INF/resources/engine/js-isolate-$other/",
            "splice/codemode/native/$other/",
            PROVIDER_CLASSES + other.replace('-', '/') + "/",
        ) + listOfNotNull("$PROVIDER_CLASSES$system/".takeIf { system != platform.substringBefore('-') })
    }

    /** The entries whose text is cut: the provider file and the jar's two reports of its own contents. */
    private val rewritten = setOf(PROVIDERS, BOM, LICENSES)

    private val otherProviders: List<String> =
        others.map { PROVIDER_CLASSES.replace('/', '.') + it.replace('-', '.') + "." }

    /** Whether the entry [name] stays in this platform's jar. */
    fun keeps(name: String): Boolean = dropped.none { name.startsWith(it) }

    /** The provider lines of [merged] that this platform's jar can load, in their order. */
    fun providers(merged: String): String =
        merged.lines().filter { line -> otherProviders.none(line::startsWith) }.joinToString("\n")

    /** Writes [target] from [universal], which must hold every platform: a cut of a jar that lacks one proves nothing. */
    fun write(universal: File, target: File) {
        ZipFile(universal).use { source ->
            val missing = IsolatePlatforms.every.filter { source.getEntry(IsolatePlatforms.marker(it)) == null }
            check(missing.isEmpty()) {
                "platform jar: $universal holds no code-mode isolate for ${missing.joinToString()}; " +
                    "build it with -Psplice.allPlatforms=true"
            }
            val reports = PlatformReports(others.flatMap { listOf(isolate(it), nativeHost(source, it)) }.toSet())
            val absent = rewritten.filter { source.getEntry(it) == null }
            check(absent.isEmpty()) { "platform jar: $universal has no ${absent.joinToString()}" }
            target.parentFile.mkdirs()
            ZipOutputStream(target.outputStream().buffered(COPY_BUFFER)).use { out ->
                source.entries().asSequence().filter { keeps(it.name) }
                    .forEach { entry -> copy(source, entry, out, reports) }
            }
        }
    }

    /** Another platform's isolate library, as the reports name it. */
    private fun isolate(other: String): String = "org.graalvm.js:js-isolate-$other-community"

    /** Another platform's native host, as the reports name it: by the target its own manifest in [source] gives. */
    private fun nativeHost(source: ZipFile, other: String): String {
        val manifest = checkNotNull(source.getEntry("splice/codemode/native/$other/host.properties")) {
            "platform jar: no native code-mode host for $other to cut"
        }
        val target = Properties().apply { source.getInputStream(manifest).use(::load) }.getProperty("target")
        return "openai:codex-code-mode-host-$target"
    }

    /** What the entry [name], one of the three that are rewritten, says in this platform's jar. */
    private fun said(name: String, text: String, reports: PlatformReports): String = when (name) {
        PROVIDERS -> providers(text)
        BOM -> reports.bom(text)
        else -> reports.licenses(text)
    }

    /** One entry as it was: its name, its time and whether it is stored. Only a rewritten entry's bytes change. */
    private fun copy(source: ZipFile, entry: ZipEntry, out: ZipOutputStream, reports: PlatformReports) {
        val rewrite = entry.name in rewritten
        val stored = entry.method == ZipEntry.STORED && !rewrite
        val copied = ZipEntry(entry.name)
        copied.timeLocal = entry.timeLocal
        copied.method = if (stored) ZipEntry.STORED else ZipEntry.DEFLATED
        if (stored) {
            copied.size = entry.size
            copied.compressedSize = entry.compressedSize
            copied.crc = entry.crc
        }
        out.putNextEntry(copied)
        source.getInputStream(entry).use { input ->
            if (rewrite) {
                out.write(said(entry.name, input.readBytes().decodeToString(), reports).encodeToByteArray())
            } else {
                input.copyTo(out, COPY_BUFFER)
            }
        }
        out.closeEntry()
    }
}

/** Cuts one platform's jar from the fat jar of all four. */
abstract class PlatformJar : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val universal: RegularFileProperty

    @get:Input
    abstract val platform: Property<String>

    @get:OutputFile
    abstract val jar: RegularFileProperty

    @TaskAction
    fun cut() {
        PlatformCut(platform.get()).write(universal.get().asFile, jar.get().asFile)
    }
}
