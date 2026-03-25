#pragma once
// WebSocketServer.h — 轻量级 WebSocket 服务器（仅依赖 WinSock2）
//
// 设计对照 sysmon-ws/src/server.rs：
//   · 独立监听线程接受客户端连接
//   · 每个客户端在独立线程中服务
//   · broadcast channel 模拟：主线程调用 Broadcast() 推送数据给所有已连接客户端
//   · 协议：RFC 6455 WebSocket，纯文本帧（opcode = 0x81）

#ifndef _WEBSOCKET_SERVER_H_
#define _WEBSOCKET_SERVER_H_

#pragma comment(lib, "ws2_32.lib")

#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <string>
#include <vector>
#include <thread>
#include <mutex>
#include <atomic>
#include <functional>

// ─── 单个 WebSocket 客户端会话 ───────────────────────────────────────────────

struct WsClient
{
    SOCKET  sock{ INVALID_SOCKET };
    bool    handshake_done{ false };
    std::thread thread;
};

// ─── WebSocket 服务器 ────────────────────────────────────────────────────────

class CWebSocketServer
{
public:
    CWebSocketServer();
    ~CWebSocketServer();

    // 启动服务器，监听指定端口（默认 9001）
    // 返回 true 表示启动成功
    bool Start(unsigned short port = 9001);

    // 停止服务器，关闭所有连接
    void Stop();

    // 向所有已完成握手的客户端广播一条 UTF-8 文本消息
    void Broadcast(const std::string& message);

    // 当前连接数（已完成握手的客户端）
    int GetClientCount() const;

    bool IsRunning() const { return m_running.load(); }

private:
    // ── 监听线程函数 ──────────────────────────────────────────────────────────
    void AcceptLoop();

    // ── 单客户端服务线程函数 ──────────────────────────────────────────────────
    void ClientLoop(WsClient* client);

    // ── WebSocket 握手 ────────────────────────────────────────────────────────
    // 读取 HTTP Upgrade 请求，发送 101 Switching Protocols
    // 返回 true 表示握手成功
    static bool DoHandshake(SOCKET sock);

    // 构造 WebSocket 文本帧（RFC 6455，服务器端发送，mask bit = 0）
    static std::string BuildTextFrame(const std::string& payload);

    // 计算 Sec-WebSocket-Accept 值
    static std::string ComputeAcceptKey(const std::string& sec_key);

    // Base64 编码
    static std::string Base64Encode(const unsigned char* data, size_t len);

    // SHA-1（用于握手）
    static void SHA1(const unsigned char* data, size_t len, unsigned char out[20]);

    // 解析 HTTP 头部中某个字段的值
    static std::string GetHeaderValue(const std::string& request, const std::string& field);

private:
    SOCKET                      m_listen_sock{ INVALID_SOCKET };
    std::atomic<bool>           m_running{ false };
    std::thread                 m_accept_thread;

    mutable std::mutex          m_clients_mutex;
    std::vector<WsClient*>      m_clients;          // 所有客户端（含未握手完成的）
};

#endif // _WEBSOCKET_SERVER_H_
