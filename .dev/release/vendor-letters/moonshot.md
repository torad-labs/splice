# Draft — to Moonshot AI / Kimi Developer Relations

Subject: Third-party reuse of the Kimi CLI device-flow OAuth identity — permitted?

Hello,

splice is an open-source local proxy that lets Anthropic's Claude Code drive a
user's own Kimi subscription on loopback. Your docs support third-party coding
agents via Kimi Console API keys — splice supports that route (including the
pay-per-token Anthropic-compatible base with `MOONSHOT_API_KEY`). Separately,
splice can currently also reuse the Kimi CLI's device-flow OAuth client
identity: it keeps its own credential file (`~/.config/splice/auth/kimi.json`)
and reads the Kimi CLI's file (`~/.kimi/credentials/kimi-code.json`) only if a
user configures it to. On that route it also calls
`https://api.kimi.com/coding/v1/usages` on a five-minute poll per signed-in
account (the default) to show users their own allowance, and a user may sign in
more than one of their own accounts, which splice switches between when one
runs out. Two questions:

1. May a third-party open-source client reuse the Kimi CLI OAuth client
   identity for the user's own subscription?
2. If not, is the Console API key the only sanctioned third-party route, or can
   a dedicated OAuth client be issued for tools like splice?

Project: splice — https://github.com/torad-labs/splice (MIT, personal open-source project)
Maintainer: Marcos Paulo Souza Damasceno <marcospaulo.s.d@gmail.com>

The integration in question documents its credential file as
password-equivalent. We will promptly implement whatever outcome you indicate,
including disabling or removing the route.
