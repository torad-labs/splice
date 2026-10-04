// NEW: V4-114. UpstreamClient.post answers `UpstreamPost { Delivered | TurnWaitExhausted }` now
// instead of returning T and throwing UpstreamTurnWaitExhausted, so this is the one narrowing the
// suite uses when the turn-wait budget is not what the test is about. A refusal here is a bug in
// the test and fails loudly rather than being silently unwrapped. The REFUSAL arm is pinned on the
// value itself (RateLimitCooldownTest / UpstreamClientBackoffTest) — never through this helper.
//
// It keeps taking the body as TEXT: a test that writes a body as a literal is handing text, and
// RoundBody.Text is exactly that. The tree-shaped RoundBody.Tree is the production path's and is
// measured where it matters (RoundSerializationTest, RequestParseAllocationTest).
package splice.upstream.transport

import splice.upstream.RoundBody
import splice.upstream.UpstreamHandler

internal suspend fun <T> UpstreamClient.posted(
    ctx: PostContext,
    bodyJson: String,
    block: UpstreamHandler<T>,
): T = when (val posted = post(ctx, RoundBody.Text(bodyJson), block)) {
    is UpstreamPost.Delivered -> posted.value
    is UpstreamPost.Refused -> throw posted.failure
    UpstreamPost.TurnWaitExhausted -> error("the turn-wait budget refused a post this test expected to go out")
}
