# claude-max head — live probe receipt

Campaign `claude-head`, item CH-11. 2026-08-15.

**Status: HALF PROVEN. The unauthenticated half is done and reproducible; the authenticated half is
the operator's to run** (it needs a credential splice deliberately never holds, and it spends
subscription quota). Read that split literally — this receipt does not claim the head has served a
real turn.

## What was proven, autonomously, with no credential and no quota

A real `Daemon` booted from a temporary topology (`auth = { kind = "client" }`, base URL
`https://api.anthropic.com`) and served a turn on a loopback head, forwarding a deliberately
invalid bearer.

    ./gradlew :app:test --tests live.ClaudeHeadLiveProbeTest   # with SPLICE_LIVE_PROBE=1

Through the head:

    event: error
    data: {"type":"error","error":{"type":"authentication_error",
           "message":"Invalid bearer token — run: claude-max login"}}

Directly against the vendor, same body shape, same invalid bearer
(`PROBE` holds a deliberately non-credential string — the probe never uses a real one):

    curl -sS https://api.anthropic.com/v1/messages \
      -H "Authorization: Bearer $PROBE" \
      -H "anthropic-version: 2023-06-01" -H "content-type: application/json" \
      -d '{"model":"claude-fable-5","max_tokens":16,"stream":true,
           "messages":[{"role":"user","content":"probe"}]}'

    HTTP_STATUS=401
    {"type":"error","error":{"type":"authentication_error","message":"Invalid bearer token"},
     "request_id":"req_011Ce5Gj8V34yzBLiSGs93Tz"}

What that establishes:

1. **The endpoint is real and was reached.** Anthropic returned a `request_id`, which only its own
   servers mint. Host, TLS and path resolve.
2. **The request SHAPE is accepted.** 401, not 400 — the vendor parsed the body this dialect builds
   and got as far as authenticating it. A malformed request would have been rejected earlier, and
   the probe asserts the absence of `invalid_request_error` precisely to catch that.
3. **Splice injected no credential of its own.** The invalid bearer the caller sent is the one that
   was judged; had the head added anything, the outcome would differ.
4. **The failure surfaces honestly to the client**, as `authentication_error` carrying this head's
   own sign-in hint (`claude-max login`) rather than a bare or misleading error.

What it does NOT establish: that an authenticated turn succeeds, that streaming/thinking blocks
render, that prompt caching is credited, or that rate-limit headers land in telemetry.

## The remaining half — operator, once

Needs the operator's own Claude login, spends a little subscription quota, and cannot be delegated:
splice never reads, stores or refreshes an Anthropic credential, so the only way this turn happens
is a real client authenticating natively.

1. Add the `[providers.anthropic]` + `[heads.claude-max]` blocks from `config/splice.example.toml`
   to `~/.config/splice/splice.toml`, then restart the daemon.
2. `splice install` (or the usual wrapper install) so `claude-max` is on PATH, then run `claude-max`.
   Because this is a client-auth head, the launcher leaves your credentials, keychain and `/login`
   alone — if it is not signed in, `/login` inside the session works normally.
3. Send one prompt, then one that uses a tool (a round trip).

Record here afterwards: model served, whether `/login` behaved, `cache_creation_input_tokens` /
`cache_read_input_tokens` from `/api/usage` (prompt caching is the economic reason the head
preserves `cache_control`), whether thinking blocks rendered, and whether
`anthropic-ratelimit-*` headers reached the statusline.

If any of that misbehaves, the wiring to suspect first is the forwarding allowlist in
`HeadServer.FORWARDED_CLIENT_HEADERS` — it is the one place a header the vendor needs could be
missing.
