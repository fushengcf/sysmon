// WebSocketServer.cpp — 基于 WinSock2 的轻量 WebSocket 服务器实现
//
// 设计参照 sysmon-ws/src/server.rs：
//   · AcceptLoop()   对应 Rust 的 listener.accept() 循环
//   · ClientLoop()   对应 Rust 的 handle_connection()
//   · Broadcast()    对应 Rust 的 broadcast::Sender::send()
//   · SHA-1 / Base64 均为自包含实现，不依赖任何第三方库

#include "stdafx.h"
#include "WebSocketServer.h"

#include <sstream>
#include <algorithm>
#include <cstring>

// ────────────────────────────────────────────────────────────────────────────
// SHA-1（RFC 3174）— 内联实现，仅用于 WebSocket 握手
// ────────────────────────────────────────────────────────────────────────────
namespace {

// 将 32 位整数左循环移位 n 位
inline uint32_t RotateLeft(uint32_t x, int n)
{
    return (x << n) | (x >> (32 - n));
}

// SHA-1 核心实现（输出 20 字节）
void SHA1Impl(const unsigned char* data, size_t len, unsigned char out[20])
{
    // 初始哈希值
    uint32_t h0 = 0x67452301u;
    uint32_t h1 = 0xEFCDAB89u;
    uint32_t h2 = 0x98BADCFEu;
    uint32_t h3 = 0x10325476u;
    uint32_t h4 = 0xC3D2E1F0u;

    // 构建填充消息（位补全 + 长度追加）
    size_t msg_len = len + 1 + 8; // +1 for 0x80 byte, +8 for 64-bit length
    // 对齐到 64 字节块
    size_t padded_len = (msg_len + 63) & ~(size_t)63;

    std::vector<unsigned char> msg(padded_len, 0);
    memcpy(msg.data(), data, len);
    msg[len] = 0x80;

    // 追加原始位长（big-endian 64-bit）
    uint64_t bit_len = (uint64_t)len * 8;
    for (int i = 0; i < 8; i++)
    {
        msg[padded_len - 8 + i] = (unsigned char)(bit_len >> (56 - i * 8));
    }

    // 处理每个 512 位（64 字节）块
    for (size_t offset = 0; offset < padded_len; offset += 64)
    {
        uint32_t w[80];
        for (int i = 0; i < 16; i++)
        {
            w[i] = ((uint32_t)msg[offset + i * 4] << 24)
                 | ((uint32_t)msg[offset + i * 4 + 1] << 16)
                 | ((uint32_t)msg[offset + i * 4 + 2] << 8)
                 |  (uint32_t)msg[offset + i * 4 + 3];
        }
        for (int i = 16; i < 80; i++)
        {
            w[i] = RotateLeft(w[i-3] ^ w[i-8] ^ w[i-14] ^ w[i-16], 1);
        }

        uint32_t a = h0, b = h1, c = h2, d = h3, e = h4;

        for (int i = 0; i < 80; i++)
        {
            uint32_t f, k;
            if (i < 20)
            {
                f = (b & c) | ((~b) & d);
                k = 0x5A827999u;
            }
            else if (i < 40)
            {
                f = b ^ c ^ d;
                k = 0x6ED9EBA1u;
            }
            else if (i < 60)
            {
                f = (b & c) | (b & d) | (c & d);
                k = 0x8F1BBCDCu;
            }
            else
            {
                f = b ^ c ^ d;
                k = 0xCA62C1D6u;
            }
            uint32_t temp = RotateLeft(a, 5) + f + e + k + w[i];
            e = d; d = c;
            c = RotateLeft(b, 30);
            b = a; a = temp;
        }
        h0 += a; h1 += b; h2 += c; h3 += d; h4 += e;
    }

    // 输出 big-endian
    auto store = [&](unsigned char* p, uint32_t v) {
        p[0] = (v >> 24) & 0xFF;
        p[1] = (v >> 16) & 0xFF;
        p[2] = (v >>  8) & 0xFF;
        p[3] =  v        & 0xFF;
    };
    store(out,      h0);
    store(out + 4,  h1);
    store(out + 8,  h2);
    store(out + 12, h3);
    store(out + 16, h4);
}

// Base64 编码表
static const char* BASE64_CHARS =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

std::string Base64EncodeImpl(const unsigned char* data, size_t len)
{
    std::string out;
    out.reserve(((len + 2) / 3) * 4);

    for (size_t i = 0; i < len; i += 3)
    {
        unsigned char b0 = data[i];
        unsigned char b1 = (i + 1 < len) ? data[i + 1] : 0;
        unsigned char b2 = (i + 2 < len) ? data[i + 2] : 0;

        out += BASE64_CHARS[(b0 >> 2) & 0x3F];
        out += BASE64_CHARS[((b0 << 4) | (b1 >> 4)) & 0x3F];
        out += (i + 1 < len) ? BASE64_CHARS[((b1 << 2) | (b2 >> 6)) & 0x3F] : '=';
        out += (i + 2 < len) ? BASE64_CHARS[b2 & 0x3F] : '=';
    }
    return out;
}

} // anonymous namespace

