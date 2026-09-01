// NEW: TurnPipeline + output-clamp construction, split from TurnDriveFactory
// (concentration, 2026-08-19) so the factory is not billed for pipeline/usage.
// Same-package.
package splice.gateway.head

import splice.core.turn.TurnMeta
import splice.gateway.pipeline.TurnPipeline
import splice.gateway.usage.OutputClampPolicy
import splice.spi.Provider

internal class DrivePipeline(
    private val provider: Provider,
    private val deps: HeadDeps,
) {
    fun make(meta: TurnMeta): TurnPipeline = TurnPipeline(
        deps.compactStats,
        deps.log,
        OutputClampPolicy.makeOutputClamp(meta.clientMaxTokens, meta.compact, provider.key, deps.log),
        mirrorReasoning = deps.mirrorReasoning,
    )
}
