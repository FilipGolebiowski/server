"""Pomocnicze funkcje panelu — szczegoly shardow, Redis, dev tools."""
from __future__ import annotations

import re
import socket
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Callable

STALE_MS = 5000


def safe_server_id(server_id: str) -> str:
    safe = re.sub(r"[^\w\-]", "", server_id)
    if safe != server_id:
        raise ValueError("Nieprawidlowy identyfikator")
    return safe


def unregister_shard_redis(r, shard_id: str) -> dict[str, Any]:
    h = r.hgetall(f"shard:{shard_id}") or {}
    mode = h.get("mode", "")
    r.srem("shards:index", shard_id)
    if mode:
        r.srem(f"shards:mode:{mode}", shard_id)
    r.delete(f"shard:{shard_id}")
    return {"ok": True, "removed": shard_id, "mode": mode}


def redis_overview(r) -> dict[str, Any]:
    now = int(time.time() * 1000)
    shard_ids = list(r.smembers("shards:index") or [])
    stale: list[str] = []
    fresh: list[str] = []
    for sid in shard_ids:
        h = r.hgetall(f"shard:{sid}") or {}
        hb = int(h.get("heartbeat") or 0)
        if hb and now - hb <= STALE_MS:
            fresh.append(sid)
        else:
            stale.append(sid)

    counts: dict[str, int] = {
        "shards:index": len(shard_ids),
        "shard:*": 0,
        "shards:mode:*": 0,
        "session:*": 0,
        "lock:profile:*": 0,
        "handoff:*": 0,
    }
    for key in r.scan_iter("shard:*", count=500):
        counts["shard:*"] += 1
    for key in r.scan_iter("shards:mode:*", count=100):
        counts["shards:mode:*"] += 1
    for key in r.scan_iter("session:*", count=500):
        counts["session:*"] += 1
        if counts["session:*"] >= 500:
            break
    for key in r.scan_iter("lock:profile:*", count=200):
        counts["lock:profile:*"] += 1
        if counts["lock:profile:*"] >= 200:
            break
    for key in r.scan_iter("handoff:*", count=100):
        counts["handoff:*"] += 1
        if counts["handoff:*"] >= 100:
            break

    mode_sets: dict[str, list[str]] = {}
    for key in r.scan_iter("shards:mode:*", count=50):
        mode = key.split(":", 2)[2]
        mode_sets[mode] = sorted(r.smembers(key) or [])

    return {
        "shardIds": sorted(shard_ids),
        "fresh": sorted(fresh),
        "stale": sorted(stale),
        "modeSets": mode_sets,
        "keyCounts": counts,
    }


def cleanup_stale_shards(r) -> dict[str, Any]:
    overview = redis_overview(r)
    removed: list[str] = []
    for sid in overview["stale"]:
        unregister_shard_redis(r, sid)
        removed.append(sid)
    return {"ok": True, "removed": removed, "count": len(removed)}


def scan_redis_keys(r, pattern: str, limit: int = 80) -> list[dict[str, Any]]:
    if not re.match(r"^[a-zA-Z0-9_:*?\[\]-]+$", pattern):
        raise ValueError("Nieprawidlowy wzor klucza")
    out: list[dict[str, Any]] = []
    for key in r.scan_iter(pattern, count=limit * 2):
        if len(out) >= limit:
            break
        t = r.type(key)
        entry: dict[str, Any] = {"key": key, "type": t}
        if t == "hash":
            entry["value"] = r.hgetall(key)
        elif t == "string":
            val = r.get(key)
            entry["value"] = val[:200] if val and len(val) > 200 else val
        elif t == "set":
            members = list(r.smembers(key) or [])
            entry["value"] = members[:30]
            entry["size"] = len(members)
        elif t == "list":
            entry["size"] = r.llen(key)
        else:
            entry["ttl"] = r.ttl(key)
        out.append(entry)
    out.sort(key=lambda x: x["key"])
    return out


