# SysMon 系统监控套件

跨平台系统监控项目，通过 WebSocket 实时流式传输 CPU、内存、网络等系统指标，同时支持摄像头 RTMP 推流。

> 🔧 CI/CD: Android 自动构建已配置

## 项目架构

```
┌──────────────────────────────────────────────────────────────────────┐
│                           客户端应用                                  │
├──────────────┬──────────────┬──────────────┬────────────┬────────────┤
│ sysmon-android│  sysmon-ios  │  sysmon-rn   │ sysmon-cam │TrafficMon. │
│   (Kotlin)    │   (Swift)    │(React Native)│  (Kotlin)  │ (C++ MFC)  │
└──────┬───────┴──────┬───────┴──────┬───────┴─────┬──────┴─────┬──────┘
       │              │              │             │            │
       └──────────────┴──────────────┼─────────────┘            │
                                     │ WebSocket                │
                                     ▼                          │
                        ┌────────────────────────┐              │
                        │       sysmon-ws        │              │
                        │    (Rust 后端服务)      │              │
                        │  CPU/Mem/Net 监控       │              │
                        └────────────────────────┘              │
                                                                │
                                                    Windows 桌面悬浮窗
```

## 子项目说明

### 1. sysmon-ws (Rust)
**跨平台 WebSocket 服务端**

- 技术栈: Rust + Tokio + tokio-tungstenite
- 功能: 采集系统 CPU、内存、网络指标，通过 WebSocket 实时推送
- 支持平台: macOS / Windows / Linux
- 特点: 高性能、低资源占用的后端服务

### 2. sysmon-android (Kotlin)
**Android 监控 + 推流应用**

- 技术栈: Kotlin + Jetpack Compose
- 功能:
  - 连接 sysmon-ws WebSocket 服务，实时显示 CPU、内存、网速、GPU 等指标
  - 摄像头 RTMP 推流（基于 RootEncoder），支持前后摄切换、画质档位选择、视频帧内嵌时间戳
  - 推流后台保活（前台服务），切换页面不中断推流
  - RTMP 播放器（拉流预览）
- 包含: 桌面小组件 (CPU/内存/网速)

### 3. sysmon-cam (Kotlin)
**独立摄像头推流应用**

- 技术栈: Kotlin + Jetpack Compose + RootEncoder
- 功能: 独立的 RTMP 摄像头推流 App，不依赖系统监控模块
- 特点: 轻量独立，专注推流场景

### 4. sysmon-ios (Swift)
**iOS 监控应用**

- 技术栈: Swift + SwiftUI
- 功能: 连接 sysmon-ws WebSocket 服务，实时显示系统指标
- 包含: 仪表盘图表、CPU 核心使用率可视化

### 5. sysmon-rn (React Native)
**跨平台监控应用**

- 技术栈: React Native + JavaScript
- 功能: 连接 sysmon-ws WebSocket 服务，支持 Android/iOS 双平台
- 轻量级实现

### 6. sysmon-un (Rust)
**通用 Rust 客户端**

- 技术栈: Rust
- 功能: 命令行/Web 服务模式，支持托盘运行
- 特点: 简洁实现，包含 HTML 客户端演示

### 7. TrafficMonitor (C++ MFC)
**Windows 桌面监控软件**

- 技术栈: C++ + MFC + Windows API
- 功能: Windows 任务栏网速/硬件监控悬浮窗
- 来源: 基于 zhongyang219/TrafficMonitor 移植
- 特点: 支持皮肤更换、温度监控、插件系统

## 快速开始

### 后端服务 (sysmon-ws)
```bash
cd sysmon-ws
cargo run --release
```

### 客户端应用
根据平台选择对应的客户端项目，按照各项目 README 构建运行。

## 技术特点

- **实时流式传输**: WebSocket 长连接，低延迟推送
- **跨平台**: 支持 macOS、Windows、Linux、Android、iOS
- **高性能**: Rust 后端，高效的系统信息采集
- **摄像头推流**: RTMP 推流支持，推流期间切换页面不中断，视频帧内嵌时间戳
- **模块化设计**: 后端与前端解耦，可独立使用

## License

MIT