// ────────────────────────────────────────────────────────────────────────────
// CWebSocketServer
// ────────────────────────────────────────────────────────────────────────────

CWebSocketServer::CWebSocketServer()
{
    // 初始化 WinSock（允许多次调用，内部引用计数）
    WSADATA wsaData;
    WSAStartup(MAKEWORD(2, 2), &wsaData);
}

CWebSocketServer::~CWebSocketServer()
{
    Stop();
    WSACleanup();
}

// ── 启动服务器 ────────────────────────────────────────────────────────────────

bool CWebSocketServer::Start(unsigned short port)
{
    if (m_running.load())
        return true;

    // 创建监听 socket
    m_listen_sock = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (m_listen_sock == INVALID_SOCKET)
        return false;

    // 允许地址复用（快速重启）
    int opt = 1;
    setsockopt(m_listen_sock, SOL_SOCKET, SO_REUSEADDR,
               reinterpret_cast<const char*>(&opt), sizeof(opt));

    sockaddr_in addr{};
    addr.sin_family      = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port        = htons(port);

    if (::bind(m_listen_sock, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == SOCKET_ERROR)
    {
        closesocket(m_listen_sock);
        m_listen_sock = INVALID_SOCKET;
        return false;
    }

    if (::listen(m_listen_sock, SOMAXCONN) == SOCKET_ERROR)
    {
        closesocket(m_listen_sock);
        m_listen_sock = INVALID_SOCKET;
        return false;
    }

    m_running.store(true);
    m_accept_thread = std::thread(&CWebSocketServer::AcceptLoop, this);
    return true;
}

// ── 停止服务器 ────────────────────────────────────────────────────────────────

void CWebSocketServer::Stop()
{
    if (!m_running.exchange(false))
        return;

    // 关闭监听 socket，使 AcceptLoop 中的 accept() 返回错误
    if (m_listen_sock != INVALID_SOCKET)
    {
        closesocket(m_listen_sock);
        m_listen_sock = INVALID_SOCKET;
    }

    if (m_accept_thread.joinable())
        m_accept_thread.join();

    // 关闭所有客户端
    std::vector<WsClient*> to_delete;
    {
        std::lock_guard<std::mutex> lock(m_clients_mutex);
        to_delete.swap(m_clients);
    }
    for (WsClient* c : to_delete)
    {
        if (c->sock != INVALID_SOCKET)
            closesocket(c->sock);
        if (c->thread.joinable())
            c->thread.join();
        delete c;
    }
}

// ── 广播消息给所有已握手客户端 ────────────────────────────────────────────────
// 对应 sysmon-ws 中的 broadcast::Sender::send()

void CWebSocketServer::Broadcast(const std::string& message)
{
    if (message.empty() || !m_running.load())
        return;

    std::string frame = BuildTextFrame(message);

    std::lock_guard<std::mutex> lock(m_clients_mutex);
    for (WsClient* c : m_clients)
    {
        if (!c->handshake_done || c->sock == INVALID_SOCKET)
            continue;
        // 发送失败不报错，ClientLoop 会自行检测到断开
        ::send(c->sock, frame.c_str(), static_cast<int>(frame.size()), 0);
    }
}

// ── 当前连接数 ────────────────────────────────────────────────────────────────

int CWebSocketServer::GetClientCount() const
{
    std::lock_guard<std::mutex> lock(m_clients_mutex);
    int cnt = 0;
    for (const WsClient* c : m_clients)
    {
        if (c->handshake_done)
            cnt++;
    }
    return cnt;
}

// ── 接受连接循环（在独立线程中运行）────────────────────────────────────────────
// 对应 sysmon-ws 中的 listener.accept() 循环

void CWebSocketServer::AcceptLoop()
{
    while (m_running.load())
    {
        sockaddr_in peer_addr{};
        int addr_len = sizeof(peer_addr);
        SOCKET client_sock = ::accept(m_listen_sock,
                                       reinterpret_cast<sockaddr*>(&peer_addr),
                                       &addr_len);

        if (client_sock == INVALID_SOCKET)
        {
            // 服务器已停止或出错，退出循环
            break;
        }

        // 为新客户端分配会话对象
        WsClient* client = new WsClient();
        client->sock = client_sock;

        {
            std::lock_guard<std::mutex> lock(m_clients_mutex);
            m_clients.push_back(client);
        }

        // 在独立线程中服务该客户端
        client->thread = std::thread(&CWebSocketServer::ClientLoop, this, client);
    }
}

// ── 单客户端服务线程 ──────────────────────────────────────────────────────────
// 对应 sysmon-ws 中的 handle_connection()

void CWebSocketServer::ClientLoop(WsClient* client)
{
    // 执行 WebSocket 握手
    if (!DoHandshake(client->sock))
    {
        closesocket(client->sock);
        client->sock = INVALID_SOCKET;

        // 从客户端列表移除
        std::lock_guard<std::mutex> lock(m_clients_mutex);
        auto it = std::find(m_clients.begin(), m_clients.end(), client);
        if (it != m_clients.end())
            m_clients.erase(it);
        // 注意：不 delete client，因为 client->thread 是自身
        return;
    }

    client->handshake_done = true;

    // 持续接收客户端发来的帧（Ping / Close 等）
    // 数据推送由主线程通过 Broadcast() 完成，这里只需处理控制帧
    while (m_running.load() && client->sock != INVALID_SOCKET)
    {
        // 读取帧头（至少 2 字节）
        unsigned char header[2];
        int n = ::recv(client->sock, reinterpret_cast<char*>(header), 2, MSG_WAITALL);
        if (n <= 0)
            break; // 客户端断开

        bool fin    = (header[0] & 0x80) != 0;
        int  opcode = header[0] & 0x0F;
        bool masked = (header[1] & 0x80) != 0;
        int  pay_len = header[1] & 0x7F;

        // 扩展载荷长度（16/64 位）
        uint64_t payload_len = pay_len;
        if (pay_len == 126)
        {
            unsigned char ext[2];
            if (::recv(client->sock, reinterpret_cast<char*>(ext), 2, MSG_WAITALL) != 2)
                break;
            payload_len = ((uint16_t)ext[0] << 8) | ext[1];
        }
        else if (pay_len == 127)
        {
            unsigned char ext[8];
            if (::recv(client->sock, reinterpret_cast<char*>(ext), 8, MSG_WAITALL) != 8)
                break;
            payload_len = 0;
            for (int i = 0; i < 8; i++)
                payload_len = (payload_len << 8) | ext[i];
        }

        // 读取掩码键（客户端发送的帧必须带掩码）
        unsigned char mask_key[4] = {};
        if (masked)
        {
            if (::recv(client->sock, reinterpret_cast<char*>(mask_key), 4, MSG_WAITALL) != 4)
                break;
        }

        // 限制最大载荷大小，防止恶意消息（1 MB）
        if (payload_len > 1024 * 1024)
            break;

        // 读取载荷
        std::vector<unsigned char> payload(static_cast<size_t>(payload_len));
        if (payload_len > 0)
        {
            size_t received = 0;
            while (received < payload_len)
            {
                int r = ::recv(client->sock,
                               reinterpret_cast<char*>(payload.data() + received),
                               static_cast<int>(payload_len - received), 0);
                if (r <= 0)
                    goto client_disconnect; // 使用 goto 跳出多层嵌套
                received += r;
            }
            // 解掩码
            if (masked)
            {
                for (size_t i = 0; i < payload.size(); i++)
                    payload[i] ^= mask_key[i % 4];
            }
        }

        // 处理控制帧
        switch (opcode)
        {
        case 0x8: // Close
            goto client_disconnect;

        case 0x9: // Ping → 回复 Pong
        {
            unsigned char pong_hdr[2] = { 0x8A, static_cast<unsigned char>(payload_len & 0x7F) };
            ::send(client->sock, reinterpret_cast<const char*>(pong_hdr), 2, 0);
            if (!payload.empty())
                ::send(client->sock, reinterpret_cast<const char*>(payload.data()),
                       static_cast<int>(payload.size()), 0);
            break;
        }

        default:
            // 忽略其他帧类型（文本/二进制数据，服务器不需要处理）
            break;
        }
    }

client_disconnect:
    // 发送 Close 帧
    if (client->sock != INVALID_SOCKET)
    {
        unsigned char close_frame[2] = { 0x88, 0x00 };
        ::send(client->sock, reinterpret_cast<const char*>(close_frame), 2, 0);
        closesocket(client->sock);
        client->sock = INVALID_SOCKET;
    }

    client->handshake_done = false;

    // 从客户端列表移除
    {
        std::lock_guard<std::mutex> lock(m_clients_mutex);
        auto it = std::find(m_clients.begin(), m_clients.end(), client);
        if (it != m_clients.end())
            m_clients.erase(it);
    }
    // 注意：不在这里 delete client，client 是由 AcceptLoop 分配的；
    // 若需要，可在 Stop() 中统一清理；这里线程对象本身由 detach 管理。
    // 实际上 thread.detach() 或由析构时 join，见 Stop()。
}

// ── WebSocket 握手 ────────────────────────────────────────────────────────────
// 对应 sysmon-ws 中的 accept_async()

bool CWebSocketServer::DoHandshake(SOCKET sock)
{
    // 设置接收超时（5 秒）
    DWORD timeout = 5000;
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO,
               reinterpret_cast<const char*>(&timeout), sizeof(timeout));

    // 读取 HTTP 请求（最多 4 KB）
    std::string request;
    request.reserve(1024);
    char buf[512];
    while (request.find("\r\n\r\n") == std::string::npos)
    {
        int n = ::recv(sock, buf, sizeof(buf) - 1, 0);
        if (n <= 0)
            return false;
        buf[n] = '\0';
        request += buf;
        if (request.size() > 4096)
            return false;
    }

    // 检查是否为 WebSocket 升级请求
    if (request.find("Upgrade: websocket") == std::string::npos &&
        request.find("upgrade: websocket") == std::string::npos)
    {
        return false;
    }

    // 提取 Sec-WebSocket-Key
    std::string sec_key = GetHeaderValue(request, "Sec-WebSocket-Key");
    if (sec_key.empty())
        return false;

    // 计算 Sec-WebSocket-Accept
    std::string accept_key = ComputeAcceptKey(sec_key);

    // 构建 101 响应
    std::string response =
        "HTTP/1.1 101 Switching Protocols\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        "Sec-WebSocket-Accept: " + accept_key + "\r\n"
        "\r\n";

    int sent = ::send(sock, response.c_str(), static_cast<int>(response.size()), 0);
    if (sent == SOCKET_ERROR)
        return false;

    // 握手完成后恢复超时为无限制（由 Broadcast 主动推送）
    DWORD no_timeout = 0;
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO,
               reinterpret_cast<const char*>(&no_timeout), sizeof(no_timeout));

    return true;
}

