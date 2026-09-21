// NEW: TurnPipeline + output-clamp construction, split from TurnDriveFactory
// (concentration, 2026-08-19) so the factory is not billed for pipeline/usage.
// Same-package.
package splice.head.turn

import splice.core.turn.TurnMeta
import splice.head.HeadDeps
import splice.head.pipeline.TurnPipeline
import splice.head.usage.OutputClampPolicy
import splice.upstream.Provider

internal class DrivePipeline(
    private val provider: Provider,
    private val deps: HeadDeps,
) {
    fun make(meta: TurnMeta): TurnPipeline = TurnPipeline(
        deps.stores.compactStats,
        deps.log,
        OutputClampPolicy.makeOutputClamp(meta.clientMaxTokens, meta.compact, provider.key, deps.log),
        mirrorReasoning = deps.policy.mirrorReasoning,
    )
}
