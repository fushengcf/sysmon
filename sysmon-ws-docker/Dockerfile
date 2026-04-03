FROM rust:1.85-bookworm AS builder

WORKDIR /app
COPY Cargo.toml Cargo.lock ../Cargo.toml ../Cargo.lock ../sysmon-core/ ./
# 创建空 src 以便缓存依赖
RUN mkdir src && echo "fn main(){}" > src/main.rs
RUN cargo build --release 2>/dev/null || true
RUN rm -rf src

# 复制真实源码并编译
COPY src/ src/
RUN rm -rf target/release/deps/sysmon_ws_docker* target/release/sysmon-ws-docker && cargo build --release

# ---------- 运行阶段 ----------
FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/target/release/sysmon-ws-docker /usr/local/bin/sysmon-ws-docker

ENV LISTEN_ADDR=0.0.0.0:9527
ENV INTERVAL_MS=1000
ENV RUST_LOG=info

EXPOSE 9527

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD bash -c 'echo > /dev/tcp/localhost/9527' || exit 1

CMD ["sysmon-ws-docker"]
