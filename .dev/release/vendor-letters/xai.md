# Draft — to xAI Developer Relations (Grok)

Subject: Third-party reuse of the Grok CLI OAuth client identity — permitted?

Hello,

splice is an open-source local proxy that lets Anthropic's Claude Code drive a
user's own SuperGrok / X Premium+ subscription on loopback. To authenticate, it
currently reproduces the public Grok CLI OAuth client identity and credential
file (`~/.grok/auth.json`) so a user's existing browser login carries over.

We noted xAI officially supports subscription use through the Grok integration
in OpenCode, which suggests third-party subscription access is a supported
pattern in at least one sanctioned integration. Two questions:

1. May a third-party open-source client reuse the Grok CLI's public OAuth
   client identity and credential format for the user's own subscription?
2. If not, can xAI issue a dedicated OAuth client identity for splice, or point
   us at the sanctioned third-party route (as with OpenCode)?

We use "Grok" descriptively only, per your brand guidelines.

Project: splice — https://github.com/torad-labs/splice (MIT, personal open-source project)
Maintainer: Marcos <marcos@grailseekers.shop>

splice is not affiliated with or endorsed by your company. The integration in
question ships labeled EXPERIMENTAL, with the credential file documented as
password-equivalent. We will promptly implement whatever outcome you indicate,
including disabling or removing the route.
