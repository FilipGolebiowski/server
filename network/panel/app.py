"""El Cartel — panel administracyjny sieci (FastAPI)."""
from __future__ import annotations

import json
import os
import re
import shutil
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Query
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from process_control import (
    server_control_status,
    start_network,
    start_server,
    stop_network,
    stop_server,
)
from dev_tools import (
    build_shard_detail,
    cleanup_stale_shards,
    dev_preflight,
    mongo_extended,
    redis_overview,
    run_script,
    safe_server_id,
    scan_redis_keys,
    sessions_on_shard,
    unregister_shard_redis,
)

NETWORK_DIR = Path(__file__).resolve().parent.parent
PANEL_DIR = Path(__file__).resolve().parent
STALE_MS = 5000

app = FastAPI(title="El Cartel Panel", version="1.0.0")


# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

def load_properties() -> dict[str, str]:
    props: dict[str, str] = {}
    candidates = [
        os.environ.get("ELCARTEL_CONFIG"),
        NETWORK_DIR / "config" / "elcartel.properties",
    ]
    for c in candidates:
        if not c:
            continue
        p = Path(c)
        if p.is_file():
            for line in p.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                props[k.strip()] = v.strip()
            break
    for k in ("ELCARTEL_MONGO_URI", "ELCARTEL_REDIS_URI", "ELCARTEL_MONGO_DB", "ELCARTEL_PANEL_TOKEN"):
        if k not in props and os.environ.get(k):
            props[k] = os.environ[k]
    return props


PROPS = load_properties()


def panel_token() -> str | None:
    return PROPS.get("ELCARTEL_PANEL_TOKEN") or os.environ.get("ELCARTEL_PANEL_TOKEN")


def require_token(authorization: str | None = Header(default=None)) -> None:
    token = panel_token()
    if not token:
        return
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "Wymagany token (Authorization: Bearer …)")
    if authorization[7:] != token:
        raise HTTPException(403, "Nieprawidłowy token")


# ---------------------------------------------------------------------------
# Redis / Mongo helpers
# ---------------------------------------------------------------------------

_redis = None
_mongo = None


def get_redis():
    global _redis
    if _redis is not None:
        return _redis
    uri = PROPS.get("ELCARTEL_REDIS_URI")
    if not uri:
        return None
    try:
        import redis
        _redis = redis.from_url(uri, decode_responses=True, socket_connect_timeout=2)
        _redis.ping()
        return _redis
    except Exception:
        return None


def get_mongo():
    global _mongo
    if _mongo is not None:
        return _mongo
    uri = PROPS.get("ELCARTEL_MONGO_URI")
    if not uri:
        return None
    try:
        from pymongo import MongoClient
        _mongo = MongoClient(uri, serverSelectionTimeoutMS=2000)
        _mongo.admin.command("ping")
        return _mongo
    except Exception:
        return None


def db_name() -> str:
    return PROPS.get("ELCARTEL_MONGO_DB", "elcartel")


# ---------------------------------------------------------------------------
# Shard registry (Redis — jak ShardRegistry.java)
# ---------------------------------------------------------------------------

def redis_shards() -> list[dict[str, Any]]:
    r = get_redis()
    if not r:
        return []
    now = int(time.time() * 1000)
    out: list[dict[str, Any]] = []
    for sid in r.smembers("shards:index") or []:
        h = r.hgetall(f"shard:{sid}") or {}
        hb = int(h.get("heartbeat") or 0)
        age_ms = now - hb if hb else None
        fresh = age_ms is not None and age_ms <= STALE_MS
        out.append({
            "id": sid,
            "mode": h.get("mode", ""),
            "addr": h.get("addr", ""),
            "state": h.get("state", "UNKNOWN"),
            "players": int(h.get("players") or 0),
            "softCap": int(h.get("softCap") or 0),
            "hardCap": int(h.get("hardCap") or 0),
            "tps": float(h.get("tps") or 0),
            "mspt": float(h.get("mspt") or 0),
            "heartbeat": hb,
            "heartbeatAgeMs": age_ms,
            "fresh": fresh,
            "joinable": fresh and h.get("state") == "OPEN" and int(h.get("players") or 0) < int(h.get("softCap") or 0),
            "sectorX": _int_or_none(h.get("sx")),
            "sectorZ": _int_or_none(h.get("sz")),
        })
    out.sort(key=lambda s: (s["mode"], s["id"]))
    return out


