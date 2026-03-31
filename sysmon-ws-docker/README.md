# sysmon-ws-docker

轻量级系统监控 WebSocket 服务，设计用于在 Docker 容器中运行在 Linux (x86_64) 主机上。

## 功能

- WebSocket 服务监听 `0.0.0.0:9527`
- 有客户端连接时每秒推送 CPU / 内存 / 网速数据（JSON 格式）
- 无客户端连接时不采集数据（节省 CPU）

## 快速部署

### 在 NAS 上构建并启动

```bash
# 克隆项目
cd /path/to/sysmon-ws-docker

# 构建并启动
docker-compose up -d --build

# 查看日志
docker-compose logs -f

# 停止
docker-compose down
```

### 本地交叉编译（可选）

如果想在 macOS/Windows 上编译 Linux 二进制：

```bash
# 安装交叉编译目标
rustup target add x86_64-unknown-linux-gnu

# 安装交叉编译工具（macOS）
brew install FiloSottile/musl-cross/musl-cross

# 编译
cargo build --release --target x86_64-unknown-linux-gnu
```

## 数据格式

每秒推送的 JSON 格式：

```json
{
  "timestamp": "2026-03-31T02:20:00+08:00",
  "cpu_usage_percent": 15.3,
  "cpu_per_core": [12.1, 18.5, 20.0, 10.5],
  "memory_usage_percent": 45.2,
  "memory_used_mb": 7234,
  "memory_total_mb": 16384,
  "net_rx_kbps": 150.5,
  "net_tx_kbps": 30.2
}
```

## 测试连接

```bash
# 使用 websocat（需要安装）
websocat ws://localhost:9527

# 或使用 wscat
wscat -c ws://localhost:9527
```

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `LISTEN_ADDR` | `0.0.0.0:9527` | 监听地址 |
| `INTERVAL_MS` | `1000` | 推送间隔（毫秒） |
| `RUST_LOG` | `info` | 日志级别 |
