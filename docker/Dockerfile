FROM ubuntu:26.04

ENV NODE_VERSION=24.19.0

RUN DEBIAN_FRONTEND=noninteractive apt-get update && apt-get install -y \
    python3 \
    python3-pip \
    python3-venv \
    git \
    curl \
    ca-certificates \
    gnupg \
    iputils-ping \
    vim \
    zip \
    unzip \
    xz-utils \
    file \
    jq \
    bc \
    net-tools \
    iproute2 \
    gh \
    htop \
    && rm -rf /var/lib/apt/lists/*

# Install Node.js v24.19.0 (LTS) via official binary distribution
RUN ARCH=$(dpkg --print-architecture) && \
    case "${ARCH}" in \
      amd64) NODE_ARCH="x64" ;; \
      arm64) NODE_ARCH="arm64" ;; \
      *) echo "Unsupported architecture: ${ARCH}" && exit 1 ;; \
    esac && \
    curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-${NODE_ARCH}.tar.xz" | tar -xJ -C /usr/local --strip-components=1

# Verify Node.js and npm installations
RUN node -v && npm -v

# Install Claude Code globally via npm, pinned to the version resolved on the
# build host (npm `stable` dist-tag — see ResolveVersions / the buildImage task).
# Pinning fixes the starting point only; Claude Code self-updates at runtime.
ARG CLAUDE_VERSION
RUN npm install -g "@anthropic-ai/claude-code@${CLAUDE_VERSION:?CLAUDE_VERSION build-arg is required}"

# Register the bitkentech marketplace and install the shipsmooth plugin
RUN --mount=type=secret,id=claude_api_key \
    CLAUDE_API_KEY=$(cat /run/secrets/claude_api_key) claude plugin marketplace add bitkentech/claude-plugins && \
    CLAUDE_API_KEY=$(cat /run/secrets/claude_api_key) claude plugin install shipsmooth --scope user

# Component versions and image identity. OCI labels are immutable and baked in
# here; they are invisible in the Docker Hub UI, so the Overview + the compound
# tag name carry the same information visibly. Read them with:
#   docker inspect --format '{{json .Config.Labels}}' <image>
ARG SHIPSMOOTH_VERSION
ARG IMAGE_VERSION
ARG VCS_REF
ARG BUILD_DATE
LABEL org.opencontainers.image.title="shipsmooth-claude sandbox" \
      org.opencontainers.image.description="Ubuntu + Node + Claude Code + the shipsmooth plugin, ready to pull-and-run" \
      org.opencontainers.image.source="https://github.com/bitkentech/shipsmooth" \
      org.opencontainers.image.version="${IMAGE_VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      io.bitken.ss.claude-code.version="${CLAUDE_VERSION}" \
      io.bitken.ss.shipsmooth.version="${SHIPSMOOTH_VERSION}"

WORKDIR /workspace

# Set default command to enter bash or launch Claude Code directly
CMD ["bash"]
