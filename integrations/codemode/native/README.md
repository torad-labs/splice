The native code-mode runtime loads only the artifact embedded by `stageNativeCodeModeHost`.
It never discovers Codex through PATH or a local Codex installation. Each platform holds
`host.properties` and a deterministic `gzip -n` payload. The task verifies the decompressed
SHA-256 before packaging; the runtime verifies it again before execution.
The runtime connects through `grpc+stdio://`. Its vendored protobuf schema and checked
descriptor come from the same official source revision as the packaged hosts.

All four payloads come from official Codex standalone 0.164.0 GitHub release archives.
Each archive was verified against GitHub's published SHA-256 before extraction; both the
archive and executable identities are pinned in `host.properties`. The Linux amd64 binary
is byte-identical to the frozen 0.164.0 reference host. The release's annotated tag resolves to
`6aaac694803004a63c6f393d153c96d22521a6b9`, the protocol source used for this upgrade.

Both Linux binaries passed `cosign verify-blob` using their official release Sigstore bundles,
the exact `rust-release.yml@refs/tags/rust-v0.164.0` certificate identity, GitHub's OIDC issuer,
and the expected source commit. These are source-bound blob signatures, not SLSA build attestations.
For macOS and Windows, the corresponding source-built binaries were downloaded from the
[successful official release workflow](https://github.com/openai/codex/actions/runs/39894017941),
whose recorded head SHA is the same commit. The GitHub-published digest of each Actions ZIP
was verified before extraction, and the extracted host SHA-256 equals its release executable.
macOS uses artifact `12478021627` (`aarch64-apple-darwin-signed-binaries`); Windows uses artifact
`12477189782` (`python-runtime-wheel-x86_64-pc-windows-msvc`). The Windows host was extracted
from that artifact's wheel. Platform code signatures were not independently revalidated on Linux.

| Platform | Executable SHA-256 |
|---|---|
| linux-amd64 | `8ee4f472e92ac226a189e98998633b2d07f989f208515750764db660369f2926` |
| linux-aarch64 | `7d28b996b825b13ea058af67655b619a26a125d61d4fb2985694a9c4e77d2e21` |
| darwin-aarch64 | `416721e33af8822041dc650793512b3e58d43e0be4f3a5c6187eb4dfaad3148a` |
| windows-amd64 | `be52517ca0f9a8eb626ec065ed3a357b5ee150fe1934a254d0126e6d40f29b57` |

The supported release target inventory remains linux-amd64, linux-aarch64,
darwin-aarch64, and windows-amd64. Local builds package their host target. All-platform
and hosted builds package all four verified payloads. Missing targets fail the build;
it cannot quietly substitute an installed CLI or ship only one platform.

Upstream Codex is Apache-2.0 licensed; its repository license is included beside the payloads.
