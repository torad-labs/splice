// NEW: the two ends one code-mode round talks through, held together as one value.
package splice.provider.codex

import splice.provider.codex.stream.CodeModeUpstreamPost
import splice.upstream.sse.WireSink

/** The client-facing [sink] a round streams to and the [post] that sends its request upstream. */
internal data class CodeModeRoundLink(val sink: WireSink, val post: CodeModeUpstreamPost)