def sessions_on_shard(r, shard_id: str, limit: int = 50) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for key in r.scan_iter("session:*", count=300):
        h = r.hgetall(key) or {}
        if h.get("shard") == shard_id:
            out.append({
                "uuid": key.split(":", 1)[1],
                "proxy": h.get("proxy", ""),
                "ts": int(h.get("ts") or 0),
            })
        if len(out) >= limit:
            break
    out.sort(key=lambda s: s["ts"], reverse=True)
    return out


def build_shard_detail(
    shard_id: str,
    *,
    get_redis_fn: Callable,
    merge_fn: Callable,
    local_servers_fn: Callable,
    parse_mode_conf_fn: Callable,
    read_env_fn: Callable,
    parse_props_fn: Callable,
    dir_size_fn: Callable,
    port_open_fn: Callable,
    tail_log_fn: Callable,
    network_dir: Path,
    stale_ms: int = STALE_MS,
) -> dict[str, Any]:
    sid = safe_server_id(shard_id)
    merged = {s["id"]: s for s in merge_fn()}.get(sid, {"id": sid})
    local = {s["id"]: s for s in local_servers_fn()}.get(sid, {})
    mode = merged.get("mode") or local.get("mode", "")

    server_dir = network_dir / "servers" / sid
    props = parse_props_fn(server_dir / "server.properties") if server_dir.is_dir() else {}
    start_sh = server_dir / "start.sh"
    start_bat = server_dir / "start.bat"
    env = read_env_fn(start_sh if start_sh.is_file() else start_bat)

    r = get_redis_fn()
    redis_hash: dict[str, str] = {}
    in_redis = False
    redis_stale = None
    if r:
        redis_hash = r.hgetall(f"shard:{sid}") or {}
        in_redis = sid in (r.smembers("shards:index") or set())
        if redis_hash.get("heartbeat"):
            redis_stale = int(time.time() * 1000) - int(redis_hash["heartbeat"]) > stale_ms

    sessions = sessions_on_shard(r, sid) if r else []
    mode_conf = parse_mode_conf_fn(mode) if mode else {}

    log_tail: list[str] = []
    try:
        log_tail = tail_log_fn(sid, 20).get("lines", [])
    except Exception:
        pass

    panel_log = network_dir / ".logs" / f"{sid}.log"
    panel_log_tail: list[str] = []
    if panel_log.is_file():
        try:
            panel_log_tail = panel_log.read_text(encoding="utf-8", errors="replace").splitlines()[-15:]
        except OSError:
            pass

    plugins = server_dir / "plugins"
    core_jars = list(plugins.glob("core-paper*.jar")) if plugins.is_dir() else []

    port = local.get("port") or int(props.get("server-port") or 0)
    host = local.get("host") or props.get("server-ip") or "127.0.0.1"

    return {
        **merged,
        "id": sid,
        "mode": mode,
        "modeConf": mode_conf,
        "local": {
            "exists": server_dir.is_dir(),
            "path": str(server_dir),
            "port": port,
            "host": host,
            "addr": env.get("ADDR") or merged.get("addr") or f"{host}:{port}",
            "motd": props.get("motd", local.get("motd", "")),
            "listening": local.get("listening", port_open_fn(host, port) if port else False),
            "diskMb": local.get("diskMb", dir_size_fn(server_dir)),
            "worldMb": local.get("worldMb", 0),
            "maxPlayers": int(props.get("max-players") or 0),
            "viewDistance": props.get("view-distance", ""),
            "simulationDistance": props.get("simulation-distance", ""),
            "levelName": props.get("level-name", "world"),
            "hasPaperJar": (server_dir / "paper.jar").is_file(),
            "hasStartSh": start_sh.is_file(),
            "hasStartBat": start_bat.is_file(),
            "coreJar": core_jars[-1].name if core_jars else None,
        },
        "env": {f"ELCARTEL_{k}": v for k, v in env.items()},
        "redis": {
            "registered": in_redis,
            "hash": redis_hash,
            "stale": redis_stale,
            "keys": {
                "index": "shards:index",
                "shard": f"shard:{sid}",
                "mode": f"shards:mode:{mode}" if mode else None,
            },
        },
        "sessions": sessions,
        "sessionCount": len(sessions),
        "logs": {
            "paper": str(server_dir / "logs" / "latest.log"),
            "panel": str(panel_log) if panel_log.is_file() else None,
            "paperTail": log_tail,
            "panelTail": panel_log_tail,
        },
        "paths": {
            "serverDir": str(server_dir),
            "worldDir": str(server_dir / (props.get("level-name") or "world")),
            "pluginsDir": str(plugins),
            "templatesMode": str(network_dir / "templates" / mode) if mode else None,
        },
    }


