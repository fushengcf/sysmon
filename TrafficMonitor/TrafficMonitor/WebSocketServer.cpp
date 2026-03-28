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
#include <chrono>

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
    m_client_count.store(0);
    m_accept_thread = std::thread(&CWebSocketServer::AcceptLoop, this);
    m_service_thread = std::thread(&CWebSocketServer::ServiceLoop, this);
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
    if (m_service_thread.joinable())
        m_service_thread.join();

    // 关闭所有客户端
    std::vector<std::shared_ptr<WsClient>> clients;
    {
        std::lock_guard<std::mutex> lock(m_clients_mutex);
        clients.swap(m_clients);
    }
    m_client_count.store(0);
    for (const auto& client : clients)
    {
        if (client->sock != INVALID_SOCKET)
        {
            ::shutdown(client->sock, SD_BOTH);
            closesocket(client->sock);
            client->sock = INVALID_SOCKET;
        }
    }
}

// ── 广播消息给所有已握手客户端 ────────────────────────────────────────────────
// 对应 sysmon-ws 中的 broadcast::Sender::send()

bool CWebSocketServer::Broadcast(const std::string& message)
{
    if (message.empty() || !m_running.load())
        return false;

    std::string frame = BuildTextFrame(message);
    std::vector<std::shared_ptr<WsClient>> clients;
    {
        std::lock_guard<std::mutex> lock(m_clients_mutex);
        clients = m_clients;
    }
    if (clients.empty())
        return false;

    bool sent_to_any_client = false;
    for (const auto& client : clients)
    {
        if (client == nullptr || client->sock == INVALID_SOCKET)
            continue;

        if (!PumpClientControlFrames(client) ||
            !SendAll(client->sock, frame.c_str(), static_cast<int>(frame.size())))
        {
            CloseClient(client->sock);
            continue;
        }
        sent_to_any_client = true;
    }

    return sent_to_any_client;
}

// ── 当前连接数 ────────────────────────────────────────────────────────────────

int CWebSocketServer::GetClientCount() const
{
    return m_client_count.load();
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

        if (!DoHandshake(client_sock))
        {
            closesocket(client_sock);
            continue;
        }

        u_long non_blocking = 1;
        ioctlsocket(client_sock, FIONBIO, &non_blocking);

        auto client = std::make_shared<WsClient>();
        client->sock = client_sock;
        {
            std::lock_guard<std::mutex> lock(m_clients_mutex);
            m_clients.push_back(client);
        }
        m_client_count.fetch_add(1);
    }
}

void CWebSocketServer::ServiceLoop()
{
    while (m_running.load())
    {
        std::vector<std::shared_ptr<WsClient>> clients;
        {
            std::lock_guard<std::mutex> lock(m_clients_mutex);
            clients = m_clients;
        }
        if (clients.empty())
        {
            std::this_thread::sleep_for(std::chrono::milliseconds(200));
            continue;
        }

        fd_set read_set;
        FD_ZERO(&read_set);
        for (const auto& client : clients)
        {
            if (client != nullptr && client->sock != INVALID_SOCKET)
                FD_SET(client->sock, &read_set);
        }

        timeval timeout{};
        timeout.tv_sec = 0;
        timeout.tv_usec = 200000;
        int ready = ::select(0, &read_set, nullptr, nullptr, &timeout);
        if (ready <= 0)
            continue;

        for (const auto& client : clients)
        {
            if (client == nullptr || client->sock == INVALID_SOCKET || !FD_ISSET(client->sock, &read_set))
                continue;
            if (!PumpClientControlFrames(client))
                CloseClient(client->sock);
        }
    }
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
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO,
               reinterpret_cast<const char*>(&no_timeout), sizeof(no_timeout));

    return true;
}

bool CWebSocketServer::PumpClientControlFrames(const std::shared_ptr<WsClient>& client)
{
    if (client == nullptr || client->sock == INVALID_SOCKET)
        return false;

    while (true)
    {
        unsigned char header[2];
        int header_bytes = ::recv(client->sock, reinterpret_cast<char*>(header), 2, MSG_PEEK);
        if (header_bytes == 0)
            return false;
        if (header_bytes == SOCKET_ERROR)
        {
            int error = WSAGetLastError();
            return (error == WSAEWOULDBLOCK);
        }
        if (header_bytes < 2)
            return true;

        const int opcode = header[0] & 0x0F;
        const bool masked = (header[1] & 0x80) != 0;
        size_t payload_len = header[1] & 0x7F;
        size_t header_len = 2;
        if (payload_len == 126 || payload_len == 127)
            return false;
        if (masked)
            header_len += 4;
        const size_t frame_len = header_len + payload_len;

        u_long available = 0;
        if (ioctlsocket(client->sock, FIONREAD, &available) == SOCKET_ERROR)
            return false;
        if (available < frame_len)
            return true;

        std::vector<unsigned char> frame(frame_len);
        int received = ::recv(client->sock, reinterpret_cast<char*>(frame.data()), static_cast<int>(frame.size()), 0);
        if (received != static_cast<int>(frame.size()))
            return false;

        unsigned char* payload = frame.data() + 2;
        if (masked)
        {
            unsigned char* mask_key = payload;
            payload += 4;
            for (size_t i = 0; i < payload_len; ++i)
                payload[i] ^= mask_key[i % 4];
        }

        if (opcode == 0x8)
            return false;

        if (opcode == 0x9)
        {
            std::string pong_frame;
            pong_frame.reserve(frame_len);
            pong_frame.push_back(static_cast<char>(0x8A));
            pong_frame.push_back(static_cast<char>(payload_len));
            if (payload_len > 0)
                pong_frame.append(reinterpret_cast<const char*>(payload), payload_len);
            if (!SendAll(client->sock, pong_frame.c_str(), static_cast<int>(pong_frame.size())))
                return false;
        }
    }
}

bool CWebSocketServer::SendAll(SOCKET sock, const char* data, int len) const
{
    int sent_total = 0;
    while (sent_total < len)
    {
        int sent = ::send(sock, data + sent_total, len - sent_total, 0);
        if (sent > 0)
        {
            sent_total += sent;
            continue;
        }

        if (sent == 0)
            return false;

        const int error = WSAGetLastError();
        if (error != WSAEWOULDBLOCK)
            return false;

        fd_set write_set;
        FD_ZERO(&write_set);
        FD_SET(sock, &write_set);
        timeval timeout{};
        timeout.tv_sec = 0;
        timeout.tv_usec = 50000;
        int ready = ::select(0, nullptr, &write_set, nullptr, &timeout);
        if (ready <= 0)
            return false;
    }
    return true;
}

void CWebSocketServer::CloseClient(SOCKET sock)
{
    std::shared_ptr<WsClient> removed_client;
    {
        std::lock_guard<std::mutex> lock(m_clients_mutex);
        auto it = std::find_if(m_clients.begin(), m_clients.end(), [sock](const std::shared_ptr<WsClient>& client) {
            return client != nullptr && client->sock == sock;
        });
        if (it == m_clients.end())
            return;
        removed_client = *it;
        m_clients.erase(it);
    }

    m_client_count.fetch_sub(1);
    if (removed_client != nullptr && removed_client->sock != INVALID_SOCKET)
    {
        ::shutdown(removed_client->sock, SD_BOTH);
        closesocket(removed_client->sock);
        removed_client->sock = INVALID_SOCKET;
    }
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
