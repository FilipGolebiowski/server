"""Uruchamianie i zatrzymywanie serwerow sieci z panelu."""
from __future__ import annotations

import os
import re
import shlex
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

NETWORK_DIR = Path(__file__).resolve().parent.parent
LOG_DIR = NETWORK_DIR / ".logs"
PID_FILE = Path(__file__).resolve().parent / ".pids.json"

# Aktywne procesy z stdin (graceful stop dopoki panel dziala)
_active: dict[str, subprocess.Popen] = {}

SERVER_ID_RE = re.compile(r"^[\w\-]+$")


def _valid_id(server_id: str) -> str:
    if not SERVER_ID_RE.match(server_id):
        raise ValueError("Nieprawidlowy identyfikator serwera")
    return server_id


def server_dir(server_id: str) -> Path:
    sid = _valid_id(server_id)
    if sid == "velocity":
        return NETWORK_DIR / "velocity"
    return NETWORK_DIR / "servers" / sid


def server_port(server_id: str) -> int:
    sid = _valid_id(server_id)
    if sid == "velocity":
        return 25565
    props = server_dir(sid) / "server.properties"
    if not props.is_file():
        return 0
    for line in props.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if line.startswith("server-port="):
            try:
                return int(line.split("=", 1)[1].strip())
            except ValueError:
                return 0
    return 0


def is_listening(server_id: str) -> bool:
    import socket
    port = server_port(server_id)
    if not port:
        return False
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.35):
            return True
    except OSError:
        return False


def _read_env_from_script(text: str) -> dict[str, str]:
    env: dict[str, str] = {}
    for m in re.finditer(r'(?:export\s+)?ELCARTEL_(\w+)=([^\s"\']+|"(?:[^"\\]|\\.)*")', text):
        env[f"ELCARTEL_{m.group(1)}"] = m.group(2).strip('"')
    for m in re.finditer(r'set\s+"ELCARTEL_(\w+)=([^"]+)"', text, re.I):
        env[f"ELCARTEL_{m.group(1)}"] = m.group(2)
    return env


def _parse_java_cmd(script_path: Path) -> list[str] | None:
    if not script_path.is_file():
        return None
    text = script_path.read_text(encoding="utf-8", errors="replace")
    for line in text.splitlines():
        s = line.strip()
        if s.startswith("exec java"):
            s = s[5:].strip()
        if s.startswith("java ") and "-jar" in s:
            parts = shlex.split(s, posix=(sys.platform != "win32"))
            return parts
    return None


def _launch_env(server_id: str) -> tuple[Path, list[str], dict[str, str]]:
    root = server_dir(server_id)
    if not root.is_dir():
        raise FileNotFoundError(f"Brak katalogu serwera: {root}")
    script = root / ("start.bat" if sys.platform == "win32" else "start.sh")
    if not script.is_file():
        script = root / ("start.sh" if script.name == "start.bat" else "start.bat")
    cmd = _parse_java_cmd(script)
    if not cmd:
        raise RuntimeError(f"Nie znaleziono komendy java w {script}")
    env = os.environ.copy()
    if script.is_file():
        env.update(_read_env_from_script(script.read_text(encoding="utf-8", errors="replace")))
    return root, cmd, env


def _tmux_has(session: str) -> bool:
    try:
        r = subprocess.run(
            ["tmux", "has-session", "-t", session],
            capture_output=True,
            timeout=3,
        )
        return r.returncode == 0
    except (FileNotFoundError, subprocess.TimeoutExpired):
        return False


def _save_pid(server_id: str, pid: int, mode: str) -> None:
    import json
    data: dict[str, Any] = {}
    if PID_FILE.is_file():
        try:
            data = json.loads(PID_FILE.read_text(encoding="utf-8"))
        except Exception:
            data = {}
    data[server_id] = {"pid": pid, "mode": mode, "ts": int(time.time())}
    PID_FILE.write_text(json.dumps(data, indent=2), encoding="utf-8")