def _int_or_none(v: Any) -> int | None:
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


def survival_sector_size() -> int:
    servers_dir = NETWORK_DIR / "servers"
    if servers_dir.is_dir():
        for d in servers_dir.iterdir():
            if not d.is_dir():
                continue
            start = d / "start.sh" if (d / "start.sh").is_file() else d / "start.bat"
            env = read_env_from_start(start)
            size = _int_or_none(env.get("SECTOR_SIZE"))
            if size:
                return size
    return 1000


def sectors_view() -> dict[str, Any]:
    """Mapa sektorow survivala: grupuje shardy po (sx,sz) -> instancje + obciazenie."""
    shards = [s for s in redis_shards()
              if s["mode"] == "survival" and s.get("sectorX") is not None and s.get("sectorZ") is not None]
    groups: dict[tuple[int, int], dict[str, Any]] = {}
    for s in shards:
        key = (s["sectorX"], s["sectorZ"])
        g = groups.get(key)
        if g is None:
            g = {"sx": s["sectorX"], "sz": s["sectorZ"], "instances": [], "players": 0, "softCap": 0, "live": 0}
            groups[key] = g
        g["instances"].append({
            "id": s["id"], "players": s["players"], "softCap": s["softCap"],
            "state": s["state"], "fresh": s["fresh"],
        })
        g["players"] += s["players"]
        g["softCap"] += s["softCap"]
        if s["fresh"]:
            g["live"] += 1
    sectors: list[dict[str, Any]] = []
    for g in groups.values():
        g["instanceCount"] = len(g["instances"])
        g["instances"].sort(key=lambda i: i["id"])
        sectors.append(g)
    sectors.sort(key=lambda s: (s["sz"], s["sx"]))
    if sectors:
        xs = [s["sx"] for s in sectors]
        zs = [s["sz"] for s in sectors]
        bounds = {"minX": min(xs), "maxX": max(xs), "minZ": min(zs), "maxZ": max(zs)}
    else:
        bounds = {"minX": 0, "maxX": 0, "minZ": 0, "maxZ": 0}
    return {
        "sectors": sectors,
        "bounds": bounds,
        "size": survival_sector_size(),
        "totalPlayers": sum(s["players"] for s in sectors),
        "totalInstances": sum(s["instanceCount"] for s in sectors),
    }


def redis_sessions(limit: int = 100) -> list[dict[str, Any]]:
    r = get_redis()
    if not r:
        return []
    out: list[dict[str, Any]] = []
    for key in r.scan_iter("session:*", count=200):
        if len(out) >= limit:
            break
        uid = key.split(":", 1)[1]
        h = r.hgetall(key) or {}
        out.append({
            "uuid": uid,
            "proxy": h.get("proxy", ""),
            "shard": h.get("shard", ""),
            "ts": int(h.get("ts") or 0),
        })
    out.sort(key=lambda s: s["ts"], reverse=True)
    return out


# ---------------------------------------------------------------------------
# Local servers (filesystem — jak new-shard.sh)
# ---------------------------------------------------------------------------

def parse_server_properties(path: Path) -> dict[str, str]:
    props: dict[str, str] = {}
    if not path.is_file():
        return props
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        props[k.strip()] = v.strip()
    return props


def parse_mode_conf(mode: str) -> dict[str, str]:
    conf: dict[str, str] = {
        "port-base": "25600",
        "soft-cap": "180",
        "hard-cap": "200",
        "display-name": mode,
    }
    p = NETWORK_DIR / "templates" / mode / "mode.conf"
    if not p.is_file():
        return conf
    for line in p.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        conf[k.strip()] = v.strip()
    return conf


