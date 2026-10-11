// NEW: (V4-92) the public-surface reports: their two -P options, the per-source ledger, and the JSON file per compiled
// source that the architecture law reads.
package splice.firchecks

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import java.util.SortedSet

internal const val PLUGIN_ID: String = "splice.fir-checks"
internal const val REPORT_FORMAT: String = "splice.public-surface/2"

internal val reportDirKey: CompilerConfigurationKey<String> =
    CompilerConfigurationKey.create("splice public-surface report directory")
internal val sourceRootKey: CompilerConfigurationKey<String> =
    CompilerConfigurationKey.create("splice public-surface source root")

internal val reportDirOption: CliOption = CliOption(
    optionName = "publicSurfaceReportDir",
    valueDescription = "<dir>",
    description = "Write one public-surface report (JSON) per compiled source under <dir>",
    required = false,
    allowMultipleOccurrences = false,
)

internal val sourceRootOption: CliOption = CliOption(
    optionName = "publicSurfaceSourceRoot",
    valueDescription = "<dir>",
    description = "Key each report by its source's path relative to <dir>, the module's directory",
    required = false,
    allowMultipleOccurrences = false,
)

@OptIn(ExperimentalCompilerApi::class)
internal class FirChecksCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = PLUGIN_ID
    override val pluginOptions: Collection<AbstractCliOption> =
        listOf(reportDirOption, sourceRootOption)

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            reportDirOption.optionName -> configuration.put(reportDirKey, value)
            sourceRootOption.optionName -> configuration.put(sourceRootKey, value)
            else -> error("unknown $PLUGIN_ID option ${option.optionName}")
        }
    }
}

/** Runs once per successful compilation, after FIR is resolved and checked, over exactly the sources it compiled: all
 *  of them on a full build, the dirty ones on an incremental one. Each source gets its own report at
 *  `<reportDir>/<its path relative to sourceRoot>.json`, and that report depends on nothing but the source's text and
 *  what its signatures resolve to, so a source compiled alone writes the bytes a whole-module compile writes. Every
 *  class the walk reads is recorded as a lookup of that source through [CompilerLookups], so incremental compilation
 *  recompiles the source, and rewrites its report, when one of them changes. */
internal class PublicSurfaceReportExtension(
    private val reportDir: File,
    sourceRoot: File,
) : IrGenerationExtension {
    private val root = sourceRoot.absoluteFile.normalize()

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        for (irFile in moduleFragment.files) {
            val fir = checkNotNull((irFile.metadata as? FirMetadataSource.File)?.fir) {
                "${irFile.fileEntry.name}: no FIR behind this IR file, so its public-surface report cannot be written"
            }
            val source = File(irFile.fileEntry.name).absoluteFile.normalize()
            val key = source.relativeTo(root).invariantSeparatorsPath
            check(key != ".." && !key.startsWith("../")) { "$source is outside $root, so no report path can name it" }
            val ledger = SurfaceLedger()
            PublicSurfaceWalk(fir.moduleData.session, ledger, CompilerLookups(fir)).walk(fir)
            val out = File(reportDir, "$key.json").absoluteFile
            out.parentFile.mkdirs()
            out.writeText(ledger.json(sha256(source.readBytes())), Charsets.UTF_8)
        }
    }

    /** The SHA-256 of a source's bytes, the way the law hashes the same file: a report is about one exact text. */
    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}

/** One source's surface: its public top-level declarations, and each one's reach. */
internal class SurfaceLedger {
    private val declarations: SortedSet<String> = sortedSetOf()
    private val edges = sortedMapOf<String, SortedSet<String>>()

    fun declare(fqn: String) {
        declarations += fqn
    }

    fun reach(owner: String, target: String) {
        if (owner != target) edges.getOrPut(owner) { sortedSetOf() } += target
    }

    /** Sorted, one owner per line: the same source and the same classpath always write the same bytes. */
    fun json(sha256: String): String = buildString {
        append("{\n  \"format\": ").append(quote(REPORT_FORMAT)).append(",\n")
        append("  \"sha256\": ").append(quote(sha256)).append(",\n")
        append("  \"declarations\": [").append(declarations.joinToString(", ", transform = ::quote)).append("],\n")
        append("  \"edges\": {")
        edges.entries.forEachIndexed { index, (owner, targets) ->
            append(if (index == 0) "\n    " else ",\n    ").append(quote(owner)).append(": [")
            append(targets.joinToString(", ", transform = ::quote)).append("]")
        }
        append(if (edges.isEmpty()) "}\n}\n" else "\n  }\n}\n")
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when {
                ch == '"' || ch == '\\' -> append('\\').append(ch)
                ch < ' ' -> append("\\u").append(HexFormat.of().toHexDigits(ch))
                else -> append(ch)
            }
        }
        append('"')
    }
}
