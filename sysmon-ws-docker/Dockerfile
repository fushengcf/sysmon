FROM rust:1.86-bookworm AS builder

WORKDIR /app
COPY Cargo.toml ./
RUN mkdir src && echo "fn main(){}" > src/main.rs
RUN cargo build --release 2>/dev/null || true
RUN rm -rf src

COPY src/ src/
RUN rm -rf target/release/deps/sysmon_ws_docker* target/release/sysmon-ws-docker && cargo build --release

# ---------- 运行阶段 ----------
FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    nginx \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/target/release/sysmon-ws-docker /usr/local/bin/sysmon-ws-docker
COPY nginx.conf /etc/nginx/nginx.conf
COPY client-demo.html /usr/share/nginx/html/index.html

RUN mkdir -p /var/run/nginx && \
    chown -R www-data:www-data /var/run/nginx

EXPOSE 9002 9001

CMD ["/bin/sh", "-c", "/usr/local/bin/sysmon-ws-docker & nginx -g 'daemon off;'"]
