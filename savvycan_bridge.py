"""Local socketcand-compatible bridge from the J2534 CAN RX stream to SavvyCAN.

The bridge is loopback-only. It forwards received CAN frames into SavvyCAN.
Raw frame transmission from SavvyCAN is intentionally rejected/ignored until
SM2 Pro transmit behavior is validated on real hardware.
"""
from __future__ import annotations

import socketserver
import threading
import time

_HOST = "127.0.0.1"
_PORT = 29536
_SERVER = None
_SERVER_THREAD = None
_LOCK = threading.RLock()


class _SocketcandServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, address, handler):
        super().__init__(address, handler)
        self.clients = set()
        self.clients_lock = threading.RLock()


class _SocketcandHandler(socketserver.BaseRequestHandler):
    def handle(self):
        server = self.server
        with server.clients_lock:
            server.clients.add(self)
        raw_mode = False
        buffer = b""
        try:
            self.request.sendall(b"< hi >")
            self.request.settimeout(1.0)
            while True:
                try:
                    chunk = self.request.recv(4096)
                except TimeoutError:
                    continue
                if not chunk:
                    break
                buffer += chunk
                while b">" in buffer:
                    command, buffer = buffer.split(b">", 1)
                    command = command.strip() + b">"
                    lower = command.lower()
                    if lower.startswith(b"< open "):
                        self.request.sendall(b"< ok >")
                    elif lower.startswith(b"< rawmode"):
                        raw_mode = True
                        self.raw_mode = True
                        self.request.sendall(b"< ok >")
                    elif lower.startswith(b"< close"):
                        self.request.sendall(b"< ok >")
                        return
                    elif lower.startswith(b"< send "):
                        # TX is deliberately disabled until SM2 Pro CAN TX is validated.
                        continue
        except (OSError, ConnectionError):
            pass
        finally:
            with server.clients_lock:
                server.clients.discard(self)

    def send_frame(self, payload: bytes) -> bool:
        if not getattr(self, "raw_mode", False):
            return False
        try:
            self.request.sendall(payload)
            return True
        except OSError:
            return False


def start_socketcand_server(host: str = _HOST, port: int = _PORT) -> bool:
    """Start the local server once. Returns False if another process owns the port."""
    global _SERVER, _SERVER_THREAD
    with _LOCK:
        if _SERVER is not None:
            return True
        try:
            server = _SocketcandServer((host, int(port)), _SocketcandHandler)
        except OSError:
            return False
        thread = threading.Thread(target=server.serve_forever,
                                  name="MOTO-DIAG-SavvyCAN-Bridge", daemon=True)
        thread.start()
        _SERVER, _SERVER_THREAD = server, thread
        return True


def publish_j2534_frames(frames) -> int:
    """Forward raw J2534 CAN frames (4-byte CAN ID + payload) to SavvyCAN clients."""
    with _LOCK:
        server = _SERVER
    if server is None:
        return 0

    sent = 0
    stamp = time.monotonic()
    with server.clients_lock:
        clients = tuple(server.clients)

    for frame in frames or ():
        try:
            raw = bytes.fromhex(str(frame.get("data", "")).replace(" ", ""))
            if len(raw) < 4:
                continue
            can_id = int.from_bytes(raw[:4], "big")
            rx_status = int(frame.get("rx_status", 0) or 0)
            # J2534 CAN_29BIT_ID receive flag. SavvyCAN infers extended format
            # from IDs above 0x7FF; keep the actual 29-bit identifier, not a flag bit.
            if rx_status & 0x100:
                can_id &= 0x1FFFFFFF
            payload = raw[4:12]
            line = f"< frame {can_id:x} {stamp:.6f} {payload.hex()} >".encode("ascii")
            for client in clients:
                if client.send_frame(line):
                    sent += 1
        except (TypeError, ValueError, OSError):
            continue
    return sent
