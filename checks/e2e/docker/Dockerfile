# checks/e2e/docker/Dockerfile — a NEW MACHINE for the fresh-install e2e.
#
# Nothing splice-specific is baked in: the image is the prerequisite set the README asks a new
# operator to have (Java 21+, Node 24, python3, curl, bash, Claude Code on PATH) and an unprivileged
# user with an empty HOME. The release artifacts and the checkout are mounted at run time, so the
# same image proves any build. The container runs with --network none: every byte the daemon and
# the wrapper move goes to the two in-container mock upstreams, or the run fails.
FROM eclipse-temurin:21-jre-noble

# Every input is pinned so two builds a month apart prove the same machine (review of #116):
# Node from the official tarball, verified against its published sha256; Claude Code at an exact
# version (run.sh overrides it with the host's, when one is discoverable).
ARG NODE_VERSION=24.20.0
ARG NODE_SHA256=2f2c0da162318f0de47665410c7c8c2ed3d36c8f3105de4bbc61176c70a7cbf2
ARG CLAUDE_CODE_VERSION=2.1.257
# The unprivileged user's uid — run.sh passes the host's so the bind-mounted /out and /artifacts
# are readable/writable on both sides (the noble base already owns uid 1000 as `ubuntu`).
ARG UID=1000

# iproute2 gives `ss` for the socket assertions; procps gives `pgrep` for the daemon assertions;
# xz-utils unpacks the Node tarball.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl bash python3 iproute2 procps xz-utils \
 && curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.xz" -o /tmp/node.tar.xz \
 && echo "${NODE_SHA256}  /tmp/node.tar.xz" | sha256sum -c - \
 && tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1 --exclude='*/CHANGELOG.md' --exclude='*/README.md' --exclude='*/LICENSE' \
 && rm /tmp/node.tar.xz \
 && npm install -g "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}" \
 && npm cache clean --force \
 && rm -rf /var/lib/apt/lists/*

RUN (userdel -r ubuntu 2>/dev/null || true) && useradd -m -u "${UID}" -s /bin/bash tester \
 && mkdir -p /out && chown tester:tester /out

USER tester
WORKDIR /home/tester
ENV HOME=/home/tester
ENV PATH="/home/tester/.local/bin:${PATH}"
