"""Hook module result type — drives the runner state machine.

Stolen verbatim from kandi-main / qgre-agent
.claude/hooks/orchestrator/result.py.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

ResultKind = Literal["block", "inject", "warn", "pass"]


@dataclass(frozen=True)
class HookResult:
    kind: ResultKind
    payload: str
    module_name: str
