#!/usr/bin/env bash
# checks/catalog-metadata-selftest.sh — red-green proof for catalog-metadata-sync.py, run by the
# gate. Same defence-in-depth idiom as secret-scan-allow-selftest.sh: the checker guards the
# catalog↔verification-metadata seam, this canary guards the CHECKER, so a bug in it (a parser
# change that starts silently skipping entries, a namespace regression) fails the gate instead of
# silently waving drift through. Fixtures are synthetic but carry the REAL metadata xmlns — a
# non-namespace-aware parser must fail HERE, not on the real file.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECKER="$ROOT/checks/catalog-metadata-sync.py"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail=0
err() { echo "  ✗ catalog-metadata-selftest: $1"; fail=1; }

# ── fixtures ──────────────────────────────────────────────────────────────────────────────────
# One of each catalog shape the checker must cover: version.ref library, inline-version library,
# versionless BOM rider (must be skipped), plugin (must be checked as its marker artifact), and
# an UNREFERENCED floor version (netty-style: no library uses it, but a bump must still trip the
# check via version presence).
cat > "$tmp/catalog.toml" <<'EOF'
[versions]
ktor = "3.5.2"
floor = "9.9.9.Final"
[libraries]
ktor-server-core = { module = "io.ktor:ktor-server-core", version.ref = "ktor" }
zstd = { module = "com.example:zstd", version = "1.0" }
bom-rider = { module = "org.junit.jupiter:junit-jupiter" }
[plugins]
kover = { id = "org.example.kover", version = "0.9.9" }
EOF

meta_head='<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="https://schema.gradle.org/dependency-verification https://schema.gradle.org/dependency-verification/dependency-verification-1.3.xsd">
   <configuration>
      <verify-metadata>true</verify-metadata>
      <verify-signatures>false</verify-signatures>
   </configuration>
   <components>'
meta_tail='   </components>
</verification-metadata>'
comp() { printf '      <component group="%s" name="%s" version="%s"><artifact name="x.jar"><sha256 value="0" origin="test"/></artifact></component>\n' "$1" "$2" "$3"; }

{ echo "$meta_head"
  comp io.ktor ktor-server-core 3.5.2
  comp com.example zstd 1.0
  comp org.example.kover org.example.kover.gradle.plugin 0.9.9
  comp io.example transitive-floor 9.9.9.Final
  echo "$meta_tail"; } > "$tmp/good.xml"

# Each bad fixture removes exactly one obligation from good.xml.
{ echo "$meta_head"
  comp io.ktor ktor-server-core 3.5.1
  comp com.example zstd 1.0
  comp org.example.kover org.example.kover.gradle.plugin 0.9.9
  comp io.example transitive-floor 9.9.9.Final
  echo "$meta_tail"; } > "$tmp/bad-lib.xml"

{ echo "$meta_head"
  comp io.ktor ktor-server-core 3.5.2
  comp com.example zstd 1.0
  comp org.example.kover org.example.kover.gradle.plugin 0.9.8
  comp io.example transitive-floor 9.9.9.Final
  echo "$meta_tail"; } > "$tmp/bad-plugin.xml"

{ echo "$meta_head"
  comp io.ktor ktor-server-core 3.5.2
  comp com.example zstd 1.0
  comp org.example.kover org.example.kover.gradle.plugin 0.9.9
  echo "$meta_tail"; } > "$tmp/bad-floor.xml"

# Marker absent but the plugin's version pinned via another component: the build-logic pattern
# (plugin applied by bare id inside a precompiled script, implementation jar on that classpath —
# the marker never resolves, so regeneration can never add it). Obligation degrades to version
# presence and the checker must PASS.
{ echo "$meta_head"
  comp io.ktor ktor-server-core 3.5.2
  comp com.example zstd 1.0
  comp org.example kover-gradle-plugin-impl 0.9.9
  comp io.example transitive-floor 9.9.9.Final
  echo "$meta_tail"; } > "$tmp/markerless-plugin.xml"

# ── assertions ────────────────────────────────────────────────────────────────────────────────
run_checker() { python3 "$CHECKER" "$tmp/catalog.toml" "$tmp/$1" > "$tmp/out" 2>&1; }

if run_checker good.xml; then :; else
  err "compliant fixture must exit 0 (got $?): $(head -3 "$tmp/out" | tr '\n' ' ')"
fi

if run_checker bad-lib.xml; then
  err "missing library version must exit 1 (exited 0)"
elif ! grep -q "io.ktor:ktor-server-core:3.5.2" "$tmp/out"; then
  err "missing library failure must name io.ktor:ktor-server-core:3.5.2"
fi

if run_checker bad-plugin.xml; then
  err "missing plugin marker must exit 1 (exited 0)"
elif ! grep -q "org.example.kover.gradle.plugin:0.9.9" "$tmp/out"; then
  err "missing plugin failure must name org.example.kover.gradle.plugin:0.9.9"
fi

if run_checker bad-floor.xml; then
  err "absent floor version must exit 1 (exited 0)"
elif ! grep -q "9.9.9.Final" "$tmp/out"; then
  err "floor failure must name the version 9.9.9.Final"
fi

if run_checker markerless-plugin.xml; then :; else
  err "markerless plugin with version pinned elsewhere must exit 0 (got build-logic pattern wrong): $(head -3 "$tmp/out" | tr '\n' ' ')"
fi

exit "$fail"
