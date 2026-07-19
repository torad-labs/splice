#!/usr/bin/env bash
set -euo pipefail
! grep -qi "encrypted CoT" config/splice.example.toml
grep -qi "password-equivalent" config/splice.example.toml
grep -qi "experimental" gateway/app/src/main/kotlin/splice/app/TopologyLoader.kt
echo "VERIFY OSS-F: OK"