def port_open(host: str, port: int, timeout: float = 0.4) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def dir_size_mb(path: Path) -> float:
    if not path.is_dir():
        return 0.0
    total = 0
    try:
        for f in path.rglob("*"):
            if f.is_file():
                total += f.stat().st_size
    except OSError:
        pass
    return round(total / (1024 * 1024), 1)


def read_env_from_start(path: Path) -> dict[str, str]:
    env: dict[str, str] = {}
    if not path.is_file():
        return env
    text = path.read_text(encoding="utf-8", errors="replace")
    for m in re.finditer(r'(?:export\s+)?ELCARTEL_(\w+)=([^\s"\']+|"(?:[^"\\]|\\.)*")', text):
        val = m.group(2).strip('"')
        env[m.group(1)] = val
    for m in re.finditer(r'set\s+"ELCARTEL_(\w+)=([^"]+)"', text, re.I):
        env[m.group(1)] = m.group(2)
    return env


def local_servers() -> list[dict[str, Any]]:
    servers_dir = NETWORK_DIR / "servers"
    if not servers_dir.is_dir():
        return []
    out: list[dict[str, Any]] = []
    for d in sorted(servers_dir.iterdir()):
        if not d.is_dir():
            continue
        sid = d.name
        props = parse_server_properties(d / "server.properties")
        port = int(props.get("server-port") or 0)
        host = props.get("server-ip") or "127.0.0.1"
        if host in ("", "0.0.0.0"):
            host = "127.0.0.1"
        start_sh = d / "start.sh"
        start_bat = d / "start.bat"
        env = read_env_from_start(start_sh if start_sh.is_file() else start_bat)
        role = "limbo" if sid == "limbo" else ("shard" if env.get("SHARD_ID") or re.match(r"^.+-\d+$", sid) else "backend")
        mode = env.get("MODE", "")
        if sid == "limbo":
            role = "limbo"
        listening = port_open(host, port) if port else False
        world = d / (props.get("level-name") or "world")
        out.append({
            "id": sid,
            "role": role,
            "mode": mode,
            "port": port,
            "host": host,
            "listening": listening,
            "motd": props.get("motd", ""),
            "maxPlayers": int(props.get("max-players") or 0),
            "softCap": int(env.get("SOFTCAP") or 0),
            "hardCap": int(env.get("HARDCAP") or 0),
            "addr": env.get("ADDR", f"{host}:{port}"),
            "hasStartScript": start_sh.is_file() or start_bat.is_file(),
            "diskMb": dir_size_mb(d),
            "worldMb": dir_size_mb(world),
            "managed": server_control_status(sid).get("managed", False),
        })
    return out


def list_modes() -> list[dict[str, Any]]:
    tpl = NETWORK_DIR / "templates"
    modes: list[dict[str, Any]] = []
    if not tpl.is_dir():
        return modes
    for d in sorted(tpl.iterdir()):
        if not d.is_dir() or d.name.startswith("_"):
            continue
        conf = parse_mode_conf(d.name)
        shard_dirs = [s.name for s in (NETWORK_DIR / "servers").iterdir()
                      if s.is_dir() and re.match(rf"^{re.escape(d.name)}-\d+$", s.name)] if (NETWORK_DIR / "servers").is_dir() else []
        live = [s for s in redis_shards() if s["mode"] == d.name and s["fresh"]]
        modes.append({
            "id": d.name,
            "displayName": conf.get("display-name", d.name),
            "portBase": int(conf.get("port-base", 25600)),
            "softCap": int(conf.get("soft-cap", 180)),
            "hardCap": int(conf.get("hard-cap", 200)),
            "localShards": len(shard_dirs),
            "liveShards": len(live),
            "totalPlayers": sum(s["players"] for s in live),
        })
    return modes


# ---------------------------------------------------------------------------
# Proxy ping (mc_ping.py)
# ---------------------------------------------------------------------------

def write_varint(value: int) -> bytes:
    out = b""
    while True:
        b = value & 0x7F
        value >>= 7
        out += struct.pack("B", b | (0x80 if value else 0))
        if not value:
            break
    return out


