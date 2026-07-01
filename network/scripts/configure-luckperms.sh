#!/usr/bin/env bash
# Wspolny LuckPerms (Mongo + Redis) na velocity i shardach.
set -euo pipefail
cd "$(dirname "$0")/.."
python3 configure-luckperms.py "$@"
