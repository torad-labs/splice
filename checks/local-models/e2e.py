#!/usr/bin/env python3
"""Local models e2e (FEATURES.md §10): starts a splice daemon from a jar against a config with one
good Ollama head and deliberately bad heads, then proves — against the live runtime — that a row
naming an unlisted model or over-declaring context is refused at boot, that the good head streams,
survives a cancelled turn, carries a tool result into the next turn, and that doctor reports it all
as "local". Fail-closed: the receipt is written only when every check passes.

  e2e.py --jar app-all.jar --config splice-local.toml --home /isolated/home \
         --good-head ollama --bad-heads ollama-unlisted,ollama-overclaim --out receipts/local-models.json

Stdlib only. The runtime is the operator's: this script never pulls a model or starts Ollama.
"""
from __future__ import annotations

import argparse
import hashlib
import http.client
import json
import os
import re
import signal
import subprocess
import sys
import time
import tomllib
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STREAM_PROBE = ROOT / "checks" / "e2e" / "stream_probe.py"
TOOL = {
    "name": "get_weather",
    "description": "Current weather for a city.",
    "input_schema": {"type": "object", "properties": {"city": {"type": "string"}}, "required": ["city"]},
}


class Failed(Exception):
    pass


def ollama_json(base: str, path: str, body: dict | None = None, timeout: int = 300) -> dict:
    req = urllib.request.Request(base + path, data=json.dumps(body).encode() if body else None,
                                 headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=timeout).read())


def runtime_facts(base: str, model: str) -> dict:
    """Version, the model's digest, its card context, and — after a warm-up that loads it — the
    window Ollama actually allocated (/api/ps). Warm-up is one tiny generate; the model stays
    loaded for the daemon boot that follows, so the boot verdict reads the served window."""
    version = ollama_json(base, "/api/version")["version"]
    tags = {m["name"]: m for m in ollama_json(base, "/api/tags")["models"]}
    if model not in tags:
        raise Failed(f"{model} is not pulled (ollama list: {sorted(tags)})")
    show = ollama_json(base, "/api/show", {"model": model})
    info = show["model_info"]
    card = next((v for k, v in info.items() if k.endswith(".context_length")), None)
    num_ctx = re.search(r"^\s*num_ctx\s+(\d+)", show.get("parameters", ""), re.M)
    t0 = time.monotonic()
    ollama_json(base, "/api/generate", {"model": model, "prompt": "hi", "stream": False, "think": False,
                                        "keep_alive": "15m"})
    load_s = round(time.monotonic() - t0, 1)
    served = next((m.get("context_length") for m in ollama_json(base, "/api/ps")["models"]
                   if m["name"] == model), None)
    if served is None:
        raise Failed("/api/ps reports no context_length for the loaded model")
    return {
        "runtime": "Ollama", "version": version, "model": model, "digest": tags[model]["digest"],
        "card_context_length": card, "num_ctx": int(num_ctx.group(1)) if num_ctx else None,
        "served_context_length": served, "warm_load_s": load_s, "capabilities": show.get("capabilities"),
    }


class Daemon:
    def __init__(self, jar: Path, config: Path, home: Path, control_port: int):
        self.jar, self.config, self.home, self.port = jar, config, home, control_port
        self.log = home / "daemon.log"
        self.proc: subprocess.Popen | None = None
        state = tomllib.loads(config.read_text())["daemon"]["state_dir"]
        self.state = Path(state)

    def env(self) -> dict:
        return {**os.environ, "SPLICE_CONFIG": str(self.config), "OLLAMA_API_KEY": "ollama",
                "HOME": str(self.home)}

    def start(self) -> None:
        self.home.mkdir(parents=True, exist_ok=True)
        (self.state / "daemon.lock").unlink(missing_ok=True)
        self.proc = subprocess.Popen(
            ["java", f"-Duser.home={self.home}", "-jar", str(self.jar), "daemon"],
            cwd=self.home, env=self.env(), stdout=open(self.log, "w"), stderr=subprocess.STDOUT,
            stdin=subprocess.DEVNULL, start_new_session=True)
        for _ in range(240):
            if self.proc.poll() is not None:
                raise Failed(f"daemon exited {self.proc.returncode}:\n{self.log.read_text()[-2000:]}")
            if "[daemon] up" in self.log.read_text():
                return
            time.sleep(0.5)
        raise Failed("daemon did not come up in 120s:\n" + self.log.read_text()[-2000:])

    def stop(self) -> None:
        if not self.proc or self.proc.poll() is not None:
            return
        os.killpg(self.proc.pid, signal.SIGTERM)
        try:
            self.proc.wait(10)
        except subprocess.TimeoutExpired:
            os.killpg(self.proc.pid, signal.SIGKILL)
            self.proc.wait(5)
        (self.state / "daemon.lock").unlink(missing_ok=True)

    def mgmt_key(self) -> str:
        return (self.state / "mgmt-key").read_text().strip()

    def get(self, path: str) -> dict:
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}",
                                     headers={"Authorization": f"Bearer {self.mgmt_key()}"})
        return json.loads(urllib.request.urlopen(req, timeout=10).read())


