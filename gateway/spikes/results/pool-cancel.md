# P0-POOL receipt: CIO client cancel-mid-stream x500 (2026-07-16)

- rounds completed: 500/500
- wall time: 1211ms (2ms/round)
- TCP sockets before/after (post 6s settle): 2 / 2 (delta 0, bar < 50)
- post-storm liveness GET: pong

## Verdict
PASS — CIO client returns/discards connections cleanly on coroutine cancellation; pool bounded, liveness intact. Cleared for P2-MACH plumbing.
