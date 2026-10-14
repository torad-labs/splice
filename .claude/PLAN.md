# Splice request performance and retention

Authoritative plan: docs/architecture/request-performance.md.

Marcos authorized optimizing all identified request operations and concurrency paths for 50-70 sessions, with full functionality preserved, and instrumenting unnecessarily retained heavy objects. Required Chinese-source research is complete. Do not ask again whether to implement.

Sequence: research complete; implementation IN PROGRESS across runtime and console fronts; then one integrated release gate, fresh delta review and representative synthetic concurrent-load comparison; install only a green reviewed candidate; report measured outcomes and remaining gaps.

Front leads route the plan's nonoverlapping scopes to platform, sessions, config and usage. Small helpers do bounded reading/checking. Existing peer edits are never reverted. Same branch/checkout; platform alone pushes. Keep pre-push until PR #247 merges. No per-edit tests or duplicate benchmarks.

Done: identified applicable optimizations are implemented or receive a specific evidence-backed nonapplicability disposition, bounded aggregate retention telemetry is available, exact-byte/recovery/auth/quota contracts hold, the full gate and review pass, the representative measurement is complete, and the installed candidate is verified. Assignments, source hypotheses and green tests alone do not establish performance gains.

Release and security obligations remain open: V4-444 still needs Marcos's eye before merging and beta-only release from main. The exposed management bearer needs rotation with same-head session recovery and private draft preservation; do not strand clients or treat that as a CPU-install dependency.
