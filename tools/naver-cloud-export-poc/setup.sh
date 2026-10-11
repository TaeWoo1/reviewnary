#!/usr/bin/env bash
# Isolated setup. Creates .venv inside this directory only. Touches nothing in the repo.
set -euo pipefail
cd "$(dirname "$0")"
python3 -m venv .venv
.venv/bin/python -m pip install --quiet --upgrade pip
.venv/bin/python -m pip install --quiet -r requirements.txt
.venv/bin/python -m poc.run selfcheck
echo
echo "next: cp .env.example .env  (fill it in), then:  .venv/bin/python -m poc.run manifest"