def read_varint(sock: socket.socket) -> int:
    num = 0
    shift = 0
    while True:
        d = sock.recv(1)
        if not d:
            raise OSError("connection closed")
        b = d[0]
        num |= (b & 0x7F) << shift
        shift += 7
        if not (b & 0x80):
            break
    return num


def ping_proxy(host: str = "127.0.0.1", port: int = 25565) -> dict[str, Any]:
    try:
        s = socket.create_connection((host, port), timeout=3)
    except OSError as e:
        return {"online": False, "error": str(e), "host": host, "port": port}
    try:
        host_b = host.encode("utf-8")
        pkt = (b"\x00" + write_varint(767) + write_varint(len(host_b)) + host_b
               + struct.pack(">H", port) + b"\x01")
        s.sendall(write_varint(len(pkt)) + pkt)
        s.sendall(write_varint(1) + b"\x00")
        read_varint(s)
        read_varint(s)
        slen = read_varint(s)
        data = b""
        while len(data) < slen:
            chunk = s.recv(slen - len(data))
            if not chunk:
                break
            data += chunk
        st = json.loads(data.decode("utf-8"))
        desc = st.get("description", "")
        if isinstance(desc, dict):
            desc = desc.get("text", "") + "".join(e.get("text", "") for e in desc.get("extra", []))
        pl = st.get("players", {})
        return {
            "online": True,
            "host": host,
            "port": port,
            "motd": desc,
            "version": st.get("version", {}).get("name", "?"),
            "playersOnline": pl.get("online", 0),
            "playersMax": pl.get("max", 0),
        }
    except Exception as e:
        return {"online": False, "error": str(e), "host": host, "port": port}
    finally:
        s.close()


def mongo_stats() -> dict[str, Any]:
    client = get_mongo()
    if not client:
        return {"connected": False}
    db = client[db_name()]
    try:
        return {
            "connected": True,
            "database": db_name(),
            "players": db["players"].estimated_document_count(),
            "auth": db["auth"].estimated_document_count(),
        }
    except Exception as e:
        return {"connected": False, "error": str(e)}


def tail_log(server_id: str, lines: int = 80) -> dict[str, Any]:
    safe = re.sub(r"[^\w\-]", "", server_id)
    if safe != server_id:
        raise HTTPException(400, "Nieprawidłowy identyfikator serwera")
    log_path = NETWORK_DIR / "servers" / safe / "logs" / "latest.log"
    if safe == "velocity":
        log_path = NETWORK_DIR / "velocity" / "logs" / "latest.log"
    if not log_path.is_file():
        return {"serverId": server_id, "lines": [], "path": str(log_path)}
    try:
        content = log_path.read_text(encoding="utf-8", errors="replace").splitlines()
        return {"serverId": server_id, "lines": content[-lines:], "path": str(log_path)}
    except OSError as e:
        raise HTTPException(500, str(e)) from e


def merge_shards_view() -> list[dict[str, Any]]:
    """Redis + lokalne katalogi (jak w ADMIN.md / ShardWatcher)."""
    redis_by_id = {s["id"]: s for s in redis_shards()}
    local_by_id = {s["id"]: s for s in local_servers() if s["role"] == "shard"}
    all_ids = sorted(set(redis_by_id) | set(local_by_id))
    merged: list[dict[str, Any]] = []
    for sid in all_ids:
        r = redis_by_id.get(sid, {})
        l = local_by_id.get(sid, {})
        proc = l.get("listening", False)
        fresh = r.get("fresh", False)
        if fresh and proc:
            status = "online"
        elif proc and not fresh:
            status = "starting"
        elif fresh and not proc:
            status = "redis_only"
        elif l and not proc:
            status = "offline"
        else:
            status = "stale"
        merged.append({
            **r,
            "id": sid,
            "mode": r.get("mode") or l.get("mode", ""),
            "host": l.get("host"),
            "port": l.get("port"),
            "addr": r.get("addr") or l.get("addr", ""),
            "listening": proc,
            "status": status,
            "diskMb": l.get("diskMb", 0),
            "worldMb": l.get("worldMb", 0),
            "localOnly": sid in local_by_id and sid not in redis_by_id,
            "redisOnly": sid in redis_by_id and sid not in local_by_id,
            "inRedis": sid in redis_by_id,
            "hasLocalDir": sid in local_by_id,
            "canStart": not proc and sid in local_by_id,
            "canStop": proc,
        })
    return merged


