#!/usr/bin/env python3
"""
Package CPA skill into a .skill file for distribution.
Usage: python scripts/package.py
Output: competitive-pressure-analysis.skill (a zip file)
"""

import os
import zipfile
import sys
from pathlib import Path

SKILL_DIR = Path(__file__).parent.parent
OUTPUT_NAME = "competitive-pressure-analysis.skill"
OUTPUT_PATH = SKILL_DIR.parent / OUTPUT_NAME

INCLUDE_PATTERNS = [
    "SKILL.md",
    "blocks/*.md",
    "references/*.md",
    "evals/*.json",
    "assets/*.html",
    "scripts/*.py",
]

EXCLUDE = {".DS_Store", "__pycache__", "*.pyc"}

def should_include(path: Path) -> bool:
    name = path.name
    if name in EXCLUDE or name.endswith(".pyc"):
        return False
    return True

def package():
    files = []
    for pattern in INCLUDE_PATTERNS:
        for f in SKILL_DIR.glob(pattern):
            if should_include(f) and f.is_file():
                files.append(f)

    if not files:
        print("ERROR: No files found to package.")
        sys.exit(1)

    with zipfile.ZipFile(OUTPUT_PATH, 'w', zipfile.ZIP_DEFLATED) as zf:
        for f in files:
            arcname = f.relative_to(SKILL_DIR)
            zf.write(f, arcname)
            print(f"  + {arcname}")

    size_kb = OUTPUT_PATH.stat().st_size / 1024
    print(f"\nPackaged {len(files)} files → {OUTPUT_PATH.name} ({size_kb:.1f} KB)")
    print(f"Full path: {OUTPUT_PATH}")

if __name__ == "__main__":
    package()
