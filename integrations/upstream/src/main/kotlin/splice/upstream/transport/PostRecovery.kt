package splice.upstream.transport

import splice.upstream.BodyAmendment
import splice.upstream.ClientFrameEmitted

/** What a failed send of a post may do next: whether the client has already been shown a frame, which forbids a
 *  reissue, and how the request body is amended before a retry. The defaults reissue freely and amend nothing. */
public data class PostRecovery(
    val clientFrameEmitted: ClientFrameEmitted = ClientFrameEmitted { true },
    val amendBodyOnFailure: BodyAmendment = BodyAmendment { _, _, _ -> null },
)
