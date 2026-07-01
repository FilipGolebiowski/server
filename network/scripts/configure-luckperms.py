#!/usr/bin/env python3
"""Ustawia wspolny LuckPerms (MongoDB + Redis) na Velocity i wszystkich shardach Paper.

Czyta network/elcartel.properties (ELCARTEL_MONGO_URI, ELCARTEL_MONGO_DB, ELCARTEL_REDIS_URI)
i patchuje kazdy znaleziony config LuckPerms.

Uzycie:
  python configure-luckperms.py              # z katalogu network/
  python configure-luckperms.py --dry-run  # podglad bez zapisu
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from urllib.parse import urlparse

NETWORK = Path(__file__).resolve().parent.parent


def load_properties() -> dict[str, str]:
    props: dict[str, str] = {}
    p = NETWORK / "config" / "elcartel.properties"
    if not p.is_file():
        print(f"[FAIL] Brak {p}", file=sys.stderr)
        sys.exit(1)
    for line in p.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        props[k.strip()] = v.strip()
    return props


def mongo_uri(props: dict[str, str]) -> str:
    uri = props.get("ELCARTEL_MONGO_URI", "mongodb://localhost:27017/")
    db = props.get("ELCARTEL_MONGO_DB", "elcartel")
    if "?" in uri:
        base, qs = uri.split("?", 1)
        if base.rstrip("/").split("/")[-1] and not base.endswith("/"):
            return uri
        return f"{base.rstrip('/')}/{db}?{qs}"
    if uri.rstrip("/").count("/") >= 3 and not uri.endswith("/"):
        return uri
    return f"{uri.rstrip('/')}/{db}"


def parse_redis(uri: str) -> tuple[str, str, str]:
    u = urlparse(uri)
    host = u.hostname or "127.0.0.1"
    port = u.port or 6379
    return f"{host}:{port}", u.username or "", u.password or ""


def patch_redis_block(text: str, address: str, username: str, password: str) -> str:
    lines = text.splitlines()
    out: list[str] = []
    in_redis = False
    for line in lines:
        if re.match(r"^redis:\s*$", line):
            in_redis = True
            out.append(line)
            continue
        if in_redis and line and not line[0].isspace():
            in_redis = False
        if in_redis and re.match(r"^  (enabled|address|username|password):\s*", line):
            key = re.match(r"^  (\w+):", line).group(1)
            if key == "enabled":
                out.append("  enabled: true")
            elif key == "address":
                out.append(f"  address: {address}")
            elif key == "username":
                out.append(f"  username: '{username}'")
            elif key == "password":
                esc = password.replace("'", "''")
                out.append(f"  password: '{esc}'")
            continue
        out.append(line)
    return "\n".join(out) + ("\n" if text.endswith("\n") else "")


def patch_config(text: str, mongo: str, redis_addr: str, redis_user: str, redis_pass: str) -> str:
    text = re.sub(r"^storage-method:\s*\S+", "storage-method: mongodb", text, flags=re.M)
    text = re.sub(r"^  database:\s*\S+", "  database: elcartel", text, flags=re.M)
    esc = mongo.replace("'", "''")
    text = re.sub(
        r"^  mongodb-connection-uri:\s*.*",
        f"  mongodb-connection-uri: '{esc}'",
        text,
        flags=re.M,
    )
    text = re.sub(r"^messaging-service:\s*\S+", "messaging-service: redis", text, flags=re.M)
    text = re.sub(r"^sync-minutes:\s*\S+", "sync-minutes: -1", text, flags=re.M)
    text = re.sub(r"^watch-files:\s*\S+", "watch-files: false", text, flags=re.M)
    return patch_redis_block(text, redis_addr, redis_user, redis_pass)


def find_configs() -> list[Path]:
    configs: list[Path] = []
    for pattern in ("**/LuckPerms/config.yml", "**/luckperms/config.yml"):
        configs.extend(NETWORK.glob(pattern))
    return sorted(set(configs))


def main() -> int:
    ap = argparse.ArgumentParser(description="Wspolny LuckPerms: Mongo + Redis")
    ap.add_argument("--dry-run", action="store_true", help="Tylko wypisz zmiany")
    args = ap.parse_args()

    props = load_properties()
    mongo = mongo_uri(props)
    redis_uri = props.get("ELCARTEL_REDIS_URI")
    if not redis_uri:
        print("[FAIL] Brak ELCARTEL_REDIS_URI w elcartel.properties", file=sys.stderr)
        return 1
    r_addr, r_user, r_pass = parse_redis(redis_uri)

    configs = find_configs()
    if not configs:
        print("[WARN] Nie znaleziono configow LuckPerms pod network/")
        return 1

    print(f"Mongo URI: {mongo}")
    print(f"Redis:     {r_addr} (user={r_user or '(brak)'})")
    print(f"Pliki ({len(configs)}):")
    for cfg in configs:
        rel = cfg.relative_to(NETWORK)
        new = patch_config(cfg.read_text(encoding="utf-8"), mongo, r_addr, r_user, r_pass)
        if args.dry_run:
            changed = new != cfg.read_text(encoding="utf-8")
            print(f"  {'[PATCH]' if changed else '[OK]   '} {rel}")
        else:
            cfg.write_text(new, encoding="utf-8")
            print(f"  [OK] {rel}")

    print()
    if args.dry_run:
        print("Dry-run — nic nie zapisano. Uruchom bez --dry-run.")
    else:
        print("Gotowe. ZRESTARTUJ velocity + wszystkie shardy (LuckPerms laduje storage przy starcie).")
        print()
        print("Potem na DOWOLNYM serwerze (np. konsola velocity):")
        print("  lp creategroup admin")
        print("  lp group admin permission set elcartel.ban true")
        print("  lp group admin permission set elcartel.mute true")
        print("  lp group admin permission set elcartel.kick true")
        print("  lp group admin permission set elcartel.warn true")
        print("  lp group admin permission set elcartel.eco.admin true")
        print("  lp user <nick> parent add admin")
        print("  lp networksync")
        print()
        print("Uprawnienia z H2 (stare lokalne bazy) NIE migruja — nadaj je raz ponownie.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