# ---------------------------------------------------------------------------
# API routes
# ---------------------------------------------------------------------------

class CreateShardsBody(BaseModel):
    mode: str = Field(min_length=1, max_length=32)
    count: int = Field(default=1, ge=1, le=20)
    xmx: str = "2G"


class StartServerBody(BaseModel):
    window: bool = False  # Windows: osobne okno konsoli zamiast tla


class StopServerBody(BaseModel):
    force: bool = False


class StartNetworkBody(BaseModel):
    window: bool = False


class StopNetworkBody(BaseModel):
    force: bool = False


@app.get("/api/overview")
def api_overview():
    shards = merge_shards_view()
    servers = local_servers()
    proxy = ping_proxy()
    live = [s for s in shards if s.get("status") == "online"]
    return {
        "timestamp": int(time.time() * 1000),
        "redis": {"connected": get_redis() is not None},
        "mongo": mongo_stats(),
        "proxy": proxy,
        "shards": {
            "total": len(shards),
            "online": len(live),
            "players": sum(s.get("players", 0) for s in live),
        },
        "servers": {
            "total": len(servers),
            "listening": sum(1 for s in servers if s["listening"]),
        },
        "modes": list_modes(),
    }


@app.get("/api/shards")
def api_shards():
    return {"shards": merge_shards_view(), "modes": list_modes()}


@app.get("/api/sectors")
def api_sectors():
    """Mapa sektorow survivala (aktywne sektory, pozycje, liczba instancji)."""
    return sectors_view()


@app.get("/api/servers")
def api_servers():
    limbo = next((s for s in local_servers() if s["id"] == "limbo"), None)
    shards = [s for s in local_servers() if s["role"] == "shard"]
    other = [s for s in local_servers() if s["role"] not in ("limbo", "shard")]
    velocity_port = 25565
    velocity = {
        "id": "velocity",
        "role": "proxy",
        "port": velocity_port,
        "listening": port_open("127.0.0.1", velocity_port),
        "diskMb": dir_size_mb(NETWORK_DIR / "velocity"),
        "managed": server_control_status("velocity").get("managed", False),
    }
    return {"limbo": limbo, "velocity": velocity, "shards": shards, "other": other}


@app.get("/api/sessions")
def api_sessions(limit: int = Query(default=100, ge=1, le=500)):
    return {"sessions": redis_sessions(limit)}


@app.get("/api/logs/{server_id}")
def api_logs(server_id: str, lines: int = Query(default=60, ge=10, le=500)):
    return tail_log(server_id, lines)


@app.post("/api/servers/{server_id}/start", dependencies=[Depends(require_token)])
def api_start_server(server_id: str, body: StartServerBody = StartServerBody()):
    try:
        return start_server(server_id, window=body.window)
    except FileNotFoundError as e:
        raise HTTPException(404, str(e)) from e
    except (ValueError, RuntimeError) as e:
        raise HTTPException(400, str(e)) from e


@app.post("/api/servers/{server_id}/stop", dependencies=[Depends(require_token)])
def api_stop_server(server_id: str, body: StopServerBody = StopServerBody()):
    try:
        result = stop_server(server_id, force=body.force)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e
    if not result.get("ok"):
        raise HTTPException(409, result.get("error", "Stop nieudany"))
    return result


@app.post("/api/network/start", dependencies=[Depends(require_token)])
def api_start_network(body: StartNetworkBody = StartNetworkBody()):
    return start_network(window=body.window)


@app.post("/api/network/stop", dependencies=[Depends(require_token)])
def api_stop_network(body: StopNetworkBody = StopNetworkBody()):
    return stop_network(force=body.force)


