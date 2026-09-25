# Draft — to Meta Developer Relations (Muse Code)

Subject: Third-party use of the Muse Code OAuth client identity — permitted?

Hello,

splice is an open-source local proxy that lets Anthropic's Claude Code drive a
user's own Muse Code subscription on loopback (`claude-muse`). To authenticate,
it reproduces the Muse Code CLI's public OAuth client identity
(`1031625952748946`) in the RFC 8628 device flow at `auth.meta.com`, exchanges
the account token at `https://api.meta.ai/muse-code/key` for the subscription's
inference key, and sends that key as a bearer to
`https://api.meta.ai/v1/messages` with a `splice/<version>` User-Agent. It keeps
its own credential file (`~/.config/splice/auth/muse.json`) and reads the Muse
Code CLI's file only if a user configures it to. As we found no usage endpoint,
it also calls `muse-code/key` on a five-minute poll per signed-in account (the
default) to read `subs_usage` and show users their own allowance. A user may
sign in more than one of their own accounts, and splice switches to another
when one runs out; its README requires every account to be one the user owns
and is entitled to use under the provider's terms.

Two questions:

1. May a third-party open-source client use the Muse Code CLI's public OAuth
   client identity and the key mint for the user's own subscription?
2. If not, can Meta issue a dedicated OAuth client identity for splice, or point
   us at the route Meta wants third-party clients to use?

We use "Muse" and "Meta" descriptively only.

Project: splice — https://github.com/torad-labs/splice (MIT, personal open-source project)
Maintainer: Marcos Paulo Souza Damasceno <marcospaulo.s.d@gmail.com>

splice is not affiliated with or endorsed by Meta. The Muse route ships labeled
unofficial and at the user's own risk, with the credential file documented as
password-equivalent. We will promptly implement whatever outcome you indicate,
including disabling or removing the route.
