// Argument parser for `splice mcp import` and `splice mcp sync`.
package splice.app.mcp

import splice.core.mcp.McpHarness
import splice.core.mcp.McpImportSource
import splice.core.mcp.McpRegistryNames

private const val FLAG_FROM = "--from"
private const val FLAG_TO = "--to"
private const val FLAG_SERVER = "--server"
private const val FLAG_IMPORT = "--import"
private const val ALL_TOKEN = "all"

internal data class McpImportArgs(val sources: List<McpImportSource>, val servers: Set<String>)

internal data class McpSyncArgs(val harnesses: Set<McpHarness>, val importFirst: Boolean)

/** Parses CLI flags for `splice mcp import` and `splice mcp sync`. */
internal class McpHarnessCliArgs {
    private val names = McpRegistryNames()
    private val allSources = listOf(
        McpImportSource.CLAUDE_CODE,
        McpImportSource.CLAUDE_DESKTOP,
        McpImportSource.AGY,
        McpImportSource.CODEX,
    )

    fun parseImport(args: List<String>): McpImportArgs? {
        val sources = mutableListOf<McpImportSource>()
        val servers = mutableSetOf<String>()
        var i = 0
        while (i < args.size) {
            val step = stepImportArg(args, i, sources, servers) ?: return null
            i += step
        }
        val effective = if (sources.isEmpty()) allSources else sources.distinct()
        return McpImportArgs(effective, servers)
    }

    private fun stepImportArg(
        args: List<String>,
        index: Int,
        sources: MutableList<McpImportSource>,
        servers: MutableSet<String>,
    ): Int? = when (args[index]) {
        FLAG_FROM -> args.getOrNull(index + 1)?.let(::parseSourceList)?.let {
            sources.addAll(it)
            2
        }
        FLAG_SERVER -> args.getOrNull(index + 1)?.takeIf { it.isNotBlank() }?.let {
            servers.add(it)
            2
        }
        else -> null
    }

    fun parseSync(args: List<String>): McpSyncArgs? {
        val harnesses = mutableSetOf<McpHarness>()
        var importFirst = false
        var valid = true
        var i = 0
        while (i < args.size && valid) {
            when (args[i]) {
                FLAG_TO -> {
                    val parsed = args.getOrNull(i + 1)?.let(::parseHarnessList)
                    if (parsed == null) valid = false else harnesses.addAll(parsed)
                    i += 2
                }
                FLAG_IMPORT -> {
                    importFirst = true
                    i += 1
                }
                else -> valid = false
            }
        }
        return if (!valid) null else McpSyncArgs(harnesses, importFirst)
    }

    private fun parseSourceList(raw: String): List<McpImportSource>? {
        val tokens = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<McpImportSource>()
        var valid = tokens.isNotEmpty()
        for (token in tokens) {
            if (!valid) break
            if (token.equals(ALL_TOKEN, ignoreCase = true)) {
                out.addAll(allSources)
            } else {
                val parsed = names.source(token)?.takeIf { it != McpImportSource.MANUAL }
                if (parsed == null) valid = false else out.add(parsed)
            }
        }
        return if (valid) out else null
    }

    private fun parseHarnessList(raw: String): List<McpHarness>? {
        val tokens = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<McpHarness>()
        var valid = tokens.isNotEmpty()
        for (token in tokens) {
            if (!valid) break
            if (token.equals(ALL_TOKEN, ignoreCase = true)) {
                out.addAll(McpHarness.entries)
            } else {
                val parsed = names.harness(token)
                if (parsed == null) valid = false else out.add(parsed)
            }
        }
        return if (valid) out else null
    }
}