// ── 构造 WebSocket 文本帧 ─────────────────────────────────────────────────────
// RFC 6455 §5.2，服务器端帧 mask bit = 0

std::string CWebSocketServer::BuildTextFrame(const std::string& payload)
{
    std::string frame;
    size_t len = payload.size();

    frame += static_cast<char>(0x81); // FIN=1, opcode=1 (text)

    if (len < 126)
    {
        frame += static_cast<char>(len);
    }
    else if (len < 65536)
    {
        frame += static_cast<char>(126);
        frame += static_cast<char>((len >> 8) & 0xFF);
        frame += static_cast<char>(len & 0xFF);
    }
    else
    {
        frame += static_cast<char>(127);
        for (int i = 7; i >= 0; i--)
            frame += static_cast<char>((len >> (i * 8)) & 0xFF);
    }

    frame += payload;
    return frame;
}

// ── 计算 Sec-WebSocket-Accept ─────────────────────────────────────────────────

std::string CWebSocketServer::ComputeAcceptKey(const std::string& sec_key)
{
    // RFC 6455 §4.2.2：key + magic GUID
    static const char* MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    std::string combined = sec_key + MAGIC;

    unsigned char sha1_out[20];
    SHA1Impl(reinterpret_cast<const unsigned char*>(combined.c_str()),
             combined.size(), sha1_out);

    return Base64EncodeImpl(sha1_out, 20);
}

