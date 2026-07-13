// Single source of truth for proxy protocol versions. The launcher's version
// handshake imports THIS (importing the entries would start a server), and the
// entries re-export from here — the launcher and proxy can never disagree.
export const CODEX_PROXY_VERSION = '30'; // mythos decomposition + SSE-overflow fix + tools-agnostic compact detection
export const CLAUDITHOS_PROXY_VERSION = '3'; // v2 + mythos decomposition + management plane
