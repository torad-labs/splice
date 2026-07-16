#!/usr/bin/env python3
"""pretooluse lifecycle entry — dispatches to modules/pretooluse/*.py."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from orchestrator.runner import dispatch

if __name__ == "__main__":
    try:
        dispatch("pretooluse")
    except Exception:
        pass
