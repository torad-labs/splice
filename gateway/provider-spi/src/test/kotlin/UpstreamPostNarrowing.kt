// NEW: V4-114. UpstreamClient.post answers `UpstreamPost { Delivered | TurnWaitExhausted }` now
// instead of returning T and throwing UpstreamTurnWaitExhausted, so this is the one narrowing the
// suite uses when the turn-wait budget is not what the test is about. A refusal here is a bug in
// the test and fails loudly rather than being silently unwrapped. The REFUSAL arm is pinned on the
// value itself (RateLimitCooldownTest / UpstreamClientBackoffTest) — never through this helper.
import splice.spi.PostContext
import splice.spi.UpstreamClient
import splice.spi.UpstreamHandler
import splice.spi.UpstreamPost

internal suspend fun <T> UpstreamClient.posted(
    ctx: PostContext,
    bodyJson: String,
    block: UpstreamHandler<T>,
): T = when (val posted = post(ctx, bodyJson, block)) {
    is UpstreamPost.Delivered -> posted.value
    UpstreamPost.TurnWaitExhausted -> error("the turn-wait budget refused a post this test expected to go out")
}