// ── SHA-1 ─────────────────────────────────────────────────────────────────────

void CWebSocketServer::SHA1(const unsigned char* data, size_t len, unsigned char out[20])
{
    SHA1Impl(data, len, out);
}

// ── Base64 ────────────────────────────────────────────────────────────────────

std::string CWebSocketServer::Base64Encode(const unsigned char* data, size_t len)
{
    return Base64EncodeImpl(data, len);
}

// ── 解析 HTTP 头部字段值 ──────────────────────────────────────────────────────

std::string CWebSocketServer::GetHeaderValue(const std::string& request, const std::string& field)
{
    // 不区分大小写搜索字段名
    std::string lower_req = request;
    std::string lower_field = field;
    std::transform(lower_req.begin(), lower_req.end(), lower_req.begin(), ::tolower);
    std::transform(lower_field.begin(), lower_field.end(), lower_field.begin(), ::tolower);
    lower_field += ": ";

    size_t pos = lower_req.find(lower_field);
    if (pos == std::string::npos)
        return "";

    pos += lower_field.size();
    size_t end = request.find("\r\n", pos);
    if (end == std::string::npos)
        return "";

    // 去除首尾空白
    std::string value = request.substr(pos, end - pos);
    while (!value.empty() && (value.front() == ' ' || value.front() == '\t'))
        value.erase(value.begin());
    while (!value.empty() && (value.back() == ' ' || value.back() == '\t'))
        value.pop_back();
    return value;
}
