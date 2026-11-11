// Viecar VP002 WiFi -> ELM327 -> ECU stream tester
// C++17. Reads real adapter responses; does not simulate or clear DTCs.
// Windows: g++ -std=c++17 -O2 viecar_ecu_stream_test.cpp -lws2_32 -o viecar_test.exe
// Linux/Android NDK: clang++ -std=c++17 -O2 viecar_ecu_stream_test.cpp -o viecar_test

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <iostream>
#include <string>
#include <thread>
#include <vector>
#include <fstream>
#include <iomanip>

#ifdef _WIN32
  #define NOMINMAX
  #include <winsock2.h>
  #include <ws2tcpip.h>
  using socket_t = SOCKET;
  static constexpr socket_t BAD_SOCKET = INVALID_SOCKET;
  static void close_socket(socket_t s) { if (s != INVALID_SOCKET) closesocket(s); }
  static bool socket_init() { WSADATA d{}; return WSAStartup(MAKEWORD(2,2), &d) == 0; }
  static void socket_cleanup() { WSACleanup(); }
  static int last_socket_error() { return WSAGetLastError(); }
#else
  #include <sys/types.h>
  #include <sys/socket.h>
  #include <arpa/inet.h>
  #include <netinet/in.h>
  #include <unistd.h>
  #include <cerrno>
  using socket_t = int;
  static constexpr socket_t BAD_SOCKET = -1;
  static void close_socket(socket_t s) { if (s >= 0) ::close(s); }
  static bool socket_init() { return true; }
  static void socket_cleanup() {}
  static int last_socket_error() { return errno; }
#endif

static bool send_all(socket_t s, const std::string& data) {
    std::size_t sent = 0;
    while (sent < data.size()) {
#ifdef _WIN32
        int n = ::send(s, data.data() + sent, static_cast<int>(data.size() - sent), 0);
#else
        auto n = ::send(s, data.data() + sent, data.size() - sent, 0);
#endif
        if (n <= 0) return false;
        sent += static_cast<std::size_t>(n);
    }
    return true;
}

// Read until ELM327 prompt ('>') or timeout. Preserve raw bytes for diagnostics.
static std::string read_response(socket_t s, int timeout_ms = 2500) {
    std::string out;
    const auto deadline = std::chrono::steady_clock::now() +
                          std::chrono::milliseconds(timeout_ms);
    char buf[512];
    while (std::chrono::steady_clock::now() < deadline) {
#ifdef _WIN32
        fd_set fds; FD_ZERO(&fds); FD_SET(s, &fds);
        auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(
            deadline - std::chrono::steady_clock::now()).count();
        timeval tv{static_cast<long>(remaining / 1000),
                   static_cast<long>((remaining % 1000) * 1000)};
        int ready = select(0, &fds, nullptr, nullptr, &tv);
#else
        fd_set fds; FD_ZERO(&fds); FD_SET(s, &fds);
        auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(
            deadline - std::chrono::steady_clock::now()).count();
        timeval tv{static_cast<time_t>(remaining / 1000),
                   static_cast<suseconds_t>((remaining % 1000) * 1000)};
        int ready = select(s + 1, &fds, nullptr, nullptr, &tv);
#endif
        if (ready <= 0) break;
#ifdef _WIN32
        int n = recv(s, buf, sizeof(buf), 0);
#else
        auto n = recv(s, buf, sizeof(buf), 0);
#endif
        if (n <= 0) break;
        out.append(buf, static_cast<std::size_t>(n));
        if (out.find('>') != std::string::npos) break;
    }
    return out;
}

static std::string clean_for_log(std::string s) {
    for (char& c : s) if (c == '\r') c = '\n';
    return s;
}

static bool command(socket_t s, std::ofstream& log,
                    const std::string& cmd, int timeout_ms = 2500) {
    std::cout << "\n>> " << cmd << std::endl;
    log << "\n>> " << cmd << "\n";
    if (!send_all(s, cmd + "\r")) {
        std::cerr << "Gonderme hatasi. Socket error: " << last_socket_error() << "\n";
        return false;
    }
    std::string response = read_response(s, timeout_ms);
    if (response.empty()) {
        std::cout << "[YANIT YOK / zaman asimi]\n";
        log << "[NO RESPONSE / timeout]\n";
        return false;
    }
    std::cout << clean_for_log(response);
    if (response.back() != '\n' && response.back() != '\r') std::cout << "\n";
    log << clean_for_log(response) << "\n";
    return true;
}

int main(int argc, char** argv) {
    const std::string host = argc > 1 ? argv[1] : "192.168.0.10";
    const int port = argc > 2 ? std::stoi(argv[2]) : 35000;
    const std::string log_name = "viecar_ecu_stream.log";

    if (!socket_init()) {
        std::cerr << "Socket baslatilamadi.\n";
        return 1;
    }

    socket_t sock = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock == BAD_SOCKET) {
        std::cerr << "TCP socket olusturulamadi: " << last_socket_error() << "\n";
        socket_cleanup(); return 1;
    }

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(static_cast<unsigned short>(port));
    if (inet_pton(AF_INET, host.c_str(), &addr.sin_addr) != 1) {
        std::cerr << "Gecersiz IP adresi: " << host << "\n";
        close_socket(sock); socket_cleanup(); return 1;
    }

    std::cout << "Viecar VP002 baglanti testi: " << host << ":" << port << "\n";
    std::cout << "Telefon/PC Viecar WiFi agina bagli olmali.\n";
    if (::connect(sock, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
        std::cerr << "Baglanti basarisiz. Socket error: " << last_socket_error()
                  << "\nWiFi agini, IP ve portu kontrol edin.\n";
        close_socket(sock); socket_cleanup(); return 2;
    }

    std::ofstream log(log_name, std::ios::out | std::ios::app);
    if (!log) std::cerr << "UYARI: log dosyasi acilamadi.\n";
    std::cout << "TCP baglandi. Ham yanitlar " << log_name << " dosyasina yaziliyor.\n";

    // Read any adapter greeting before sending commands.
    auto greeting = read_response(sock, 1200);
    if (!greeting.empty()) {
        std::cout << "ADAPTER: " << clean_for_log(greeting) << "\n";
        log << "ADAPTER: " << clean_for_log(greeting) << "\n";
    }

    const std::vector<std::string> init = {
        "ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "0100"
    };
    for (const auto& c : init) {
        if (!command(sock, log, c, c == "ATZ" ? 4000 : 2500)) {
            std::cout << "Baslangic komutunda yanit eksik; test devam ediyor.\n";
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(250));
    }

    std::cout << "\nCanli veri testi basliyor. Cikmak icin Ctrl+C.\n";
    std::cout << "Bu test sadece okuma yapar; DTC silmez ve aktuatore komut gondermez.\n";
    const std::vector<std::string> pids = {"010C", "0111", "0105", "010B", "0142"};
    std::size_t index = 0;
    while (true) {
        command(sock, log, pids[index], 1800);
        index = (index + 1) % pids.size();
        std::this_thread::sleep_for(std::chrono::milliseconds(350));
    }

    close_socket(sock);
    socket_cleanup();
    return 0;
}
