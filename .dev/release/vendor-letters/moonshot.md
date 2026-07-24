# Draft — to Moonshot AI / Kimi Developer Relations

Subject: Third-party reuse of the Kimi CLI device-flow OAuth identity — permitted?

Hello,

splice is an open-source local proxy that lets Anthropic's Claude Code drive a
user's own Kimi subscription on loopback. Your docs support third-party coding
agents via Kimi Console API keys — splice supports that route (including the
pay-per-token Anthropic-compatible base with `MOONSHOT_API_KEY`). Separately,
splice can currently also reuse the Kimi CLI's device-flow OAuth client
identity and credential file (`~/.kimi/credentials/kimi-code.json`) so an
existing Kimi Code login carries over. Two questions:

1. May a third-party open-source client reuse the Kimi CLI OAuth client
   identity and credential file for the user's own subscription?
2. If not, is the Console API key the only sanctioned third-party route, or can
   a dedicated OAuth client be issued for tools like splice?

Project: splice — https://github.com/torad-labs/splice (MIT, personal open-source project)
Maintainer: Marcos Paulo Souza Damasceno <marcospaulo.s.d@gmail.com>

splice is not affiliated with or endorsed by your company. The integration in
question ships labeled EXPERIMENTAL, with the credential file documented as
password-equivalent. We will promptly implement whatever outcome you indicate,
including disabling or removing the route.
