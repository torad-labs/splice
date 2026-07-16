#!/usr/bin/env python3
"""Hook orchestrator - dispatches to modules/<lifecycle>/*.py."""
import importlib.util
import json
import sys
from pathlib import Path

HOOKS_DIR = Path(__file__).resolve().parent.parent


def dispatch(lifecycle: str) -> None:
    """Load and run all modules for a lifecycle."""
    modules_dir = HOOKS_DIR / "modules" / lifecycle
    if not modules_dir.exists():
        print(json.dumps({"continue": True}))
        return

    data = json.loads(sys.stdin.read()) if not sys.stdin.isatty() else {}

    results = []
    for module_file in sorted(modules_dir.glob("*.py")):
        if module_file.name.startswith("_"):
            continue

        spec = importlib.util.spec_from_file_location(module_file.stem, module_file)
        if spec is None or spec.loader is None:
            continue

        module = importlib.util.module_from_spec(spec)
        try:
            spec.loader.exec_module(module)

            if hasattr(module, "applies") and not module.applies(data):
                continue

            if hasattr(module, "run"):
                result = module.run(data)
                if result:
                    results.append(result)
        except Exception:
            continue

    # Merge results
    final = {"continue": True}
    messages = []
    for r in results:
        if not r.get("continue", True):
            final["continue"] = False
        if r.get("message"):
            messages.append(r["message"])
        if r.get("decision"):
            final["decision"] = r["decision"]

    if messages:
        final["message"] = "\n".join(messages)

    print(json.dumps(final))