def dev_preflight(network_dir: Path, get_redis_fn: Callable, get_mongo_fn: Callable, db_name: str) -> dict[str, Any]:
    checks: list[dict[str, Any]] = []

    def add(name: str, ok: bool, detail: str = ""):
        checks.append({"name": name, "ok": ok, "detail": detail})

    # Java
    try:
        proc = subprocess.run(["java", "-version"], capture_output=True, text=True, timeout=5)
        ver_line = proc.stderr.splitlines()[0] if proc.stderr else "?"
        add("Java", proc.returncode == 0, ver_line)
    except Exception as e:
        add("Java", False, str(e))

    add("Redis", get_redis_fn() is not None)
    mongo = get_mongo_fn()
    add("MongoDB", mongo is not None)
    if mongo:
        try:
            db = mongo[db_name]
            add("Mongo players", True, f"{db['players'].estimated_document_count()} kont")
        except Exception as e:
            add("Mongo players", False, str(e))

    for jar, label in [
        (network_dir / "velocity" / "velocity.jar", "velocity.jar"),
        (network_dir / "servers" / "limbo" / "paper.jar", "limbo paper.jar"),
    ]:
        add(label, jar.is_file(), str(jar))

    ports: list[dict[str, Any]] = []
    servers = network_dir / "servers"
    if servers.is_dir():
        for d in sorted(servers.iterdir()):
            if not d.is_dir():
                continue
            sp = d / "server.properties"
            if not sp.is_file():
                continue
            port = 0
            for line in sp.read_text(encoding="utf-8", errors="replace").splitlines():
                if line.strip().startswith("server-port="):
                    port = int(line.split("=", 1)[1].strip())
            if port:
                open_ = False
                try:
                    with socket.create_connection(("127.0.0.1", port), timeout=0.3):
                        open_ = True
                except OSError:
                    pass
                ports.append({"id": d.name, "port": port, "listening": open_})
    ports.append({"id": "velocity", "port": 25565, "listening": _port_open(25565)})
    proxy_ok = next((p for p in ports if p["id"] == "velocity"), {}).get("listening", False)
    add("Proxy :25565", proxy_ok)

    stale_shards = []
    r = get_redis_fn()
    if r:
        ov = redis_overview(r)
        stale_shards = ov["stale"]

    return {
        "checks": checks,
        "ports": ports,
        "staleRedisShards": stale_shards,
        "allOk": all(c["ok"] for c in checks),
        "timestamp": int(time.time() * 1000),
    }


def _port_open(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.35):
            return True
    except OSError:
        return False


def mongo_extended(client, db_name: str) -> dict[str, Any]:
    db = client[db_name]
    cols = ["players", "auth", "punishments"]
    counts: dict[str, int | str] = {}
    for c in cols:
        try:
            counts[c] = db[c].estimated_document_count()
        except Exception:
            counts[c] = "?"
    mode_cols = [n for n in db.list_collection_names() if n.startswith("mode_profiles_") or n.startswith("economy_")]
    return {"collections": counts, "modeCollections": sorted(mode_cols)[:30]}


def run_script(network_dir: Path, name: str) -> dict[str, Any]:
    if sys.platform == "win32":
        script = network_dir / f"{name}.ps1"
        cmd = ["powershell", "-ExecutionPolicy", "Bypass", "-File", str(script)]
    else:
        script = network_dir / f"{name}.sh"
        cmd = ["bash", str(script)]
    if not script.is_file():
        return {"ok": False, "error": f"Brak {script.name}"}
    try:
        proc = subprocess.run(cmd, cwd=str(network_dir), capture_output=True, text=True, timeout=180)
        return {
            "ok": proc.returncode == 0,
            "output": (proc.stdout or proc.stderr or "").strip()[:4000],
            "returncode": proc.returncode,
        }
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": "Timeout"}