def _clear_pid(server_id: str) -> None:
    import json
    if not PID_FILE.is_file():
        return
    try:
        data = json.loads(PID_FILE.read_text(encoding="utf-8"))
        data.pop(server_id, None)
        PID_FILE.write_text(json.dumps(data, indent=2), encoding="utf-8")
    except Exception:
        pass


def _pid_on_port(port: int) -> int | None:
    try:
        import psutil
        for c in psutil.net_connections(kind="inet"):
            if c.laddr and c.laddr.port == port and c.status == "LISTEN" and c.pid:
                return c.pid
    except Exception:
        pass
    return None


def _stop_cmd(server_id: str) -> str:
    return "end" if server_id == "velocity" else "stop"


def start_server(server_id: str, *, window: bool = False) -> dict[str, Any]:
    sid = _valid_id(server_id)
    if is_listening(sid):
        return {"ok": False, "error": "Serwer juz nasluchuje na porcie", "serverId": sid}

    root, cmd, env = _launch_env(sid)
    LOG_DIR.mkdir(exist_ok=True)
    log_path = LOG_DIR / f"{sid}.log"

    # Linux: tmux (jak ADMIN.md)
    if sys.platform != "win32" and not window and _tmux_available():
        script = root / "start.sh"
        if not script.is_file():
            raise RuntimeError(f"Brak {script}")
        if _tmux_has(sid):
            return {"ok": False, "error": f"Sesja tmux '{sid}' juz istnieje", "serverId": sid}
        subprocess.run(
            ["tmux", "new", "-d", "-s", sid, f"bash {script.name}"],
            cwd=str(root),
            check=True,
            timeout=10,
        )
        _save_pid(sid, 0, "tmux")
        return {"ok": True, "serverId": sid, "mode": "tmux", "log": str(log_path)}

    # Windows: osobne okno (jak start-all.ps1)
    if sys.platform == "win32" and window:
        bat = root / "start.bat"
        if not bat.is_file():
            raise RuntimeError(f"Brak {bat}")
        subprocess.Popen(
            ["cmd", "/c", "start", f"El Cartel - {sid}", str(bat.name)],
            cwd=str(root),
        )
        _save_pid(sid, 0, "window")
        return {"ok": True, "serverId": sid, "mode": "window", "note": "Otwarto okno konsoli"}

    # Tlo: subprocess z stdin (graceful stop z panelu)
    log_f = open(log_path, "a", encoding="utf-8")
    log_f.write(f"\n--- panel start {time.strftime('%Y-%m-%d %H:%M:%S')} ---\n")
    log_f.flush()
    kwargs: dict[str, Any] = {
        "cwd": str(root),
        "env": env,
        "stdin": subprocess.PIPE,
        "stdout": log_f,
        "stderr": subprocess.STDOUT,
    }
    if sys.platform == "win32":
        kwargs["creationflags"] = subprocess.CREATE_NO_WINDOW  # type: ignore[attr-defined]
    proc = subprocess.Popen(cmd, **kwargs)
    _active[sid] = proc
    _save_pid(sid, proc.pid, "subprocess")
    return {"ok": True, "serverId": sid, "mode": "subprocess", "pid": proc.pid, "log": str(log_path)}