def sse(port: int, bearer: str, body: dict, stop_after_first_delta: bool = False) -> tuple[list, str]:
    """POST /v1/messages streaming; returns (events, raw_tail). With stop_after_first_delta the socket
    is closed as soon as the first content delta arrives — the client-abort the head must survive."""
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=180)
    conn.request("POST", "/v1/messages", body=json.dumps({**body, "stream": True}),
                 headers={"Content-Type": "application/json", "Authorization": f"Bearer {bearer}"})
    resp = conn.getresponse()
    if resp.status != 200:
        raise Failed(f"/v1/messages -> {resp.status}: {resp.read()[:300]!r}")
    events, buf, name = [], "", None
    while True:
        chunk = resp.read1(65536)
        if not chunk:
            break
        buf += chunk.decode(errors="replace")
        while "\n\n" in buf:
            frame, buf = buf.split("\n\n", 1)
            data = None
            for line in frame.splitlines():
                if line.startswith("event: "):
                    name = line[7:]
                elif line.startswith("data: "):
                    data = json.loads(line[6:])
            if name:
                events.append((name, data))
            if stop_after_first_delta and name == "content_block_delta":
                conn.close()
                return events, buf
            name = None
    conn.close()
    return events, buf


def text_of(events: list) -> str:
    return "".join(d.get("delta", {}).get("text", "") for n, d in events
                   if n == "content_block_delta" and d and d.get("delta", {}).get("type") == "text_delta")


def stop_reason(events: list) -> str | None:
    return next((d["delta"].get("stop_reason") for n, d in events if n == "message_delta" and d), None)


def tool_use_blocks(events: list) -> list[dict]:
    """Reassemble tool_use blocks from content_block_start + input_json_delta."""
    blocks: dict[int, dict] = {}
    for n, d in events:
        if n == "content_block_start" and d["content_block"].get("type") == "tool_use":
            blocks[d["index"]] = {**d["content_block"], "_json": ""}
        elif n == "content_block_delta" and d["index"] in blocks and d["delta"].get("type") == "input_json_delta":
            blocks[d["index"]]["_json"] += d["delta"]["partial_json"]
    out = []
    for b in blocks.values():
        raw = b.pop("_json")
        b["input"] = json.loads(raw) if raw.strip() else b.get("input", {})
        out.append(b)
    return out


def perf_rows(path: Path) -> list[dict]:
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def check_boot(d: Daemon, good: str, bad: list[str]) -> dict:
    heads = None
    for _ in range(60):
        heads = {h["key"]: h for h in d.get("/api/heads")["heads"]}
        if good in heads and heads[good].get("healthy"):
            break
        time.sleep(1)
    if good not in heads:
        raise Failed(f"good head {good} missing from /api/heads: {sorted(heads)}")
    if not heads[good].get("healthy"):
        raise Failed(f"good head {good} never became healthy: {heads[good]}")
    present = [b for b in bad if b in heads]
    if present:
        raise Failed(f"bad heads served instead of refused: {present}")
    log = d.log.read_text()
    refusals = {}
    for b in bad:
        line = next((ln for ln in log.splitlines() if b in ln and "refuses" in ln), None)
        if not line:
            raise Failed(f"no refusal line for {b} in daemon.log")
        refusals[b] = line.strip()
    up = next(ln for ln in log.splitlines() if "[daemon] up" in ln)
    if f"DEGRADED=" not in up or not all(b in up for b in bad):
        raise Failed(f"[daemon] up line does not list the refused heads: {up}")
    return {"good_head": {k: heads[good][k] for k in ("key", "port", "healthy", "authKind")},
            "refused": refusals, "up_line": up.strip()}


