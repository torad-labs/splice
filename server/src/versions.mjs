// Single source of truth for proxy protocol versions. The launcher's version
// handshake imports THIS (importing the entries would start a server), and the
// entries re-export from here — the launcher and proxy can never disagree.
export const CODEX_PROXY_VERSION = '35'; // v34 + THE compaction fix: the stream watchdog reaped big-context prefills (compaction re-reads ~160k uncached, minutes before first byte) at streamIdleMs as if zombies → every compaction aborted mid-prefill → retry loop re-read the transcript each attempt. Idle now uses firstByteTimeoutMs until the first byte; streamIdleMs only applies once streaming has started.
export const CONTROL_SERVER_VERSION = '1'; // spliced: centralized dashboard + cross-head management (auth, usage soft-warn, config, full lifecycle)