def stop_server(server_id: str, *, force: bool = False) -> dict[str, Any]:
    sid = _valid_id(server_id)
    if not is_listening(sid) and sid not in _active and not _tmux_has(sid):
        return {"ok": False, "error": "Serwer nie dziala", "serverId": sid}

    cmd = _stop_cmd(sid)

    # tmux graceful
    if _tmux_has(sid):
        subprocess.run(["tmux", "send-keys", "-t", sid, cmd, "Enter"], timeout=5)
        return {"ok": True, "serverId": sid, "mode": "tmux", "graceful": True}

    # subprocess graceful (panel trzyma stdin)
    proc = _active.get(sid)
    if proc and proc.poll() is None and proc.stdin and not force:
        try:
            proc.stdin.write(f"{cmd}\n".encode())
            proc.stdin.flush()
            return {"ok": True, "serverId": sid, "mode": "subprocess", "graceful": True}
        except OSError:
            pass

    if force or not proc:
        port = server_port(sid)
        pid = _pid_on_port(port) if port else None
        if pid:
            try:
                import psutil
                psutil.Process(pid).terminate()
                _active.pop(sid, None)
                _clear_pid(sid)
                return {
                    "ok": True,
                    "serverId": sid,
                    "mode": "terminate",
                    "graceful": False,
                    "pid": pid,
                    "note": "Zatrzymano proces po porcie (bez gwarancji save-all)",
                }
            except Exception as e:
                return {"ok": False, "error": str(e), "serverId": sid}

    return {
        "ok": False,
        "error": "Nie mozna wyslac stop — uruchom serwer z panelu (tlo) lub uzyj tmux / wpisz stop w oknie konsoli",
        "serverId": sid,
    }


def _tmux_available() -> bool:
    try:
        subprocess.run(["tmux", "-V"], capture_output=True, timeout=3)
        return True
    except (FileNotFoundError, subprocess.TimeoutExpired):
        return False


def list_controllable_servers() -> list[str]:
    ids = []
    limbo = NETWORK_DIR / "servers" / "limbo"
    if limbo.is_dir():
        ids.append("limbo")
    servers = NETWORK_DIR / "servers"
    if servers.is_dir():
        for d in sorted(servers.iterdir()):
            if d.is_dir() and d.name != "limbo":
                if (d / "start.bat").is_file() or (d / "start.sh").is_file():
                    ids.append(d.name)
    ids.append("velocity")
    return ids


def start_network(*, window: bool = False) -> dict[str, Any]:
    """Kolejnosc jak start-all: limbo -> shardy -> velocity."""
    results: list[dict[str, Any]] = []
    order: list[str] = []
    if (NETWORK_DIR / "servers" / "limbo").is_dir():
        order.append("limbo")
    for d in sorted((NETWORK_DIR / "servers").iterdir()) if (NETWORK_DIR / "servers").is_dir() else []:
        if d.name != "limbo" and ((d / "start.bat").is_file() or (d / "start.sh").is_file()):
            order.append(d.name)
    if (NETWORK_DIR / "velocity" / "start.bat").is_file() or (NETWORK_DIR / "velocity" / "start.sh").is_file():
        order.append("velocity")

    delays = {"limbo": 8, "velocity": 0}
    default_delay = 5

    for sid in order:
        if is_listening(sid):
            results.append({"serverId": sid, "ok": True, "skipped": True, "reason": "juz dziala"})
            continue
        try:
            r = start_server(sid, window=window)
            results.append(r)
        except Exception as e:
            results.append({"serverId": sid, "ok": False, "error": str(e)})
        delay = delays.get(sid, default_delay)
        if delay and sid != order[-1]:
            time.sleep(delay)

    ok = all(r.get("ok") for r in results)
    return {"ok": ok, "results": results}


def stop_network(*, force: bool = False) -> dict[str, Any]:
    """Odwrotna kolejnosc: velocity -> shardy -> limbo."""
    order = list(reversed(list_controllable_servers()))
    results: list[dict[str, Any]] = []
    for sid in order:
        if not is_listening(sid) and sid not in _active and not _tmux_has(sid):
            results.append({"serverId": sid, "ok": True, "skipped": True})
            continue
        results.append(stop_server(sid, force=force))
        time.sleep(1)
    ok = all(r.get("ok") for r in results if not r.get("skipped"))
    return {"ok": ok, "results": results}


def server_control_status(server_id: str) -> dict[str, Any]:
    sid = _valid_id(server_id)
    return {
        "serverId": sid,
        "listening": is_listening(sid),
        "port": server_port(sid),
        "managed": sid in _active or _tmux_has(sid),
        "tmux": _tmux_has(sid),
    }