def check_streaming(port: int, bearer: str, model: str) -> dict:
    proc = subprocess.run(
        [sys.executable, str(STREAM_PROBE), "--head", "ollama", "--port", str(port), "--model", model,
         "--total-ms", "180000", "--first-delta-ms", "90000"],
        env={**os.environ, "SPLICE_PROBE_BEARER": bearer}, capture_output=True, text=True)
    summary = json.loads(proc.stdout.strip().splitlines()[-1]) if proc.stdout.strip() else {}
    if proc.returncode != 0:
        raise Failed(f"stream_probe failed rc={proc.returncode}: {summary or proc.stderr[-800:]}")
    return summary


# A client that goes away lands as one of two perf outcomes: client_abort when the turn coroutine is
# cancelled cooperatively, error:conn-reset when the tear surfaces as a failed write first (a raw
# socket close mid-frame, which is what this harness does). Both are "the client hung up".
GONE = ("client_abort", "error:conn-reset")


def ollama_cancelled(since_epoch: float) -> str:
    """Ollama's own log line for a cancelled generation, when journalctl is readable; else why not."""
    since = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(since_epoch - 1))
    proc = subprocess.run(["journalctl", "-u", "ollama", "--since", since, "--no-pager", "-o", "cat"],
                          capture_output=True, text=True)
    if proc.returncode != 0 or not proc.stdout.strip():
        return "journal unavailable"
    line = next((ln for ln in proc.stdout.splitlines() if "cancel task" in ln), None)
    if line is None:
        raise Failed("Ollama's journal shows no cancelled task after the client hung up — generation kept running")
    return line.strip()


def check_cancellation(port: int, bearer: str, model: str, perf: Path) -> dict:
    gone_before = sum(1 for r in perf_rows(perf) if r.get("outcome") in GONE)
    t_cancel = time.time()
    body = {"model": model, "max_tokens": 1024,
            "messages": [{"role": "user", "content": "Write a 500 word essay about rivers. No thinking, start immediately."}]}
    events, _ = sse(port, bearer, body, stop_after_first_delta=True)
    if not any(n == "content_block_delta" for n, _ in events):
        raise Failed("no delta before the cancel")
    t0 = time.monotonic()
    after, _ = sse(port, bearer, {"model": model, "max_tokens": 64,
                                  "messages": [{"role": "user", "content": "Reply with the single word OK."}]})
    if not any(n == "message_stop" for n, _ in after):
        raise Failed("the turn after the cancel did not finish with message_stop")
    gone_rows: list[str] = []
    for _ in range(30):
        gone_rows = [r["outcome"] for r in perf_rows(perf) if r.get("outcome") in GONE][gone_before:]
        if gone_rows:
            break
        time.sleep(1)
    if not gone_rows:
        raise Failed("no client_abort / error:conn-reset perf row after the cancelled turn")
    time.sleep(2)
    return {"deltas_before_cancel": sum(1 for n, _ in events if n == "content_block_delta"),
            "next_turn_s": round(time.monotonic() - t0, 1), "next_turn_text": text_of(after)[:80],
            "head_outcome": gone_rows, "ollama_journal": ollama_cancelled(t_cancel)}


def check_tool_continuity(port: int, bearer: str, model: str) -> dict:
    user = {"role": "user", "content": "What is the weather in Lisbon right now? You must call get_weather."}
    first, _ = sse(port, bearer, {"model": model, "max_tokens": 512, "tools": [TOOL], "messages": [user]})
    uses = tool_use_blocks(first)
    if not uses or uses[0]["name"] != "get_weather":
        raise Failed(f"no get_weather tool_use in the first turn (stop={stop_reason(first)}, text={text_of(first)[:120]!r})")
    if stop_reason(first) != "tool_use":
        raise Failed(f"stop_reason {stop_reason(first)!r}, expected tool_use")
    use = uses[0]
    assistant = {"role": "assistant", "content": [{"type": "tool_use", "id": use["id"], "name": use["name"], "input": use["input"]}]}
    result = {"role": "user", "content": [{"type": "tool_result", "tool_use_id": use["id"],
                                           "content": "Lisbon: sunny, 22 degrees Celsius, light breeze."}]}
    second, _ = sse(port, bearer, {"model": model, "max_tokens": 512, "tools": [TOOL],
                                   "messages": [user, assistant, result]})
    text = text_of(second)
    if "22" not in text:
        raise Failed(f"second turn did not use the tool result: stop={stop_reason(second)}, text={text[:200]!r}")
    return {"tool_use_id": use["id"], "input": use["input"], "first_stop_reason": stop_reason(first),
            "second_stop_reason": stop_reason(second), "second_text": text[:160]}


