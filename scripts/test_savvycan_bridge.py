"""Protocol smoke test for the loopback socketcand bridge."""
import socket
import time

from savvycan_bridge import publish_j2534_frames, start_socketcand_server

assert start_socketcand_server("127.0.0.1", 29536), "socketcand server failed to bind"
client = socket.create_connection(("127.0.0.1", 29536), timeout=3)
client.settimeout(3)
assert client.recv(128) == b"< hi >", "missing socketcand greeting"
client.sendall(b"< open can0 >")
assert client.recv(128) == b"< ok >", "open handshake failed"
client.sendall(b"< rawmode >")
assert client.recv(128) == b"< ok >", "rawmode handshake failed"
count = publish_j2534_frames([{
    "data": "00 00 07 E8 03 41 0C 1F 40",
    "rx_status": 0,
}])
assert count == 1, f"expected one forwarded frame, got {count}"
message = client.recv(256)
assert message.startswith(b"< frame 7e8 "), message
assert message.endswith(b"03 410c1f40 >") is False  # protocol emits contiguous payload hex
assert b"410c1f40" in message, message
client.close()
print("socketcand handshake and J2534 CAN frame forwarding passed; TX remains disabled.")
