package splice.core.wire

/** The header every request on the Anthropic wire carries, naming the API version it speaks. Claude Code and the
 *  Anthropic SDKs send it on each request, so a request splice makes of an Anthropic endpoint carries it too, and a
 *  listener that also serves OpenAI-style clients tells an Anthropic client's request by it. */
public const val ANTHROPIC_VERSION_HEADER: String = "anthropic-version"

/** The header an Anthropic-wire refusal tells its client in whether sending the same request again is worth it. Claude
 *  Code reads it, and Claude Desktop's gateway protocol asks a gateway for it; every site that writes it names it here. */
public const val SHOULD_RETRY_HEADER: String = "x-should-retry"