def check_doctor(d: Daemon, good: str, bad: list[str], model: str, bad_rows: dict[str, str]) -> dict:
    proc = subprocess.run(["java", f"-Duser.home={d.home}", "-jar", str(d.jar), "doctor", "--json"],
                          env=d.env(), capture_output=True, text=True, timeout=120)
    if proc.returncode not in (0, 1):
        raise Failed(f"doctor --json rc={proc.returncode}: {proc.stderr[-500:]}")
    report = json.loads(proc.stdout)
    # rows are keyed "<section>/<check name>"; a local check's own name starts with "local:".
    local = {c["id"].split("/", 1)[1]: c for c in report["checks"] if "/local:" in c.get("id", "")}
    want_ok = [f"local:{good}", f"local:{good}/{model}"]
    want_fail = [f"local:{b}/{row}" for b, row in bad_rows.items()]
    for name in want_ok:
        if local.get(name, {}).get("status") != "ok":
            raise Failed(f"doctor {name}: {local.get(name)}")
    for name in want_fail:
        if local.get(name, {}).get("status") != "fail":
            raise Failed(f"doctor {name}: {local.get(name)}")
    summary = local[f"local:{good}"]["detail"]
    if "Ollama" not in summary or "local" not in f"local:{good}":
        raise Failed(f"doctor summary does not name the runtime: {summary}")
    return {name: {"status": c["status"], "detail": c["detail"]} for name, c in local.items()}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", required=True, type=Path)
    ap.add_argument("--config", required=True, type=Path)
    ap.add_argument("--home", required=True, type=Path)
    ap.add_argument("--control-port", type=int, default=3196)
    ap.add_argument("--ollama", default="http://localhost:11434")
    ap.add_argument("--good-head", default="ollama")
    ap.add_argument("--bad-heads", default="ollama-unlisted,ollama-overclaim")
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--keep-daemon", action="store_true")
    args = ap.parse_args()
    bad = [b for b in args.bad_heads.split(",") if b]
    topo = tomllib.loads(args.config.read_text())
    good_row = topo["providers"][args.good_head]["models"][0]
    bad_rows = {b: topo["providers"][b]["models"][0]["id"] for b in bad}

    d = Daemon(args.jar.resolve(), args.config.resolve(), args.home.resolve(), args.control_port)
    receipt = {"kind": "local-models-e2e", "at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
               "jar_sha256": hashlib.sha256(args.jar.read_bytes()).hexdigest(),
               "lm_studio": {"tested": False, "reason": "not installed on the reference machine"},
               "vllm": {"tested": False, "documented": "checks/local-models/README.md"}}
    try:
        receipt["ollama"] = runtime_facts(args.ollama, good_row["id"])
        declared = good_row["context_window"]
        served = receipt["ollama"]["served_context_length"]
        if declared != served:
            raise Failed(f"config declares {declared} for the good row; Ollama serves {served} — align the config")
        receipt["context_limit"] = {"declared": declared, "served": served,
                                    "card": receipt["ollama"]["card_context_length"]}
        print(f"runtime: {receipt['ollama']}")
        d.start()
        receipt["boot"] = check_boot(d, args.good_head, bad)
        print(f"boot: {receipt['boot']['up_line']}")
        port = receipt["boot"]["good_head"]["port"]
        bearer = d.mgmt_key()
        models = json.loads(urllib.request.urlopen(urllib.request.Request(
            f"http://127.0.0.1:{port}/v1/models", headers={"Authorization": f"Bearer {bearer}"}), timeout=10).read())
        model = models["data"][0]["id"]
        receipt["head_model"] = model
        receipt["streaming"] = check_streaming(port, bearer, model)
        print(f"streaming: {receipt['streaming']}")
        receipt["cancellation"] = check_cancellation(port, bearer, model, d.state / f"{args.good_head}-perf.jsonl")
        print(f"cancellation: {receipt['cancellation']}")
        receipt["tool_continuity"] = check_tool_continuity(port, bearer, model)
        print(f"tool continuity: {receipt['tool_continuity']}")
        receipt["doctor"] = check_doctor(d, args.good_head, bad, good_row["id"], bad_rows)
        print(f"doctor: {json.dumps(receipt['doctor'], indent=1)}")
    except Failed as e:
        print(f"E2E FAILED: {e}", file=sys.stderr)
        return 1
    finally:
        if not args.keep_daemon:
            d.stop()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(receipt, indent=2) + "\n")
    print(f"receipt -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
