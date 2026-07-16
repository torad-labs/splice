#!/usr/bin/env python3
"""posttooluse lifecycle entry — dispatches to modules/posttooluse/*.py."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from orchestrator.runner import dispatch

if __name__ == "__main__":
    try:
        dispatch("posttooluse")
    except Exception:
        pass
