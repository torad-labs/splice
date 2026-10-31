// NEW: V4-114. UpstreamClient.post answers `UpstreamPost { Delivered | Refused | Ended | TurnWaitExhausted }`, so this is
// the one narrowing the suite uses when the turn-wait budget is not what the test is about. An ending here reaches the
// test as [EndedWith], a test-only throwable, and [assertEnds] hands the test the sealed ending itself; the ENDING arms
// are pinned on the value (RateLimitCooldownTest / UpstreamClientBackoffTest) when the post's own answer is the point.
//
// It keeps taking the body as TEXT: a test that writes a body as a literal is handing text, and
// RoundBody.Text is exactly that. The tree-shaped RoundBody.Tree is the production path's and is
// measured where it matters (RoundSerializationTest, RequestParseAllocationTest).
package splice.upstream.transport

import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.assertThrows
import splice.upstream.RoundBody
import splice.upstream.StreamRead

/** The ending a post answered with, as the throwable a test asserts on. Test-only: production returns the value. */
internal class EndedWith(val ending: UpstreamEnding) : RuntimeException("upstream ended: ${ending::class.simpleName}")

internal suspend fun <T> UpstreamClient.posted(
    ctx: PostContext,
    bodyJson: String,
    block: suspend (UpstreamResponse) -> T,
): T = when (val posted = post(ctx, RoundBody.Text(bodyJson)) { StreamRead.Read(block(it)) }) {
    is UpstreamPost.Delivered -> posted.value
    is UpstreamPost.Refused -> throw EndedWith(posted.failure)
    is UpstreamPost.Ended -> throw EndedWith(posted.ending)
    UpstreamPost.TurnWaitExhausted -> error("the turn-wait budget refused a post this test expected to go out")
}

/** [posted] for a handler that reports how the body ended: a value, a re-issuable tear, or an oversized frame. */
internal suspend fun <T> UpstreamClient.postedRead(
    ctx: PostContext,
    bodyJson: String,
    block: suspend (UpstreamResponse) -> StreamRead<T>,
): T = when (val posted = post(ctx, RoundBody.Text(bodyJson)) { block(it) }) {
    is UpstreamPost.Delivered -> posted.value
    is UpstreamPost.Refused -> throw EndedWith(posted.failure)
    is UpstreamPost.Ended -> throw EndedWith(posted.ending)
    UpstreamPost.TurnWaitExhausted -> error("the turn-wait budget refused a post this test expected to go out")
}

/** Runs [block] and returns the [E] ending it ended on; any other ending, or none, fails the test. */
internal inline fun <reified E : UpstreamEnding> assertEnds(block: () -> Unit): E =
    assertInstanceOf(E::class.java, assertThrows<EndedWith> { block() }.ending)