@app.get("/api/servers/{server_id}/status")
def api_server_status(server_id: str):
    try:
        return server_control_status(server_id)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e


@app.post("/api/shards/create", dependencies=[Depends(require_token)])
def api_create_shards(body: CreateShardsBody):
    mode = re.sub(r"[^\w\-]", "", body.mode)
    if not mode:
        raise HTTPException(400, "Nieprawidłowy tryb")
    tpl = NETWORK_DIR / "templates" / mode
    if not tpl.is_dir():
        raise HTTPException(404, f"Brak szablonu templates/{mode}/")
    if sys.platform == "win32":
        script = NETWORK_DIR / "scripts" / "new-shard.ps1"
        cmd = ["powershell", "-ExecutionPolicy", "Bypass", "-File", str(script),
               "-Mode", mode, "-Count", str(body.count), "-Xmx", body.xmx]
    else:
        script = NETWORK_DIR / "scripts" / "new-shard.sh"
        cmd = ["bash", str(script), mode, str(body.count), body.xmx]
    if not script.is_file():
        raise HTTPException(500, "Brak skryptu new-shard")
    try:
        proc = subprocess.run(cmd, cwd=str(NETWORK_DIR), capture_output=True, text=True, timeout=120)
    except subprocess.TimeoutExpired:
        raise HTTPException(504, "Timeout tworzenia shardów")
    if proc.returncode != 0:
        raise HTTPException(500, proc.stderr or proc.stdout or "Błąd new-shard")
    return {"ok": True, "output": proc.stdout.strip(), "shards": merge_shards_view()}


@app.delete("/api/shards/{shard_id}", dependencies=[Depends(require_token)])
def api_delete_shard(shard_id: str, backup: bool = Query(default=False), redis: bool = Query(default=True)):
    safe = safe_server_id(shard_id)
    if safe in ("limbo", "velocity"):
        raise HTTPException(400, "Nie można usunąć tego serwera")
    if not re.match(r"^.+-\d+$", safe):
        raise HTTPException(400, "Usuwanie dotyczy tylko shardów (np. survival-2)")
    target = NETWORK_DIR / "servers" / safe
    if not target.is_dir():
        raise HTTPException(404, "Katalog sharda nie istnieje")
    r = get_redis()
    if r:
        h = r.hgetall(f"shard:{safe}") or {}
        if h and int(h.get("players") or 0) > 0:
            raise HTTPException(409, "Shard ma aktywnych graczy — najpierw stop")
    if backup:
        import tarfile
        from datetime import date
        backups = NETWORK_DIR / "backups"
        backups.mkdir(exist_ok=True)
        arc = backups / f"{safe}-world-{date.today().isoformat()}.tgz"
        world = target / "world"
        if world.is_dir():
            with tarfile.open(arc, "w:gz") as tar:
                tar.add(world, arcname="world")
    shutil.rmtree(target)
    redis_note = ""
    if redis and r:
        unregister_shard_redis(r, safe)
        redis_note = " Wpis Redis usunięty."
    return {"ok": True, "removed": safe, "note": "Proces JVM nadal może działać — zatrzymaj go (Stop)." + redis_note}


@app.get("/api/shards/{shard_id}")
def api_shard_detail(shard_id: str):
    try:
        return build_shard_detail(
            shard_id,
            get_redis_fn=get_redis,
            merge_fn=merge_shards_view,
            local_servers_fn=local_servers,
            parse_mode_conf_fn=parse_mode_conf,
            read_env_fn=read_env_from_start,
            parse_props_fn=parse_server_properties,
            dir_size_fn=dir_size_mb,
            port_open_fn=port_open,
            tail_log_fn=tail_log,
            network_dir=NETWORK_DIR,
            stale_ms=STALE_MS,
        )
    except ValueError as e:
        raise HTTPException(400, str(e)) from e


