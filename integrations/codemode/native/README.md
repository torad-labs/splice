The native code-mode runtime loads only the artifact embedded by `stageNativeCodeModeHost`.
It never discovers Codex through PATH or a local Codex installation. Each platform holds
`host.properties` and a deterministic `gzip -n` payload. The task verifies the decompressed
SHA-256 before packaging; the runtime verifies it again before execution.

All four payloads come from official Codex standalone 0.159.1 GitHub release archives.
Each archive was verified against GitHub's published SHA-256 before extraction; both the
archive and executable identities are pinned in `host.properties`. The Linux amd64 binary
is byte-identical to the installed reference host. The release's annotated tag resolves to
`8e68a98ef03cdde76d2e6800791ebdf1b3b95b24`; build attestation has not been verified.
The protocol source used for behavioral comparison is the later Codex revision
`17a9df60e420b58e3edc55efb1bb052e39492bbc`. Equality between those source revisions
and the release binary is not claimed.

The supported release target inventory remains linux-amd64, linux-aarch64,
darwin-aarch64, and windows-amd64. Local builds package their host target. All-platform
and hosted builds package all four verified payloads. Missing targets fail the build;
it cannot quietly substitute an installed CLI or ship only one platform.

Upstream Codex is Apache-2.0 licensed; its repository license is included beside the payloads.
