// PORT-OF: splice/app/Daemon.kt (CHATGPT_OAUTH, GROK_OAUTH, KIMI_OAUTH, CLIENT) @ ed5c868 —
// arm-local wire vocabulary. ProviderAssembly validates registered kind/dialect compatibility first;
// unregistered api-key/custom strings intentionally retain each arm's fallback.
package splice.app.provider

import splice.core.auth.CLIENT_AUTH_KIND

internal const val CHATGPT_OAUTH = "chatgpt-oauth"
internal const val GROK_OAUTH = "grok-oauth"
internal const val KIMI_OAUTH = "kimi-oauth"

/** The head forwards the CALLER's own auth and holds none itself (campaign claude-head). */
internal const val CLIENT = CLIENT_AUTH_KIND
