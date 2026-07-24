# Draft — to OpenAI Developer/Partnerships (Codex)

Subject: Third-party OAuth client-identity reuse for Codex subscriptions — permitted?

Hello,

splice is an open-source local proxy that lets Anthropic's Claude Code drive a
user's own ChatGPT Codex subscription on loopback. To authenticate, it currently
reproduces the PUBLIC Codex CLI OAuth client identity
(`app_EMoamEEZ73f0CkXaXp7hrann`), performs the same PKCE authorize flow and
scopes, reads and refreshes `~/.codex/auth.json` byte-compatibly with the Codex
CLI, and calls `https://chatgpt.com/backend-api/codex` with the resulting
subscription credential.

Your published docs cover ChatGPT sign-in for Codex's own CLI/app/IDE clients,
and your CI/CD guidance says third parties should let Codex refresh `auth.json`
rather than calling the token endpoint themselves — but they do not address a
generic third-party client like ours. Three questions:

1. Is reusing the public Codex CLI OAuth client identity (and directly calling
   the OAuth token endpoint to refresh) permitted for a third-party open-source
   client acting on the user's own subscription?
2. If not, is there a sanctioned route — a registrable OAuth client, the
   documented access-token workflows, or another supported surface — for
   third-party tools to use a Codex subscription with user consent?
3. Is direct use of `chatgpt.com/backend-api/codex` by such a client permitted,
   or is the OpenAI Platform API the only supported programmatic surface?

Project: splice — https://github.com/torad-labs/splice (MIT, personal open-source project)
Maintainer: Marcos Paulo Souza Damasceno <marcospaulo.s.d@gmail.com>

splice is not affiliated with or endorsed by your company. The integration in
question ships labeled EXPERIMENTAL, with the credential file documented as
password-equivalent. We will promptly implement whatever outcome you indicate,
including disabling or removing the route.
