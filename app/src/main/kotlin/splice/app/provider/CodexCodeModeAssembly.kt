// Code mode's half of the Codex arm: each codex head's bridge and model lists, and the session liveness poller,
// one per daemon, that every head's cell lease reads. CodexResponsesArm builds the provider around it.
package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import splice.app.provider.codex.CodeModeMcpCatalogs
import splice.app.provider.codex.CodeModeMcpReplies
import splice.app.provider.codex.CodeModeNativeRuntimes
import splice.app.provider.codex.CodeModeSessionLiveness
import splice.app.provider.codex.HeadCodeModeControls
import splice.codemode.DEFAULT_HEAP_MB
import splice.codemode.DEFAULT_MAX_WORKERS
import splice.codemode.DEFAULT_POOL_MEMORY_MB
import splice.codemode.host.PoolLimits
import splice.core.config.CODE_MODE_DIR
import splice.core.config.CODE_MODE_STATE_SUFFIX
import splice.core.config.StatePaths
import splice.core.mcp.McpToolResultStore
import splice.core.util.Cancellables
import splice.core.util.HeadScopedLogs
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeJournaling
import splice.provider.codex.CodeModeOnlyModels
import splice.provider.codex.CodeModeOriginalImageModels
import splice.provider.codex.CodeModeReplayReasoning
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexCodeModeConfiguration
import splice.provider.codex.CodexCodeModeWiring
import splice.provider.codex.catalog.CodeModeModelCatalog
import splice.provider.codex.catalog.CodeModeModelMetadata
import splice.provider.codex.state.lease.CodeModeCellLease
import splice.provider.codex.state.lease.CodeModeSessionAlive
import splice.provider.codex.state.lease.CodeModeSessionIdle
import java.util.concurrent.ConcurrentHashMap

internal class CodexCodeModeAssembly(
    private val statePaths: StatePaths,
    private val probeScope: CoroutineScope,
    private val log: LogSink,
    /** The heads that run code mode. The liveness poller is one per daemon, so each of its lines carries every
     *  such head's tag, and each head's log view keeps it (gap 480). */
    private val codeModeHeads: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    private val sessionAlive: CodeModeSessionAlive = CodeModeSessionLiveness(
        probeScope,
        log = LogSink { line ->
            codeModeHeads.fold(log) { sink, head -> HeadScopedLogs.headScopedLog(head, sink) }(line)
        },
    ),
    private val sessionIdle: CodeModeSessionIdle =
        (sessionAlive as? CodeModeSessionLiveness)?.idle ?: CodeModeSessionIdle { false },
) {
    internal val controls: HeadCodeModeControls = HeadCodeModeControls()
    internal val catalogs: CodeModeMcpCatalogs = CodeModeMcpCatalogs()

    fun wiring(ctx: ProviderBuild): CodexCodeModeWiring = CodexCodeModeWiring(
        // A provider's `code_mode = false` is the operator's off switch (V4-11): no bridge, so its turns keep direct
        // tools. Only an enabled provider whose native host is missing takes the unavailable, fail-closed path.
        bridge = if (ctx.providerCfg.codeModeEnabled) {
            codeModeBridge(ctx).also { codeModeHeads += ctx.key }
        } else {
            controls.register(ctx.key, null)
            null
        },
        models = ctx.providerCfg.quirks.codeModeModels,
        onlyModels = backendCodeModeOnly(ctx),
        originalImageModels = CodeModeOriginalImageModels {
            ctx.roster.discovered.metadataForHead(ctx.key).filter { it.supportsImageDetailOriginal }
                .flatMap { it.spellings }
        },
        settings = CodexCodeModeConfiguration.read(ctx.cfg).copy(
            modelCatalog = CodeModeModelCatalog {
                ctx.roster.discovered.metadataForHead(ctx.key).flatMap { model ->
                    val metadata = CodeModeModelMetadata(
                        model.modelMessages,
                        model.supportsImageDetailOriginal,
                        model.useResponsesLite,
                    )
                    model.spellings.map { it to metadata }
                }.toMap()
            },
        ),
    )

    /** V4-441: the models the backend marks `code_mode_only`, read from the head's roster at each turn so a
     *  refresh while the daemon runs reaches the next one. With none known at start (no answer and no kept
     *  list) only `code_mode_models` runs code mode, and the head's log says so once. */
    private fun backendCodeModeOnly(ctx: ProviderBuild): CodeModeOnlyModels {
        val port = CodeModeOnlyModels {
            ctx.roster.discovered.forHead(ctx.key).filter { it.codeModeOnly }.flatMap { it.spellings }
        }
        if (port.ids().isEmpty()) {
            HeadScopedLogs.headScopedLog(ctx.key, log).invoke(
                "[code-mode] no model is marked code_mode_only by the backend's list (none was discovered or " +
                    "kept), so only code_mode_models runs code mode\n",
            )
        }
        return port
    }

    private fun codeModeBridge(ctx: ProviderBuild): CodexCodeModeBridge =
        CodexCodeModeBridge(
            CodeModeBridgeConfig(
                runtimes = CodeModeNativeRuntimes(
                    enabled = ctx.providerCfg.codeModeEnabled,
                    limits = PoolLimits(
                        maxWorkers = ctx.providerCfg.quirks.codeModeWorkers ?: DEFAULT_MAX_WORKERS,
                        memoryBudgetMb = ctx.providerCfg.quirks.codeModeMemoryMb ?: DEFAULT_POOL_MEMORY_MB,
                    ),
                    cellHeapMb = ctx.providerCfg.quirks.codeModeHeapMb ?: DEFAULT_HEAP_MB,
                ),
                state = CodeModeStateLocation(
                    dir = statePaths.headsDir.resolve(ctx.key).resolve(CODE_MODE_DIR),
                    legacyFile = statePaths.stateDir.resolve("${ctx.key}$CODE_MODE_STATE_SUFFIX"),
                    journaling = CodeModeJournaling { ctx.cfg.current().keepCodeModeWork },
                    replayReasoning = CodeModeReplayReasoning { ctx.cfg.current().replayReasoning },
                    mcpResults = CodeModeMcpReplies(
                        McpToolResultStore(statePaths),
                        HeadScopedLogs.headScopedLog(ctx.key, log),
                    ),
                    mcpSearch = catalogs.forHead(ctx.key),
                ),
                log = HeadScopedLogs.headScopedLog(ctx.key, log),
                cellLease = CodeModeCellLease(sessionAlive = sessionAlive, sessionIdle = sessionIdle),
            ),
        ).also { bridge ->
            controls.register(ctx.key, bridge)
            checkNotNull(probeScope.coroutineContext[Job]).invokeOnCompletion {
                controls.remove(ctx.key, bridge)
                Cancellables.runCatchingBestEffort { bridge.onProviderStop() }.exceptionOrNull()?.let { failure ->
                    HeadScopedLogs.headScopedLog(ctx.key, log).invoke(
                        "[code-mode] terminal save needs its retained retry owner " +
                            "(${SafeFailureText.render(failure)})",
                    )
                }
            }
        }
}
