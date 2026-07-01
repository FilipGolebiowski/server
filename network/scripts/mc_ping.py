#!/usr/bin/env python3
"""El Cartel - smoke test proxy (Minecraft Server List Ping).

Uzycie:  python mc_ping.py [host] [port]      (domyslnie 127.0.0.1 25565)

Pinguje serwer/proxy protokolem Minecraft i wypisuje MOTD, wersje oraz
liczbe graczy. Nie wymaga klienta Minecraft - sluzy do szybkiego
potwierdzenia, ze Velocity dziala i odpowiada (powinno pokazac MOTD
"El Cartel - Network" oraz 0/1000)."""
import socket
import struct
import json
import sys


def write_varint(value):
    out = b""
    while True:
        b = value & 0x7F
        value >>= 7
        out += struct.pack("B", b | (0x80 if value else 0))
        if not value:
            break
    return out


def read_varint(sock):
    num = 0
    shift = 0
    while True:
        d = sock.recv(1)
        if not d:
            raise IOError("polaczenie zamkniete przez serwer")
        b = d[0]
        num |= (b & 0x7F) << shift
        shift += 7
        if not b & 0x80:
            break
    return num


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 25565

    try:
        s = socket.create_connection((host, port), timeout=5)
    except Exception as e:
        print(f"[FAIL] nie moge polaczyc z {host}:{port} -> {e}")
        print("       Czy proxy Velocity dziala? Start: velocity/start.bat")
        sys.exit(2)

    try:
        host_b = host.encode("utf-8")
        # Handshake: id 0x00, protocol 767 (1.21.x), addr, port, next-state=1 (status)
        pkt = (b"\x00" + write_varint(767) + write_varint(len(host_b)) + host_b
               + struct.pack(">H", port) + b"\x01")
        s.sendall(write_varint(len(pkt)) + pkt)
        # Status request: id 0x00, pusty
        s.sendall(write_varint(1) + b"\x00")
        # Odpowiedz: varint len, varint packet-id (0), varint json-len, json
        read_varint(s)            # dlugosc pakietu (pomijamy)
        read_varint(s)            # packet id (0)
        slen = read_varint(s)     # dlugosc JSON-a
        data = b""
        while len(data) < slen:
            chunk = s.recv(slen - len(data))
            if not chunk:
                break
            data += chunk
        st = json.loads(data.decode("utf-8"))
    except Exception as e:
        print(f"[FAIL] blad protokolu ping: {e}")
        sys.exit(3)
    finally:
        s.close()

    ver = st.get("version", {}).get("name", "?")
    pl = st.get("players", {})
    desc = st.get("description", "")
    if isinstance(desc, dict):
        desc = desc.get("text", "") + "".join(
            e.get("text", "") for e in desc.get("extra", []))

    print("[OK] proxy odpowiada")
    print(f"  MOTD:    {desc!r}")
    print(f"  Wersja:  {ver}")
    print(f"  Gracze:  {pl.get('online', '?')}/{pl.get('max', '?')}")


if __name__ == "__main__":
    main()
