#!/usr/bin/env bash
# V4-87 — red-green selftest for the environment-variable documentation wall.
#
# The wall's proof lives INSIDE the checker, as `--selftest`, because that is the idiom the
# template it mirrors uses (checks/config/quirks-keys-documented.ts, gate legs
# checks/gate.sh:150-161): the fixtures are Kotlin and TOML source strings, and keeping them
# beside the parser they exercise is what lets a parser change and its proof move together.
# This script exists so the row's verify command and any bash-shaped caller have the
# conventional `checks/<name>-selftest.sh` entry point; it adds no assertions of its own,
# because a second copy of the proof is a second thing to drift.
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 checks/config/env-vars-documented.py --selftest