@app.delete("/api/shards/{shard_id}/redis", dependencies=[Depends(require_token)])
def api_shard_redis_delete(shard_id: str):
    try:
        sid = safe_server_id(shard_id)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e
    r = get_redis()
    if not r:
        raise HTTPException(503, "Redis niedostępny")
    h = r.hgetall(f"shard:{sid}") or {}
    if h and int(h.get("players") or 0) > 0:
        raise HTTPException(409, "Shard ma graczy w Redis — najpierw stop serwera")
    if sid not in (r.smembers("shards:index") or set()):
        raise HTTPException(404, "Shard nie ma wpisu w Redis")
    return unregister_shard_redis(r, sid)


@app.get("/api/redis/overview")
def api_redis_overview():
    r = get_redis()
    if not r:
        raise HTTPException(503, "Redis niedostępny")
    return redis_overview(r)


@app.post("/api/redis/cleanup-stale", dependencies=[Depends(require_token)])
def api_redis_cleanup_stale():
    r = get_redis()
    if not r:
        raise HTTPException(503, "Redis niedostępny")
    return cleanup_stale_shards(r)


@app.get("/api/redis/keys")
def api_redis_keys(pattern: str = Query(default="shard:*"), limit: int = Query(default=50, ge=1, le=200)):
    r = get_redis()
    if not r:
        raise HTTPException(503, "Redis niedostępny")
    try:
        return {"keys": scan_redis_keys(r, pattern, limit)}
    except ValueError as e:
        raise HTTPException(400, str(e)) from e


@app.delete("/api/sessions/{uuid}", dependencies=[Depends(require_token)])
def api_delete_session(uuid: str):
    safe = re.sub(r"[^\w\-]", "", uuid)
    if safe != uuid or len(safe) < 8:
        raise HTTPException(400, "Nieprawidłowy UUID")
    r = get_redis()
    if not r:
        raise HTTPException(503, "Redis niedostępny")
    key = f"session:{safe}"
    if not r.exists(key):
        raise HTTPException(404, "Sesja nie istnieje")
    r.delete(key)
    return {"ok": True, "removed": safe}


@app.get("/api/dev/preflight")
def api_dev_preflight():
    return dev_preflight(NETWORK_DIR, get_redis, get_mongo, db_name())


@app.get("/api/dev/mongo")
def api_dev_mongo():
    client = get_mongo()
    if not client:
        raise HTTPException(503, "Mongo niedostępny")
    return mongo_extended(client, db_name())


@app.post("/api/dev/deploy-core", dependencies=[Depends(require_token)])
def api_dev_deploy_core():
    return run_script(NETWORK_DIR / "scripts", "deploy-core")


@app.post("/api/dev/configure-luckperms", dependencies=[Depends(require_token)])
def api_dev_configure_luckperms():
    import sys as _sys
    script = NETWORK_DIR / "scripts" / "configure-luckperms.py"
    if not script.is_file():
        return {"ok": False, "error": "Brak configure-luckperms.py"}
    try:
        proc = subprocess.run(
            [_sys.executable, str(script)],
            cwd=str(NETWORK_DIR),
            capture_output=True,
            text=True,
            timeout=60,
        )
        return {"ok": proc.returncode == 0, "output": (proc.stdout or proc.stderr or "").strip()}
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": "Timeout"}


@app.get("/api/config")
def api_config():
    return {
        "networkDir": str(NETWORK_DIR),
        "authRequired": panel_token() is not None,
        "staleMs": STALE_MS,
        "platform": sys.platform,
        "controlRequiresToken": panel_token() is not None,
    }


# Static UI
static_dir = PANEL_DIR / "static"
if static_dir.is_dir():
    app.mount("/assets", StaticFiles(directory=static_dir), name="assets")


@app.get("/")
def index():
    index_file = static_dir / "index.html"
    if index_file.is_file():
        return FileResponse(index_file)
    return {"message": "Panel UI brak — dodaj panel/static/index.html"}


if __name__ == "__main__":
    import uvicorn
    host = os.environ.get("ELCARTEL_PANEL_HOST", "127.0.0.1")
    port = int(os.environ.get("ELCARTEL_PANEL_PORT", "8080"))
    uvicorn.run("app:app", host=host, port=port, reload=False)
