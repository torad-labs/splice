// Where a provider publishes its model list. Moved from features/models (2026-10-10) so the add command and the
// models listing share ONE derivation without one feature module depending on the other.
package splice.core.topology

/** Where a provider publishes its model list. The dialect decides the default; an operator whose
 *  vendor puts it somewhere else says so with `models_url`, which is why there is no per-vendor
 *  table here — a hardcoded vendor list is the thing this whole file exists to retire. */
public object UpstreamRosterUrl {

    /** The Codex backend lists `GET {base}/models?client_version=<v>` and answers with the models
     *  whose `minimal_client_version` is at or below <v> — measured 2026-09-22 against
     *  chatgpt.com/backend-api/codex: no version is HTTP 400, `0.1.0` an empty list, `0.200.0` and
     *  above all nine models. The version is a claim about the CLIENT, and here splice is the client:
     *  it speaks the Responses wire itself rather than running codex-rs, so it claims every model the
     *  account may use, which is what every other dialect's list already returns. Pinning a codex-rs
     *  release instead would hide each new model until someone bumped it — the invisible-model
     *  failure discovery exists to end. A model splice cannot drive is excluded the way it is on any
     *  provider, with `discovery`. */
    public const val CODEX_LIST_CLIENT_VERSION: String = "999.0.0"

    /** The list URL for this provider: [override] when one is configured, else its dialect's own.
     *  [authKind] matters for one shape — `chatgpt-oauth` is the Codex backend, whose list takes the
     *  client version above; an api-key Responses provider is the OpenAI API, which lists at
     *  `{base}/models` like every OpenAI-compatible endpoint. */
    public fun of(dialect: Dialect, baseUrl: String, override: String?, authKind: String = ""): String {
        override?.takeIf { it.isNotBlank() }?.let { return it }
        val base = baseUrl.trimEnd('/')
        return when (dialect) {
            Dialect.OPENAI_CHAT -> "$base/models"
            Dialect.ANTHROPIC_PASSTHROUGH -> "$base/v1/models"
            Dialect.OPENAI_RESPONSES ->
                if (authKind == AuthKind.ChatgptOAuth.wire) {
                    "$base/models?client_version=$CODEX_LIST_CLIENT_VERSION"
                } else {
                    "$base/models"
                }
        }
    }

    /** The list URL [provider] is asked at — one derivation for the probe, the cache it fills, and
     *  the daemon's line when the endpoint gives no answer in time. */
    public fun of(provider: ProviderConfig): String =
        of(provider.dialect, provider.baseUrl, provider.modelsUrl, provider.auth.kind)
}
