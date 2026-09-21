# tools/e2e/docker/Dockerfile — a NEW MACHINE for the fresh-install e2e.
#
# Nothing splice-specific is baked in: the image is the prerequisite set the README asks a new
# operator to have (Java 21+, Node 24, python3, curl, bash, Claude Code on PATH) and an unprivileged
# user with an empty HOME. The release artifacts and the checkout are mounted at run time, so the
# same image proves any build. The container runs with --network none: every byte the daemon and
# the wrapper move goes to the two in-container mock upstreams, or the run fails.
FROM eclipse-temurin:21-jre-noble

# Every input is pinned so two builds a month apart prove the same machine (review of #116):
# Node from the official tarball, verified against its published sha256; Claude Code at an exact
# version (run.sh passes Versions.kt's TESTED_CLAUDE_CODE value explicitly).
ARG NODE_VERSION=24.20.0
ARG NODE_SHA256=2f2c0da162318f0de47665410c7c8c2ed3d36c8f3105de4bbc61176c70a7cbf2
ARG CLAUDE_CODE_VERSION=2.1.257
# Bun runs the mock upstream and wire probe; match package.json's pinned packageManager.
ARG BUN_VERSION=1.4.2
ARG BUN_SHA256=36368faef7527875d5ffa52e53cd48021741f2a83eb6208a8dd64068d422a913
# The unprivileged user's uid — run.sh passes the host's so the bind-mounted /out and /artifacts
# are readable/writable on both sides (the noble base already owns uid 1000 as `ubuntu`).
ARG UID=1000

# iproute2 gives `ss` for the socket assertions; procps gives `pgrep` for the daemon assertions;
# xz-utils unpacks the Node tarball.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl bash python3 iproute2 procps xz-utils unzip \
 && curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.xz" -o /tmp/node.tar.xz \
 && echo "${NODE_SHA256}  /tmp/node.tar.xz" | sha256sum -c - \
 && tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1 --exclude='*/CHANGELOG.md' --exclude='*/README.md' --exclude='*/LICENSE' \
 && rm /tmp/node.tar.xz \
 && curl -fsSL "https://github.com/oven-sh/bun/releases/download/bun-v${BUN_VERSION}/bun-linux-x64.zip" -o /tmp/bun.zip \
 && echo "${BUN_SHA256}  /tmp/bun.zip" | sha256sum -c - \
 && unzip -p /tmp/bun.zip bun-linux-x64/bun > /usr/local/bin/bun \
 && chmod 0755 /usr/local/bin/bun \
 && test "$(bun --version)" = "${BUN_VERSION}" \
 && rm /tmp/bun.zip \
 && npm install -g "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}" \
 && npm cache clean --force \
 && rm -rf /var/lib/apt/lists/*

RUN (userdel -r ubuntu 2>/dev/null || true) && useradd -m -u "${UID}" -s /bin/bash tester \
 && mkdir -p /out && chown tester:tester /out

USER tester
WORKDIR /home/tester
ENV HOME=/home/tester
ENV PATH="/home/tester/.local/bin:${PATH}"
