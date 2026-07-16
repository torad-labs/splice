#!/usr/bin/env python3
"""manifest.py — the campaign-manifest CLI (torad-fleet, adapted from qgre 2026-07-02).

WHAT THIS IS: the ONE sanctioned channel for reading/updating
dev/campaigns/*.toml from orchestrators and agents. Replaces raw
whole-file reads (token cost: `get B2` returns ~1 item instead of the
whole file) and raw Edits (collision cost: concurrent agents hit
"file modified since read" constantly; this tool takes an flock and
appends atomically).

WHICH LAW + WHY: concept #945 (Campaign Orchestration Standard v2) —
the manifest is the memory; this makes touching it cheap, atomic, and
non-colliding. Text is edited LINE-BASED (never via a TOML serializer)
so comments — where the decisions and resume pointers live — survive
every operation; tomllib re-parses after each write as the
well-formedness gate (a failed parse rolls back).

WHO CALLS: the orchestrator's review loop and every subagent brief
("update the manifest AS you work" => `manifest.py note <id> "..."`).

TESTS/ORACLE: self-test via `manifest.py selftest` (runs get/list/note/
set-status/edit-fence/edit-verify/packet against a temp copy and
diff-checks the results).

USAGE:
  manifest.py list [--status S] [--phase P]     compact id|phase|status|title table
  manifest.py get <ID>                          the full item block incl. its notes
  manifest.py next                              campaign.next + all in_flight items
  manifest.py claim <ID> --session <sid> [--lease mem=12G,cpu=8,wall=20m,tier=B,gpu=24576]
                                                claim ownership, set in_flight, append CLAIM note
  manifest.py release-stale <ID> --by <sid>    clear dead ownership back to todo
  manifest.py handover <ID> --to <seat> --by <owner-seat>  a LIVE owner transfers the claim
  manifest.py set-status <ID> <todo|in_flight|done|verified> [--proof TOKEN]
  manifest.py proof-payload <ID> [--nonce N] [--issued-at ISO] [--key-id ID]
  manifest.py verify-signatures [--require-proof ID] [--strict]
  manifest.py backfill-signatures [--dry-run] [--private-key PATH]
  manifest.py note <ID> "text"                  append a dated # note to the item
  manifest.py gym-kpi                           read-only KPI instrument: verdict-coverage%,
                                                lineage-completeness%, trajectories-7d, honesty
                                                yield — the productization ratchet floor
  manifest.py verdict-backfill [--dry-run]      type the historical verdicts: closed items with
                                                prose markers gain orchestrator_verdict records
                                                flagged backfilled=true; markerless stay ungraded
  manifest.py lineage-backfill [--dry-run]      attribute historical arcs: CLAIM owner uuid ->
                                                that session's transcript -> the model at claim
                                                time, as policy_lineage_stamped backfilled=true
  manifest.py verdict <ID> --outcome accepted|redo|blocked [--gap TEXT] [--contradiction LOCUS] [--env-failure]
                                                #992-C verdict capture: dated VERDICT ledger note +
                                                typed orchestrator_verdict journal record + printed
                                                builder poke (record and poke are ONE action, #991)
  manifest.py events [--after-seq N] [--exclude-seat S] [--verbs v,..]  NOTIFY-1 read-only
                                                journal query (Monitor condition + drain);
                                                exit 0 = new actionable events, 1 = none
  manifest.py cost <ID> [--db PATH] [--pricing PATH] [--now ISO]
  manifest.py add --id X --phase P --title T --files F --verify V [--status todo]
  manifest.py edit-fence <ID> "f1, f2, ..."      replace the files fence + append FENCE-EDITED note
  manifest.py edit-verify <ID> "cmd"             replace the verify string + append VERIFY-EDITED note
  manifest.py add-law "text"                    append a dated # LAW line to the header banner
  manifest.py laws                              print LAW header lines (no explicit path: aggregated
                                                + deduped across ALL dev/campaigns/*.toml)
  manifest.py packet <ID>                         emit the computed packet for one item
  manifest.py dispatch <ID> --to <seat>         FAIL-CLOSED dispatch (#924): refuse an uncut ID,
                                                else record the dispatch + emit the packet
  manifest.py fence-check <ID> <pattern>        assert every tree file referencing the literal
                                                path <pattern> is covered by <ID>'s files= fence
  manifest.py amend-header "old" "new"          replace a substring in exactly ONE header banner line
  manifest.py set-next "ID1, ID2, ..."           replace the campaign.next queue (curation is the gate)
  manifest.py next-packet --session <sid>       self-serve dispatch: claim + emit the first eligible
                                                queued item, or refuse (review debt / queue exhausted)
  manifest.py selftest
Optional first arg: a path to a manifest (default dev/campaigns/kotlin-hardening.toml).
"""

from __future__ import annotations

import base64
import binascii
import fcntl
import hashlib
import importlib.util
import json
import os
import re
import secrets
import shlex
import socket
import stat
import subprocess
import shutil
import sys
import sqlite3
import tempfile
import time
import tomllib
from decimal import Decimal, ROUND_HALF_UP
from datetime import date, datetime, timedelta, timezone
from contextlib import redirect_stderr
from pathlib import Path

DEFAULT = os.path.join(os.path.dirname(__file__), "kotlin-hardening.toml")
DEFAULT_WORKSPACES_DB = os.path.expanduser("~/.openclaw/workspaces/workspaces.db")
DEFAULT_LITELLM_PRICING = os.path.expanduser("~/.openclaw/workspaces/litellm-pricing.json")
HDR = re.compile(r"^\[\[items?\]\]\s*$")
IDLINE = re.compile(r'^id\s*=\s*"([^"]+)"')
STATUSLINE = re.compile(r'^(status\s*=\s*")([a-z_]+)(".*)$')
STATUSES = {"todo", "in_flight", "done", "verified"}

# SELF-VERIFY-CLOSURE-BUILD: campaign-transition proofs reuse W9 Option B's
# `<payloadB64url>.<sigB64url>` Ed25519 wire contract and pinned public key. The
# purpose and exact payload shape keep an RL verdict token from being accepted as
# a ledger transition. Missing proofs remain legal until the signed backfill and
# hard-enforcement follow-up land; a present proof is always checked fail-closed.
CAMPAIGN_PROOF_VERSION = 1
CAMPAIGN_PROOF_PURPOSE = "torad.campaign.verified-transition"
CAMPAIGN_REQUIRE_PROOF_ENV = "TORAD_CAMPAIGN_REQUIRE_PROOF"
CAMPAIGN_PROOF_DEFAULT_KEY_ID = "7d212c67f2a1d27bee8abede500a9e25984bc52bf52dfde50990a6ee1fe1807b"
CAMPAIGN_PROOF_FIELD = "verification_proof"
CAMPAIGN_PROOF_PUBLIC_KEYS = {
    CAMPAIGN_PROOF_DEFAULT_KEY_ID: """-----BEGIN PUBLIC KEY-----
MCowBQYDK2VwAyEARuHmETBZFbtR16q3s6xIv/7Vig7yederFCjOwrFu6kM=
-----END PUBLIC KEY-----""",
}
CAMPAIGN_PROOF_REQUIRED_FIELDS = {
    "version",
    "purpose",
    "keyId",
    "ledger",
    "campaign",
    "itemId",
    "fromStatus",
    "toStatus",
    "itemSpecSha256",
    "reviewedArtifactSha256",
    "nonce",
    "issuedAt",
}
_ED25519_VERIFY_JS = r"""
const crypto = require("node:crypto");
const fs = require("node:fs");
try {
  const input = JSON.parse(fs.readFileSync(0, "utf8"));
  const ok = crypto.verify(
    null,
    Buffer.from(input.payloadB64, "utf8"),
    crypto.createPublicKey(input.publicKeyPem),
    Buffer.from(input.signatureB64, "base64url"),
  );
  process.exit(ok ? 0 : 1);
} catch (_) {
  process.exit(2);
}
"""
_ED25519_BATCH_SIGN_JS = r"""
const crypto = require("node:crypto");
const fs = require("node:fs");
try {
  const input = JSON.parse(fs.readFileSync(0, "utf8"));
  const privateKey = crypto.createPrivateKey(fs.readFileSync(input.privateKeyPath, "utf8"));
  const publicKey = crypto.createPublicKey(privateKey);
  const publicDer = publicKey.export({ type: "spki", format: "der" });
  const keyId = crypto.createHash("sha256").update(publicDer).digest("hex");
  const tokens = input.payloads.map((payloadB64) => {
    const data = Buffer.from(payloadB64, "utf8");
    const signature = crypto.sign(null, data, privateKey);
    if (!crypto.verify(null, data, publicKey, signature)) throw new Error("self-verification failed");
    return `${payloadB64}.${signature.toString("base64url")}`;
  });
  process.stdout.write(JSON.stringify({ keyId, tokens }));
} catch (error) {
  process.stderr.write(String(error && error.message ? error.message : error));
  process.exit(2);
}
"""


class _CampaignProofRefusal(Exception):
    """A strict-mode verified transition refused while holding the manifest flock."""


def _campaign_proof_required() -> bool:
    return os.environ.get(CAMPAIGN_REQUIRE_PROOF_ENV) == "1"


def _read(path: str) -> list[str]:
    with open(path, encoding="utf-8") as f:
        return f.readlines()


def _blocks(lines: list[str]):
    """Yield (item_id, header_idx, end_idx) — end is the next [[item*]]/section or EOF.
    Trailing # comment notes between items belong to the PRECEDING item."""
    starts = [i for i, l in enumerate(lines) if HDR.match(l)]
    for n, s in enumerate(starts):
        end = starts[n + 1] if n + 1 < len(starts) else len(lines)
        # stop at a new [section] header too (e.g. [baselines])
        for j in range(s + 1, end):
            if re.match(r"^\[[a-z]", lines[j]):
                end = j
                break
        item_id = None
        for j in range(s + 1, min(s + 4, end)):
            m = IDLINE.match(lines[j])
            if m:
                item_id = m.group(1)
                break
        if item_id:
            yield item_id, s, end


def _find(lines: list[str], item_id: str):
    for iid, s, e in _blocks(lines):
        if iid == item_id:
            return s, e
    sys.exit(f"error: item {item_id!r} not found")


def _campaign_item_data(block_lines: list[str]) -> dict:
    """Parse one [[items]] block without making comments part of the signed slot."""
    try:
        parsed = tomllib.loads("".join(block_lines))
        items = parsed.get("items") or parsed.get("item")
        if not isinstance(items, list) or len(items) != 1 or not isinstance(items[0], dict):
            raise ValueError("expected exactly one item table")
        return items[0]
    except (tomllib.TOMLDecodeError, ValueError) as exc:
        raise ValueError(f"invalid campaign item block: {exc}") from exc


def _campaign_item_spec_sha256(block_lines: list[str], *, files_override: list[str] | None = None) -> str:
    item = _campaign_item_data(block_lines)
    spec = {
        "id": item.get("id"),
        "phase": item.get("phase"),
        "title": item.get("title"),
        "files": item.get("files") if files_override is None else files_override,
        "verify": item.get("verify"),
    }
    encoded = json.dumps(spec, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _normalized_campaign_ledger_path(path: str) -> str:
    resolved = Path(path).resolve(strict=False)
    repo = _git_repo_root(path)
    if repo:
        try:
            return resolved.relative_to(Path(repo).resolve(strict=False)).as_posix()
        except ValueError:
            pass
    return resolved.as_posix()


def _reviewed_artifact_sha256(
    path: str,
    block_lines: list[str],
    *,
    allow_legacy_missing_attribution: bool = False,
) -> str:
    """Bind a proof to the attribution evidence the orchestrator actually reviewed.

    The existing ATTEST notes are the durable campaign-side artifact reference. The
    sidecar path is already part of that signed text, but the ignored scratch sidecar's
    bytes are deliberately not required later: proofs must remain verifiable in clones
    of all three projects where `.claude/ledger-diffs` is not distributed.
    """
    evidence = []
    for line in block_lines:
        stripped = line.strip()
        if " ATTEST-START:" in stripped or "] ATTEST:" in stripped:
            evidence.append(stripped)
    if not evidence:
        if not allow_legacy_missing_attribution:
            raise ValueError("campaign proof requires durable ATTEST attribution evidence")
        evidence = ["LEGACY-BASELINE:NO-DURABLE-ATTEST"]
    artifact = {"attribution": evidence}
    encoded = json.dumps(artifact, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _campaign_transition_payload(
    path: str,
    item_id: str,
    block_lines: list[str],
    *,
    nonce: str,
    issued_at: str,
    key_id: str,
    item_spec_files: list[str] | None = None,
    allow_legacy_missing_attribution: bool = False,
) -> dict:
    return {
        "version": CAMPAIGN_PROOF_VERSION,
        "purpose": CAMPAIGN_PROOF_PURPOSE,
        "keyId": key_id,
        "ledger": _normalized_campaign_ledger_path(path),
        "campaign": Path(path).stem,
        "itemId": item_id,
        "fromStatus": "done",
        "toStatus": "verified",
        "itemSpecSha256": _campaign_item_spec_sha256(block_lines, files_override=item_spec_files),
        "reviewedArtifactSha256": _reviewed_artifact_sha256(
            path,
            block_lines,
            allow_legacy_missing_attribution=allow_legacy_missing_attribution,
        ),
        "nonce": nonce,
        "issuedAt": issued_at,
    }


def _base64url_no_padding(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def _decode_base64url(value: str) -> bytes:
    if not isinstance(value, str) or not value or "=" in value:
        raise ValueError("base64url value must be non-empty and unpadded")
    padded = value + ("=" * (-len(value) % 4))
    try:
        decoded = base64.b64decode(padded, altchars=b"-_", validate=True)
    except (ValueError, binascii.Error) as exc:
        raise ValueError("malformed base64url value") from exc
    if _base64url_no_padding(decoded) != value:
        raise ValueError("non-canonical base64url value")
    return decoded


def _ed25519_signature_verifies(payload_b64: str, signature_b64: str, public_key_pem: str) -> bool:
    request = json.dumps(
        {"payloadB64": payload_b64, "signatureB64": signature_b64, "publicKeyPem": public_key_pem}
    )
    try:
        proc = subprocess.run(
            ["node", "-e", _ED25519_VERIFY_JS],
            input=request,
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError):
        return False
    return proc.returncode == 0


def _signed_campaign_tokens(
    payloads_b64: list[str],
    private_key_path: str,
    *,
    expected_key_id: str,
) -> list[str]:
    key_path = Path(private_key_path).expanduser().resolve(strict=False)
    if not key_path.is_file():
        raise ValueError("caller-supplied campaign signing key does not exist")
    if stat.S_IMODE(key_path.stat().st_mode) & 0o077:
        raise ValueError("campaign signing key must not be accessible by group/other (expected mode 0600)")
    request = json.dumps({"payloads": payloads_b64, "privateKeyPath": str(key_path)})
    try:
        proc = subprocess.run(
            ["node", "-e", _ED25519_BATCH_SIGN_JS],
            input=request,
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError) as exc:
        raise ValueError("campaign proof signer is unavailable") from exc
    if proc.returncode != 0:
        raise ValueError(f"campaign proof signing failed: {(proc.stderr or 'unknown error').strip()}")
    try:
        result = json.loads(proc.stdout)
    except json.JSONDecodeError as exc:
        raise ValueError("campaign proof signer returned malformed output") from exc
    if result.get("keyId") != expected_key_id:
        raise ValueError("caller-supplied private key does not match the pinned campaign proof keyId")
    tokens = result.get("tokens")
    if not isinstance(tokens, list) or len(tokens) != len(payloads_b64) or not all(isinstance(v, str) for v in tokens):
        raise ValueError("campaign proof signer returned the wrong token count or shape")
    return tokens


def _validated_campaign_transition_proof(
    path: str,
    item_id: str,
    block_lines: list[str],
    proof: str,
    *,
    required_status: str,
    public_keys: dict[str, str] | None = None,
    signature_preverified: bool = False,
) -> dict:
    if not isinstance(proof, str) or proof.count(".") != 1:
        raise ValueError("campaign proof must be <payloadB64url>.<sigB64url>")
    payload_b64, signature_b64 = proof.split(".", 1)
    try:
        payload = json.loads(_decode_base64url(payload_b64).decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        raise ValueError(f"campaign proof payload is malformed: {exc}") from exc
    if not isinstance(payload, dict) or set(payload) != CAMPAIGN_PROOF_REQUIRED_FIELDS:
        raise ValueError("campaign proof payload fields do not match the v1 envelope")
    if payload.get("version") != CAMPAIGN_PROOF_VERSION or payload.get("purpose") != CAMPAIGN_PROOF_PURPOSE:
        raise ValueError("campaign proof version or purpose is not supported")
    for field in CAMPAIGN_PROOF_REQUIRED_FIELDS - {"version"}:
        if not isinstance(payload.get(field), str) or not payload[field]:
            raise ValueError(f"campaign proof field {field} must be a non-empty string")
    if len(payload["nonce"]) < 16:
        raise ValueError("campaign proof nonce is too short")
    try:
        signature = _decode_base64url(signature_b64)
    except ValueError as exc:
        raise ValueError(f"campaign proof signature is malformed: {exc}") from exc
    if len(signature) != 64:
        raise ValueError("campaign proof Ed25519 signature must be 64 bytes")
    try:
        issued_at = datetime.fromisoformat(payload["issuedAt"].replace("Z", "+00:00"))
    except ValueError as exc:
        raise ValueError("campaign proof issuedAt is not an ISO-8601 timestamp") from exc
    if issued_at.tzinfo is None:
        raise ValueError("campaign proof issuedAt must include a timezone")

    keyring = CAMPAIGN_PROOF_PUBLIC_KEYS if public_keys is None else public_keys
    public_key = keyring.get(payload["keyId"])
    if not public_key:
        raise ValueError(f"unknown campaign proof keyId: {payload['keyId']}")
    if not signature_preverified and not _ed25519_signature_verifies(payload_b64, signature_b64, public_key):
        raise ValueError("campaign proof Ed25519 signature is invalid")

    item = _campaign_item_data(block_lines)
    if item.get("status") != required_status:
        raise ValueError(f"campaign proof requires item status {required_status!r}")
    final_files = None
    if required_status == "done":
        candidate = _attested_scoped_files(path, item_id)
        current_files = item.get("files")
        if candidate and isinstance(current_files, list) and sorted(candidate) != sorted(current_files):
            final_files = candidate
    expected = _campaign_transition_payload(
        path,
        item_id,
        block_lines,
        nonce=payload["nonce"],
        issued_at=payload["issuedAt"],
        key_id=payload["keyId"],
        item_spec_files=final_files,
        allow_legacy_missing_attribution=required_status == "verified",
    )
    if payload != expected:
        raise ValueError("campaign proof bindings do not match the locked ledger item")
    return payload


def _upsert_item_string_field(lines: list[str], s: int, e: int, field: str, value: str) -> list[str]:
    encoded = json.dumps(value, ensure_ascii=False)
    for j in range(s, e):
        lhs, sep, _rhs = lines[j].partition("=")
        if sep and lhs.strip() == field:
            lines[j] = f"{field} = {encoded}\n"
            return lines
    for j in range(s, e):
        if STATUSLINE.match(lines[j]):
            lines.insert(j + 1, f"{field} = {encoded}\n")
            return lines
    raise ValueError("item has no status line for proof persistence")


def campaign_signature_report(
    path: str,
    *,
    public_keys: dict[str, str] | None = None,
    require_proof_item: str | None = None,
) -> dict:
    lines = _read(path)
    valid = 0
    unsigned_verified = 0
    errors = []
    found_required = require_proof_item is None
    for item_id, s, e in _blocks(lines):
        block = lines[s:e]
        try:
            item = _campaign_item_data(block)
        except ValueError as exc:
            errors.append(f"{item_id}: {exc}")
            continue
        if item_id == require_proof_item:
            found_required = True
        proof = item.get(CAMPAIGN_PROOF_FIELD)
        if proof is None:
            if item.get("status") == "verified":
                unsigned_verified += 1
            if item_id == require_proof_item:
                errors.append(f"{item_id}: required transition proof is missing")
            continue
        if not isinstance(proof, str):
            errors.append(f"{item_id}: {CAMPAIGN_PROOF_FIELD} must be a string")
            continue
        try:
            _validated_campaign_transition_proof(
                path,
                item_id,
                block,
                proof,
                required_status="verified",
                public_keys=public_keys,
            )
            valid += 1
        except ValueError as exc:
            errors.append(f"{item_id}: {exc}")
    if not found_required:
        errors.append(f"{require_proof_item}: item not found")
    return {"valid": valid, "unsigned_verified": unsigned_verified, "errors": errors}


def cmd_proof_payload(
    path: str,
    item_id: str,
    *,
    nonce: str | None = None,
    issued_at: str | None = None,
    key_id: str = CAMPAIGN_PROOF_DEFAULT_KEY_ID,
):
    lines = _read(path)
    s, e = _find(lines, item_id)
    block = lines[s:e]
    item = _campaign_item_data(block)
    if item.get("status") != "done":
        sys.exit("error: a campaign transition proof can only be prepared from status 'done'")
    payload = _campaign_transition_payload(
        path,
        item_id,
        block,
        nonce=nonce or secrets.token_hex(16),
        issued_at=issued_at or datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        key_id=key_id,
        item_spec_files=(
            candidate
            if (candidate := _attested_scoped_files(path, item_id))
            and isinstance(item.get("files"), list)
            and sorted(candidate) != sorted(item["files"])
            else None
        ),
    )
    raw = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    print(_base64url_no_padding(raw))


def cmd_verify_signatures(
    path: str,
    *,
    require_proof_item: str | None = None,
    strict: bool = False,
    _public_keys: dict[str, str] | None = None,
):
    report = campaign_signature_report(
        path,
        require_proof_item=require_proof_item,
        public_keys=_public_keys,
    )
    for error in report["errors"]:
        print(f"INVALID campaign proof: {error}", file=sys.stderr)
    if strict and report["unsigned_verified"]:
        print(
            f"INVALID campaign proof: {report['unsigned_verified']} verified item(s) have no proof",
            file=sys.stderr,
        )
    if report["errors"] or (strict and report["unsigned_verified"]):
        raise SystemExit(1)
    print(
        f"OK campaign signatures{' STRICT' if strict else ''}: {report['valid']} valid proof(s); "
        f"{report['unsigned_verified']} unsigned verified item(s)"
        + ("" if strict else " (report-only until backfill)")
    )


def cmd_backfill_signatures(
    path: str,
    *,
    private_key_path: str | None = None,
    dry_run: bool = False,
    _public_keys: dict[str, str] | None = None,
    _key_id: str = CAMPAIGN_PROOF_DEFAULT_KEY_ID,
    _issued_at: str | None = None,
) -> dict:
    """Add proofs to existing verified items; never changes status or overwrites a proof."""
    if not dry_run and not private_key_path:
        sys.exit("error: backfill-signatures requires --private-key PATH unless --dry-run is used")
    summary = {"candidates": [], "added": 0, "skipped_proofed": 0}

    def mutate(lines):
        candidates = []
        for item_id, s, e in _blocks(lines):
            item = _campaign_item_data(lines[s:e])
            if item.get("status") != "verified":
                continue
            if item.get(CAMPAIGN_PROOF_FIELD) is not None:
                summary["skipped_proofed"] += 1
                continue
            candidates.append((item_id, list(lines[s:e])))
        summary["candidates"] = [item_id for item_id, _block in candidates]
        if dry_run or not candidates:
            return lines
        issued_at = _issued_at or datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        payloads_b64 = []
        for item_id, block in candidates:
            payload = _campaign_transition_payload(
                path,
                item_id,
                block,
                nonce=secrets.token_hex(16),
                issued_at=issued_at,
                key_id=_key_id,
                allow_legacy_missing_attribution=True,
            )
            raw = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
            payloads_b64.append(_base64url_no_padding(raw))
        try:
            tokens = _signed_campaign_tokens(
                payloads_b64,
                private_key_path,
                expected_key_id=_key_id,
            )
            keyring = CAMPAIGN_PROOF_PUBLIC_KEYS if _public_keys is None else _public_keys
            for (item_id, block), token in zip(candidates, tokens, strict=True):
                _validated_campaign_transition_proof(
                    path,
                    item_id,
                    block,
                    token,
                    required_status="verified",
                    public_keys=keyring,
                    signature_preverified=True,
                )
        except ValueError as exc:
            sys.exit(f"error: signed baseline backfill aborted: {exc}")

        for (item_id, _block), token in zip(candidates, tokens, strict=True):
            s, e = _find(lines, item_id)
            lines = _upsert_item_string_field(lines, s, e, CAMPAIGN_PROOF_FIELD, token)
        summary["added"] = len(tokens)
        return lines

    _locked_rewrite(path, mutate)
    ledger_name = _normalized_campaign_ledger_path(path)
    if dry_run:
        for item_id in summary["candidates"]:
            print(f"WOULD SIGN {ledger_name}:{item_id}")
        print(
            f"DRY RUN signed baseline: {len(summary['candidates'])} candidate(s); "
            f"{summary['skipped_proofed']} already proofed"
        )
    else:
        print(
            f"SIGNED BASELINE {ledger_name}: {summary['added']} proof(s) added; "
            f"{summary['skipped_proofed']} already proofed"
        )
    return summary


def _block_owner(lines: list[str], s: int, e: int) -> str | None:
    owner = None
    for line in lines[s:e]:
        if line.lstrip().startswith("#") and "CLAIM: owner=" in line:
            tail = line.partition("owner=")[2].split()
            owner = tail[0] if tail else None
        elif line.lstrip().startswith("#") and "CLAIM-RELEASED" in line:
            owner = None
    return owner


def _block_status(lines: list[str], s: int, e: int) -> str | None:
    for line in lines[s:e]:
        if m := STATUSLINE.match(line):
            return m.group(2)
    return None


def _owned_owner(lines: list[str], s: int, e: int) -> str | None:
    if _block_status(lines, s, e) != "in_flight":
        return None
    return _block_owner(lines, s, e)


# G-retired-guard (D13: structured grammar over prose). The PRIMARY signal is a structured
# note-head mirroring the CLAIM:/CLAIM-RELEASED grammar: `# [date] RETIRED: <reason>` retires,
# `# [date] RETIRE-LIFTED: <reason>` un-retires, and the LAST structured marker wins — giving
# retirement the un-retire path the old text scan never had (P6-B3b's revisit needed
# --override-retired precisely because a prose mention latched the item shut permanently).
# The legacy free-text regex stays as a BLOCKING FLOOR only when no structured marker exists,
# so pre-grammar items (P6-B3b-class prose rulings) remain protected unmigrated. Both markers
# are written through the normal note verb: manifest.py note <ID> "RETIRED: <reason>".
_RETIREMENT_MARKER_RE = re.compile(r"\bRETIRED\b|do not re-?queue", re.IGNORECASE)
_STRUCTURED_RETIRE_RE = re.compile(r"^#\s*(?:\[[^\]]*\]\s*)?(RETIRED|RETIRE-LIFTED):")


def _retirement_marker(lines: list[str], s: int, e: int) -> str | None:
    """The item's operative retirement marker, or None if the item is live.

    Last structured marker (RETIRED:/RETIRE-LIFTED:) wins; the legacy free-text scan is
    consulted only when NO structured marker exists in the block (back-compat floor)."""
    structured_state: str | None = None
    structured_line: str | None = None
    legacy_line: str | None = None
    for line in lines[s:e]:
        stripped = line.strip()
        if not stripped.startswith("#"):
            continue
        m = _STRUCTURED_RETIRE_RE.match(stripped)
        if m:
            structured_state = m.group(1)
            structured_line = stripped
            continue
        if legacy_line is None and _RETIREMENT_MARKER_RE.search(stripped):
            legacy_line = stripped
    if structured_state == "RETIRED":
        return structured_line
    if structured_state == "RETIRE-LIFTED":
        return None
    return legacy_line


def _retirement_block_error(item_id: str, marker: str) -> str:
    return (
        f"error: item {item_id!r} carries a RETIREMENT marker in its own notes — refusing to "
        f"proceed without an explicit override.\n  {marker}\n"
        f"Read the full ruling first: python3 dev/campaigns/manifest.py get {item_id}\n"
        f"If this is a genuine, fresh, operator-approved re-queue, pass --override-retired —\n"
        f"or, for a durable un-retire, record the lift structurally:\n"
        f'  python3 dev/campaigns/manifest.py note {item_id} "RETIRE-LIFTED: <operator ruling>"'
    )


def _append_block_lines(lines: list[str], s: int, e: int, body: list[str]):
    j = e
    while j > s and lines[j - 1].strip() == "":
        j -= 1
    return lines[:j] + body + lines[j:]


def _rewrite_status(lines: list[str], s: int, e: int, new_status: str):
    for j in range(s, e):
        if m := STATUSLINE.match(lines[j]):
            lines[j] = f"{m.group(1)}{new_status}{m.group(3)}\n"
            return
    sys.exit("error: item has no status line")


def _utc_iso_seconds() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


IDENTITY_RE = re.compile(r"^[A-Za-z0-9._@:-]+$")


def _validate_identity(value: str, label: str) -> str:
    """REV2-F1: every --session/--seat/--to/--by value is spliced verbatim into a ledger
    comment line (line-based TOML edit, never a serializer, per concept #945) — a newline
    lets it forge an arbitrary [[items]] block (e.g. a fabricated status="verified" entry).
    Reject anything but a plain identity token BEFORE the caller does anything else with it,
    so no downstream path (tmux resolution, note building, pointer writes) ever sees a raw
    hostile value."""
    if not IDENTITY_RE.match(value):
        sys.exit(f"error: {label} must match {IDENTITY_RE.pattern} (rejected: {value!r})")
    return value


def _claim_note(
    session_id: str,
    supersedes: str | None = None,
    requested: str | None = None,
) -> list[str]:
    note = f"# [{date.today().isoformat()}] CLAIM: owner={session_id} at={_utc_iso_seconds()}"
    if supersedes:
        note += f" supersedes={supersedes}"
    if requested and requested != session_id:
        note += f" (requested: {requested})"
    return [note + "\n"]


def _handover_note(to_seat: str, by_seat: str) -> list[str]:
    """A voluntary, LIVE-owner transfer — distinct from _claim_note's supersedes= (a stale
    takeover): still a 'CLAIM: owner=' line so _block_owner's existing last-claim-wins scan
    picks it up with no changes there, just a different trailing field name."""
    note = f"# [{date.today().isoformat()}] CLAIM: owner={to_seat} at={_utc_iso_seconds()} handover-from={by_seat}"
    return [note + "\n"]


def _released_note(owner: str, by: str) -> list[str]:
    return [
        f"# [{date.today().isoformat()}] CLAIM-RELEASED (stale): owner={owner} dead per registry+tmux, by={by}\n"
    ]


# PORTED from /home/<user>/Documents/dev/apps/kandi/kandi-main/vulkan-inference/silicon/tools/backlog.py
# on 2026-07-05; shapes only: snapshot_tree/scope_matches/bracket_paths/diff_for_paths/
# diffstat_for_paths/done_attribution.
ATTR_START_NOTE = "ATTEST-START"
ATTR_NOTE = "ATTEST"
ATTR_DIFF_DIR = Path(".claude") / "ledger-diffs"
ATTR_DIFFSTAT_LINES = 12
ATTR_EXCLUDED_PATHS = (".claude/ledger-diffs/", ".claude/state/", "dev/campaigns/traces/")
HEX_DIGITS = set("0123456789abcdef")


def _git_repo_root(manifest_path: str) -> Path | None:
    manifest_dir = Path(manifest_path).resolve(strict=False).parent
    try:
        proc = subprocess.run(
            ["git", "-C", str(manifest_dir), "rev-parse", "--show-toplevel"],
            capture_output=True,
            text=True,
            check=False,
        )
    except (FileNotFoundError, OSError):
        return None
    if proc.returncode != 0:
        return None
    root = proc.stdout.strip()
    return Path(root).resolve(strict=False) if root else None


_SEATD_PROBE_TIMEOUT_S = 1.0


def _seatd_fleet_root() -> Path:
    override = os.environ.get("TORAD_FLEET_ROOT")
    return Path(override) if override else Path.home() / ".torad"


def _seatd_send_frame(sock: socket.socket, payload: dict) -> None:
    body = json.dumps(payload).encode("utf-8")
    sock.sendall(len(body).to_bytes(4, "big") + body)


def _seatd_recv_exact(sock: socket.socket, count: int) -> bytes:
    chunks = bytearray()
    while len(chunks) < count:
        chunk = sock.recv(count - len(chunks))
        if not chunk:
            raise ConnectionError("seatd socket closed before a complete frame arrived")
        chunks.extend(chunk)
    return bytes(chunks)


def _seatd_read_frame(sock: socket.socket) -> dict:
    length = int.from_bytes(_seatd_recv_exact(sock, 4), "big")
    parsed = json.loads(_seatd_recv_exact(sock, length).decode("utf-8"))
    if not isinstance(parsed, dict):
        raise ValueError(f"seatd frame body was not a JSON object: {parsed!r}")
    return parsed


def _seatd_alive_probe(seat: str, socket_path: Path, token: str) -> bool:
    """The C1 alive(seat) probe (SEATD-CONTRACT.md C1 wire format: 4-byte big-endian length
    prefix + UTF-8 JSON body). Any failure (connect/timeout/auth-denied/malformed response)
    raises — the caller treats ANY exception here as "could not verify," never as "confirmed
    dead" or "confirmed alive". Vendored from .claude/hooks/seat_role.py (manifest.py stays
    standalone and does not import across trees)."""
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
        sock.settimeout(_SEATD_PROBE_TIMEOUT_S)
        sock.connect(str(socket_path))
        _seatd_send_frame(sock, {"token": token})
        auth_response = _seatd_read_frame(sock)
        if not auth_response.get("ok"):
            raise ValueError(f"seatd auth denied: {auth_response.get('reason')}")
        _seatd_send_frame(sock, {"verb": "alive", "args": {"seat": seat}})
        response = _seatd_read_frame(sock)
        if not response.get("ok"):
            raise ValueError(f"seatd alive({seat!r}) denied: {response.get('reason')}")
        result = response.get("result")
        if not isinstance(result, dict) or not isinstance(result.get("alive"), bool):
            raise ValueError(f"malformed alive() result: {result!r}")
        return result["alive"]


def _seatd_verified_seat() -> str | None:
    """S3-2: TORAD_SEAT (set by seatd's own spawn — SeatLifecycle.kt's seatEnv, same trust class
    as $TMUX for the tmux path: infrastructure-set at spawn time, not a caller-supplied string)
    is VERIFIED via seatd's alive(seat) before being trusted as this session's canonical seat —
    c2-schema.toml's own alive() exception is explicitly rationalized for "seat_role resolution"
    (read directly from the frozen schema, not guessed). Returns the verified name, or None if
    the seatd path doesn't apply here (no TORAD_SEAT/TORAD_SEAT_TOKEN/seatd.sock) OR the probe
    disagrees/fails for any reason — fail CLOSED means never trusting an unverified self-claimed
    name, falling through to the tmux path exactly as before this item."""
    seat = os.environ.get("TORAD_SEAT")
    token = os.environ.get("TORAD_SEAT_TOKEN")
    if not seat or not token:
        return None
    socket_path = _seatd_fleet_root() / "seatd.sock"
    if not socket_path.exists():
        return None
    try:
        alive = _seatd_alive_probe(seat, socket_path, token)
    except Exception:
        return None
    return seat if alive else None


def _canonical_seat() -> str | None:
    seatd_seat = _seatd_verified_seat()
    if seatd_seat is not None:
        return seatd_seat
    # Vendored from .claude/hooks/seat_role.py::_session_seat (same 3s timeout and fail-soft
    # None behavior; manifest.py stays standalone and does not import across trees).
    if not os.environ.get("TMUX"):
        return None
    try:
        # Production seat self-identification on the REAL fleet server (ledger attribution),
        # never a test sandbox. Reviewed exemption (CR-RULES-EXCLUDE-SCOPE).
        # ast-grep-ignore: tmux-test-socket-isolation-python
        proc = subprocess.run(
            ["tmux", "display-message", "-p", "#S"],
            timeout=3,
            capture_output=True,
            text=True,
            check=False,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError):
        return None
    if proc.returncode != 0:
        return None
    seat = proc.stdout.strip()
    return seat or None


MACHINE_ID_RE = re.compile(r"^[0-9a-f]{8}$")
_QUALIFIER_SEP = "~"  # IDENTITY_RE (--session/--seat/--to/--by) excludes '~' — never collides
                       # with a caller-supplied token, so a single partition() is unambiguous.


def _machine_id_path(_path: Path | str | None = None) -> Path:
    return Path(_path) if _path is not None else Path.home() / ".torad" / "machine-id"


def _machine_id(_path: Path | str | None = None) -> str:
    """G30: an 8-hex substrate identifier, generated ONCE on first need (no flag, no env var
    in the normal path) and reused forever after — the qualifier that makes a bare seat name
    (unique per tmux server today) globally unambiguous once the same git-shared ledger is
    claimed from a second machine or tmux server. _path is test-injectable so a two-machine
    fixture can give each simulated machine its own file without touching the real one."""
    path = _machine_id_path(_path)
    try:
        existing = path.read_text(encoding="utf-8").strip()
    except OSError:
        existing = ""
    if MACHINE_ID_RE.match(existing):
        return existing
    new_id = secrets.token_hex(4)
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_name(f".{path.name}.tmp.{os.getpid()}")
        tmp.write_text(new_id + "\n", encoding="utf-8")
        os.replace(tmp, path)
    except OSError:
        pass
    return new_id


def _qualify_seat(seat: str, machine_id: str) -> str:
    return f"{seat}{_QUALIFIER_SEP}{machine_id}"


def _split_qualified(identity: str) -> tuple[str, str | None]:
    """The inverse of _qualify_seat — (seat, machine_id) for a qualified identity, or
    (identity, None) unchanged for an old-format/unqualified one (no '~' present). Backward
    compatible by construction: a claim recorded before this item shipped still self-matches
    and still resolves via the existing bare-name registry/tmux path."""
    seat, sep, machine_id = identity.partition(_QUALIFIER_SEP)
    return (seat, machine_id) if sep else (identity, None)


def _display_identity(identity: str) -> str:
    """G30: 'note/display contexts strip the qualifier for readability' — the machine hash is
    plumbing, not something an operator needs to read in a peer-fence listing or `list` row."""
    return _split_qualified(identity)[0]


def _claim_identity(
    session_id: str, _canonical: str | None = None, _machine_id_value: str | None = None
) -> tuple[str, str | None, str]:
    """Returns (identity_for_record_and_compare, requested_session_if_different, bare_seat).
    identity_for_record_and_compare is qualified (seat~machine-id) whenever a canonical tmux
    seat resolves — RECORDING and OWNERSHIP COMPARISON both use it, so a same-named seat on a
    different machine (a git-shared ledger, a second tmux server) never collides with a claim
    made here. bare_seat is the UNqualified seat/session name — the only form the LOCAL
    registry/pointer machinery (_write_pointer/_resolve_pointer_session_id) understands, since
    the registry stays per-machine local by design (G30 point 4). _canonical/_machine_id_value
    are test-injectable, mirroring the _alive/_registry_dir convention elsewhere in this file."""
    canonical = _canonical_seat() if _canonical is None else _canonical
    if canonical is None:
        return session_id, None, session_id
    machine_id = _machine_id() if _machine_id_value is None else _machine_id_value
    qualified = _qualify_seat(canonical, machine_id)
    if qualified == session_id:
        return qualified, None, canonical
    return qualified, session_id, canonical


def _qualified_recipient(
    seat: str, _canonical: str | None = None, _machine_id_value: str | None = None
) -> str:
    """A handover's --to recipient lives on the SAME local tmux server as the caller (a
    live-owner-initiated, local action) — qualify it with the CALLER's own machine-id exactly
    when canonical-seat resolution is active, mirroring _claim_identity's own gate, so a
    handed-over item's new owner compares identically to a directly-claimed one. Unqualified
    (bare seat, unchanged) when no canonical seat resolves — same gate, same reason."""
    canonical = _canonical_seat() if _canonical is None else _canonical
    if canonical is None:
        return seat
    machine_id = _machine_id() if _machine_id_value is None else _machine_id_value
    return _qualify_seat(seat, machine_id)


def _handover_mismatch_error(item_id: str, owner: str, by_identity: str, requested: str) -> str:
    return (
        f"error: item {item_id} is owned by {owner}; you are {by_identity} "
        f"(requested: {requested}) — handover must be run by the owner seat; "
        f"if the owner is dead use: manifest.py release-stale {item_id} --by <your seat>"
    )


def _release_stale_mismatch_error(item_id: str, owner: str, by_identity: str, requested: str) -> str:
    return (
        f"error: item {item_id} is owned by {owner}; you are {by_identity} "
        f"(requested: {requested}) — release-stale must be run by the owner seat; "
        f"if the owner is dead use: manifest.py release-stale {item_id} --by <your seat>"
    )


NOTIFY_OFF_ENV = "TORAD_LEDGER_NOTIFY"
NOTIFY_ROLE_ENV = "TORAD_SESSION_ROLE"


def _is_canonical_ledger(path: str, _this_file: str | None = None) -> bool:
    """Cross-fleet contract (kandi 09ae618f, adopted here as the primary poke gate): the
    poke fires ONLY when the written ledger lives in the CLI's own dev/campaigns dir — both
    comparison sides (the ledger path and this script's own path) travel together, so there is
    no cwd/env/config to get out of sync. This mutes scratch/selftest ledgers BY CONSTRUCTION,
    stronger than (and independent of) the env-flag/role suppressions below."""
    this_file = Path(__file__) if _this_file is None else Path(_this_file)
    return Path(path).resolve().parent == this_file.resolve().parent


def _should_poke_orchestrator() -> bool:
    """Self-poke + test suppression: an orchestrator's own flip never pokes itself, and
    TORAD_LEDGER_NOTIFY=off is the escape hatch selftest uses so it never poke a real seat."""
    if os.environ.get(NOTIFY_OFF_ENV) == "off":
        return False
    return os.environ.get(NOTIFY_ROLE_ENV) != "orchestrator"


def _notify_orchestrator(path: str, message: str) -> None:
    """The landing poke: fire-and-forget scripts/notify_orchestrator.py, which is
    itself exit-0-always (NOTIFY-1 contract) — a missing repo/script just means
    silently skip.

    G43: cwd=repo is load-bearing, not decorative — notify_orchestrator.py's own
    _project_root() runs `git rev-parse --show-toplevel` relative to ITS OWN cwd (inherited
    from whatever process spawns it), not relative to its file location. Without cwd= here,
    a manifest.py invocation from a FOREIGN cwd (any directory outside this repo) hands the
    notifier a cwd it doesn't control, so _project_root() resolves to the WRONG repo (or
    Path.cwd()'s own fallback) — the registry lookup then matches no session for the real
    project and the poke silently vanishes. Same gap G42 already fixed for
    07_enforcement_layer_ack.py's own notify call (cwd=root there); this is the SAME fix,
    reusing `repo` (already resolved via _git_repo_root(path) above) rather than
    re-deriving it."""
    repo = _git_repo_root(path)
    if repo is None:
        return
    script = repo / "scripts" / "notify_orchestrator.py"
    if not script.is_file():
        return
    try:
        subprocess.run([str(script), message], cwd=repo, timeout=5, check=False)
    except (OSError, subprocess.TimeoutExpired):
        pass


# S3-4 (seat-substrate campaign): manifest.py's claim/note/set-status/handover verbs emit C4
# substrate-journal lines — the Gym B environment surface starts existing here. manifest.py is
# standalone Python and does not import across trees (S0-4/S0-5's FleetRoot.kt/FleetJournal.kt
# are Kotlin/JS, part of the host module) — the journal is a SHARED SUBSTRATE (one append-only
# JSONL file), not an RPC target: this is a from-scratch Python writer matching FleetJournal.kt's
# on-disk schema BYTE-COMPATIBLY (two producers, one file, must agree on both shape and the
# monotonic seq), never calling into the Kotlin.
FLEET_ROOT_ENV = "TORAD_FLEET_ROOT"
JOURNAL_FILE_NAME = "events.jsonl"
JOURNAL_KIND_MANIFEST_VERB = "manifest_verb"
JOURNAL_KIND_LEASE_DECLARED = "lease-declared"
# PROD-D5: the seatd-lane spawn-stamp wire (:seatd Dispatch.kt handleSpawn) — the backfill emits
# the SAME kind/event so the Kotlin consumer (TrajectoryJoin.lineageOf) needs no change.
JOURNAL_KIND_SEATD_LIFECYCLE = "seatd_lifecycle"
POLICY_LINEAGE_STAMPED_EVENT = "policy_lineage_stamped"
# GYM-TRAJECTORY-JOIN (#992-C): the orchestrator's validation verdict as STRUCTURED journal data —
# the accept|redo|blocked edge + honesty signal the trajectory join consumes. Kotlin counterpart
# (byte-identical string contract): backend/gym .../trajectory/VerdictJournalRecord.kt.
JOURNAL_KIND_ORCHESTRATOR_VERDICT = "orchestrator_verdict"
LEASE_FIELDS = frozenset({"mem", "cpu", "wall", "tier", "gpu"})
LEASE_MEMORY_UNITS = frozenset({"K", "M", "G", "T"})
LEASE_WALL_UNITS = frozenset({"s", "m", "h", "d"})
LEASE_TIERS = frozenset({"A", "B", "C"})


def _fleet_journal_dir(_root: str | Path | None = None) -> Path:
    if _root is not None:
        return Path(_root) / "journal"
    override = os.environ.get(FLEET_ROOT_ENV)
    root = Path(override) if override else Path.home() / ".torad"
    return root / "journal"


def _fleet_journal_path(_root: str | Path | None = None) -> Path:
    return _fleet_journal_dir(_root) / JOURNAL_FILE_NAME


JOURNAL_LOCK_RETRY_SECONDS = 0.005
JOURNAL_LOCK_TIMEOUT_SECONDS = 2.0
JOURNAL_LOCK_STALE_SECONDS = 5.0


def _journal_lock_path(journal_path: Path) -> Path:
    return journal_path.with_name(journal_path.name + ".lock")


def _acquire_journal_lock(lock_path: Path) -> None:
    """S1-9: the SAME cross-language lock protocol as FleetJournal.kt's acquireJournalLock and
    journal.mjs's acquireLock — an atomic mkdir succeeds for exactly one racing caller (Python,
    Node, and Kotlin/JS-on-Node all reach the OS's atomic mkdir syscall) and raises FileExistsError
    for the rest, so flock/mkdir/no-lock can no longer race each other on the same tail read. A
    lock directory older than JOURNAL_LOCK_STALE_SECONDS is assumed orphaned by a crashed holder
    and force-removed rather than deadlocking every other producer forever."""
    deadline = time.time() + JOURNAL_LOCK_TIMEOUT_SECONDS
    while True:
        try:
            os.mkdir(lock_path)
            return
        except FileExistsError:
            try:
                stale = time.time() - lock_path.stat().st_mtime > JOURNAL_LOCK_STALE_SECONDS
            except OSError:
                stale = False
            if stale:
                try:
                    os.rmdir(lock_path)
                except OSError:
                    pass
                continue
            if time.time() > deadline:
                raise TimeoutError(f"timed out waiting for journal lock at {lock_path}")
            time.sleep(JOURNAL_LOCK_RETRY_SECONDS)


def _release_journal_lock(lock_path: Path) -> None:
    try:
        os.rmdir(lock_path)
    except OSError:
        pass


def _read_last_journal_seq(path: Path) -> int:
    """Assumes the caller already holds the journal's cross-language mkdir lock — reads under
    lock, not before it, or two producers could both observe the same tail and mint the same seq
    twice."""
    if not path.is_file():
        return -1
    last = -1
    with path.open(encoding="utf-8") as handle:
        for raw_line in handle:
            line = raw_line.strip()
            if not line:
                continue
            try:
                seq = json.loads(line).get("seq")
            except json.JSONDecodeError:
                continue
            if isinstance(seq, int) and seq > last:
                last = seq
    return last


def _journal_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


# PROD-D5-LINEAGE-AUTOSTAMP: transcript-derived model identity. The RESOLVED model id is already
# on disk for every CLI session — Claude Code stamps it on each assistant transcript line
# (~/.claude/projects/<proj>/<session>.jsonl), Codex equivalents under ~/.codex/sessions, and the
# turns bank keeps hardlinked copies after originals rotate. Deriving from the session's OWN
# transcript removes the hand-exported env var (operator ruling: manual-per-seat is not a
# design); TORAD_SEAT_MODEL survives only as an explicit override. Never invented: no transcript,
# no parsable model line -> no modelId, exactly as before.
_MODEL_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]*$")


def _transcript_candidates(session_id: str, _home: str | Path | None = None) -> list[Path]:
    """Transcript files for a session uuid, provider-agnostic: Claude projects, Codex sessions,
    the turns bank (survives original rotation), plus any TORAD_TRANSCRIPT_ROOTS dirs
    (colon-separated) — the zero-code onboarding hook for providers whose transcript filenames
    carry the fleet session id. Providers whose layout CANNOT carry it (e.g. Grok Build's
    per-project prompt_history.jsonl) join via the registry row's explicit `transcript` path
    instead. Filename match only — never fuzzy."""
    home = Path(_home) if _home is not None else Path.home()
    roots: list[tuple[Path, str, str]] = [
        (home / ".claude" / "projects", f"*/{session_id}.jsonl", "glob"),
        (home / ".codex" / "sessions", f"*{session_id}*.jsonl", "rglob"),
        (home / ".torad" / "turns", f"*{session_id}*.jsonl", "rglob"),
    ]
    for extra in (os.environ.get("TORAD_TRANSCRIPT_ROOTS") or "").split(":"):
        extra = extra.strip()
        if extra:
            roots.append((Path(extra), f"*{session_id}*.jsonl", "rglob"))
    out: list[Path] = []
    for root, pattern, mode in roots:
        try:
            if root.is_dir():
                found = root.glob(pattern) if mode == "glob" else root.rglob(pattern)
                out.extend(sorted(p for p in found if p.is_file()))
        except OSError:
            continue
    return out


# Process-lived: lineage-backfill resolves many items against the SAME few session transcripts;
# without this each item re-reads a potentially huge file. Transcripts are append-only, so a
# stale-within-one-process read can never change an already-computed (ts, model) prefix.
_TRANSCRIPT_EVENTS_CACHE: dict[str, list[tuple[str, str]]] = {}


def _transcript_model_events(path: Path) -> list[tuple[str, str]]:
    """(ts, modelId) per transcript line that carries a plausible model, in file order. Cheap
    substring prefilter before json.loads; synthetic placeholders (e.g. '<synthetic>') dropped."""
    cache_key = str(path)
    cached = _TRANSCRIPT_EVENTS_CACHE.get(cache_key)
    if cached is not None:
        return cached
    events: list[tuple[str, str]] = []
    try:
        with open(path, encoding="utf-8", errors="replace") as handle:
            for raw in handle:
                if '"model"' not in raw:
                    continue
                try:
                    obj = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                model = None
                for holder in (obj.get("message"), obj.get("payload"), obj):
                    if isinstance(holder, dict) and isinstance(holder.get("model"), str):
                        model = holder["model"].strip()
                        break
                if not model or not _MODEL_ID_RE.match(model):
                    continue
                ts = obj.get("timestamp") if isinstance(obj.get("timestamp"), str) else obj.get("ts")
                events.append((ts if isinstance(ts, str) else "", model))
    except OSError:
        return []
    _TRANSCRIPT_EVENTS_CACHE[cache_key] = events
    return events


def _model_at(events: list[tuple[str, str]], at_iso: str | None) -> str | None:
    """Last stamped model at-or-before [at_iso], else the last overall (the session opened after
    its stamps rotated), else None. UTC ISO strings compare lexicographically once +00:00 is
    normalized to Z."""
    if not events:
        return None
    if at_iso:
        at_key = at_iso.replace("+00:00", "Z")
        before = [model for ts, model in events if ts and ts.replace("+00:00", "Z") <= at_key]
        if before:
            return before[-1]
    return events[-1][1]


def _resolve_transcript_model(
    session_id: str, at_iso: str | None = None, _home: str | Path | None = None
) -> str | None:
    """The model driving [session_id] at [at_iso] (claim time), from the first candidate
    transcript that carries any model stamps."""
    for path in _transcript_candidates(session_id, _home=_home):
        model = _model_at(_transcript_model_events(path), at_iso)
        if model:
            return model
    return None


def _claim_policy_lineage(session_id: str | None = None, _home: str | Path | None = None) -> dict[str, str] | None:
    """PROD-V3 (#992 gap 4), tmux/ledger-lane half: the best policy identity a CLAIM can carry.
    modelId resolution (PROD-D5): TORAD_SEAT_MODEL env wins as an explicit override; else the
    claiming session's own transcript (infrastructure-held truth, no hands); absent both =
    omitted, never invented. baseSha is the repo HEAD at claim time (fail-soft). Returns None
    when nothing resolved — the journal line then carries no policyLineage key at all. The Kotlin
    consumer (:gym TrajectoryJoin.lineageOf) falls back to this when no spawn stamp exists."""
    lineage: dict[str, str] = {}
    model_id = (os.environ.get("TORAD_SEAT_MODEL") or "").strip()
    if model_id:
        lineage["modelId"] = model_id
        lineage["modelIdBasis"] = "env"
    elif session_id:
        derived = _resolve_transcript_model(session_id, _home=_home)
        if derived:
            lineage["modelId"] = derived
            lineage["modelIdBasis"] = "transcript"
    try:
        proc = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            timeout=3,
            capture_output=True,
            text=True,
            check=False,
        )
        if proc.returncode == 0 and proc.stdout.strip():
            lineage["baseSha"] = proc.stdout.strip()
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError):
        pass
    return lineage or None


def _append_manifest_verb_journal_line(
    path: str,
    verb: str,
    item_id: str,
    seat: str,
    _this_file: str | None = None,
    _root: str | Path | None = None,
    _policy_lineage: dict[str, str] | None = None,
    _status_value: str | None = None,
) -> None:
    """Appends one C4 journal line for a manifest.py verb — gated on _is_canonical_ledger (same
    guard as the notify poke) so selftest's own scratch-copy fixture manipulations never write
    into the real journal; a real canonical-ledger call always does.

    SEQ COORDINATION (S1-9): FleetJournal.kt (Kotlin/JS) and seatd/recorder/journal.mjs (Node)
    are the journal's other two producers. flock is advisory and process-local to fcntl semantics
    — it does NOT exclude a concurrent mkdir-based lock holder in another language, so this
    function used to be able to race them and mint a duplicate seq. All three producers now take
    the SAME mkdir-directory lock (see _acquire_journal_lock) around BOTH the tail-read and the
    append, so no cross-language/cross-process pair can ever observe the same tail at once.

    Fail-open throughout (a journal write failure must never break the ledger operation it is
    attached to — same posture as _notify_orchestrator): a malformed/failed append burns NO seq
    (the seq is computed and the line is written inside the SAME locked, all-or-nothing block),
    and any OSError/lock-timeout here is silently swallowed rather than propagated."""
    if not _is_canonical_ledger(path, _this_file):
        return
    try:
        journal_path = _fleet_journal_path(_root)
        journal_path.parent.mkdir(parents=True, exist_ok=True)
        lock_path = _journal_lock_path(journal_path)
        _acquire_journal_lock(lock_path)
        try:
            seq = _read_last_journal_seq(journal_path) + 1
            line = {
                "seq": seq,
                "ts": _journal_now_iso(),
                # GYM-S6-EPISODE-LABEL-STAMP (host-ratified convention): episodeLabel = ledger
                # itemId — an episode IS the claim->set-status arc, and these verb events are its
                # endpoints, so they carry the same key gym spawn-time events use.
                "episodeLabel": item_id,
                "kind": JOURNAL_KIND_MANIFEST_VERB,
                "verb": verb,
                "itemId": item_id,
                "seat": seat,
            }
            if _policy_lineage:
                line["policyLineage"] = dict(_policy_lineage)
            # PROD-LP-KPI-INSTRUMENT: set-status lines carry the STATUS VALUE so KPI windows can
            # distinguish done/verified flips without re-reading ledgers (additive key; the
            # journal-line validator is shape-open and Kotlin consumers ignore unknown keys).
            if _status_value:
                line["status"] = _status_value
            with open(journal_path, "a", encoding="utf-8") as handle:
                handle.write(json.dumps(line, ensure_ascii=False) + "\n")
                handle.flush()
                os.fsync(handle.fileno())
        finally:
            _release_journal_lock(lock_path)
    except (OSError, TimeoutError):
        pass


def _append_lease_declared_journal_line(
    path: str,
    item_id: str,
    seat: str,
    declared: dict[str, str],
    _this_file: str | None = None,
    _root: str | Path | None = None,
) -> None:
    """Append the caller-authored lease declaration under the manifest episode key.

    This is deliberately a declaration record, not a broker outcome. Later honored/blown
    outcome records must use the host-authenticated broker lane (F5); this writer grants no
    outcome authority. Journal failures retain the existing manifest-verb fail-open posture so
    an observability failure never corrupts or rolls back an already-atomic ledger claim.
    """
    if not _is_canonical_ledger(path, _this_file):
        return
    try:
        journal_path = _fleet_journal_path(_root)
        journal_path.parent.mkdir(parents=True, exist_ok=True)
        lock_path = _journal_lock_path(journal_path)
        _acquire_journal_lock(lock_path)
        try:
            line = {
                "seq": _read_last_journal_seq(journal_path) + 1,
                "ts": _journal_now_iso(),
                "episodeLabel": item_id,
                "kind": JOURNAL_KIND_LEASE_DECLARED,
                "itemId": item_id,
                "seat": seat,
                "declared": dict(declared),
            }
            with open(journal_path, "a", encoding="utf-8") as handle:
                handle.write(json.dumps(line, ensure_ascii=False) + "\n")
                handle.flush()
                os.fsync(handle.fileno())
        finally:
            _release_journal_lock(lock_path)
    except (OSError, TimeoutError):
        pass


def _has_orchestrator_verdict_record(
    item_id: str,
    _root: str | Path | None = None,
) -> bool:
    """READ-ONLY: does the journal already carry a typed orchestrator_verdict record for this
    item? Fail-open on any read problem (an unreadable journal must never turn a status flip
    noisy) — the caller only uses this for an advisory reminder, never a gate."""
    try:
        with _fleet_journal_path(_root).open(encoding="utf-8") as handle:
            for raw_line in handle:
                raw_line = raw_line.strip()
                if not raw_line:
                    continue
                try:
                    entry = json.loads(raw_line)
                except json.JSONDecodeError:
                    continue
                if (
                    entry.get("kind") == JOURNAL_KIND_ORCHESTRATOR_VERDICT
                    and entry.get("itemId") == item_id
                ):
                    return True
    except OSError:
        return True  # unreadable journal: stay silent rather than cry wolf
    return False


def _append_orchestrator_verdict_journal_line(
    path: str,
    item_id: str,
    seat: str,
    outcome: str,
    gap_named: str | None,
    contradiction_locus: str | None,
    env_failure: bool,
    _this_file: str | None = None,
    _root: str | Path | None = None,
    _backfilled: bool = False,
    _basis: str | None = None,
    _corrected: bool = False,
) -> None:
    """GYM-TRAJECTORY-JOIN (#992-C): the typed verdict record — camelCase journal wire matching
    the other verb lines, consumed by :gym trajectory/VerdictJournalRecord.kt (keep field
    spellings byte-identical). Same canonical-ledger gate and fail-open posture as every other
    journal writer here: a journal failure never corrupts or rolls back the ledger note the
    verdict verb already appended (the note is the durable prose home; this line is the typed
    training-side edge)."""
    if not _is_canonical_ledger(path, _this_file):
        return
    try:
        journal_path = _fleet_journal_path(_root)
        journal_path.parent.mkdir(parents=True, exist_ok=True)
        lock_path = _journal_lock_path(journal_path)
        _acquire_journal_lock(lock_path)
        try:
            line = {
                "seq": _read_last_journal_seq(journal_path) + 1,
                "ts": _journal_now_iso(),
                "episodeLabel": item_id,
                "kind": JOURNAL_KIND_ORCHESTRATOR_VERDICT,
                "itemId": item_id,
                "seat": seat,
                "outcome": outcome,
                "gapNamed": gap_named,
                "contradictionFound": contradiction_locus is not None,
                "contradictionLocus": contradiction_locus,
                "envFailure": bool(env_failure),
            }
            if _backfilled:
                # PROD-VERDICT-BACKFILL: a prose-derived record must never be indistinguishable
                # from a review-time verdict (campaign LAW on reconstructed/synthetic flags).
                line["backfilled"] = True
            if _basis:
                line["basis"] = _basis
            if _corrected:
                line["corrected"] = True
            with open(journal_path, "a", encoding="utf-8") as handle:
                handle.write(json.dumps(line, ensure_ascii=False) + "\n")
                handle.flush()
                os.fsync(handle.fileno())
        finally:
            _release_journal_lock(lock_path)
    except (OSError, TimeoutError):
        pass


def _kpi_ledger_closed_items(ledger_paths: list[str]) -> set[str]:
    """Item ids with status done|verified across the given ledgers — line scan, read-only (the
    same comment-preserving posture as every reader here; ids are per-ledger namespaced but the
    KPI is a fleet-wide rate, so the union is the honest denominator)."""
    closed: set[str] = set()
    for ledger_path in ledger_paths:
        current_id = None
        try:
            with open(ledger_path, encoding="utf-8") as handle:
                for raw_line in handle:
                    line = raw_line.strip()
                    if line.startswith('id = "') and line.endswith('"'):
                        current_id = line[len('id = "'):-1]
                    elif line.startswith('status = "') and current_id:
                        status = line[len('status = "'):-1]
                        if status in ("done", "verified"):
                            closed.add(current_id)
        except OSError:
            continue
    return closed


# PROD-BACKFILL-AUDIT-REPAIR: marker NOISE — substrings containing a marker without its verdict:
# negated forms + fleet-mechanism names quoted in prose. PREMISE-BLOCK deliberately absent (a
# premise-block IS a real blocked verdict). Mirror: :gym RetroTrajectorySegmenter
# PROSE_MARKER_NOISE — keep byte-identical or backfill and retro drift apart.
_PROSE_MARKER_NOISE = ("UNVERIFIED", "UNBLOCKED", "VERIFIED-SHIELD", "REDO-NOT-ACCEPT", "BLOCKED-PREFIX")


def _parse_prose_verdict(notes: str):
    """PROD-VERDICT-BACKFILL: mirror of :gym RetroTrajectorySegmenter.parseProseVerdict incl the
    negation/noise vocabulary. Last outcome marker wins; a contradiction marker sets the locus;
    contradiction with no outcome = redo (#991 §3). Returns (outcome, gap_or_clip, locus) or
    None when nothing decisive."""
    outcome = None
    locus = None
    clip = 200
    for raw_line in notes.split("\n"):
        line = raw_line.strip()
        if not line:
            continue
        upper = line.upper()
        if locus is None and ("HONESTY-RECONCILE" in upper or "CONTRADICT" in upper):
            locus = line[:clip]
        # Outcome markers are CASE-SENSITIVE uppercase tokens (the fleet SHOUTS verdicts:
        # "VERIFIED + LANDED", "BLOCKED:"); lowercase prose verbs ("the shield blocked the
        # write") are narration — case-folding conflated them (wave-3 audit finding). Noise is
        # stripped case-insensitively; PREMISE-BLOCK is its own blocked-class token.
        positive = line
        for noise in _PROSE_MARKER_NOISE:
            positive = re.sub(re.escape(noise), "", positive, flags=re.IGNORECASE)
        if "VERIFIED" in positive:
            outcome = ("accepted", line[:clip])
        elif "BLOCKED" in positive or "PREMISE-BLOCK" in positive:
            outcome = ("blocked", line[:clip])
        elif "REDO" in positive:
            outcome = ("redo", line[:clip])
    if outcome is None:
        return ("redo", locus, locus) if locus else None
    return (outcome[0], outcome[1], locus)


def _ledger_closed_items_with_notes(ledger_paths: list[str]) -> list[tuple[str, str, str]]:
    """(item_id, status, joined note prose) for every done|verified item — the same line-scan
    posture as _kpi_ledger_closed_items, plus the dated `# [` note lines a TOML parser discards."""
    items: dict[str, dict] = {}
    for ledger_path in ledger_paths:
        current_id = None
        try:
            with open(ledger_path, encoding="utf-8") as handle:
                for raw_line in handle:
                    line = raw_line.strip()
                    if line == "[[items]]":
                        current_id = None
                    elif line.startswith('id = "') and line.endswith('"'):
                        current_id = line[len('id = "'):-1]
                        items[current_id] = {"status": "", "notes": []}
                    elif line.startswith('status = "') and current_id:
                        items[current_id]["status"] = line[len('status = "'):-1]
                    elif line.startswith("# [") and current_id:
                        items[current_id]["notes"].append(line)
        except OSError:
            continue
    return [
        (item_id, data["status"], "\n".join(data["notes"]))
        for item_id, data in items.items()
        if data["status"] in ("done", "verified")
    ]


def _journal_last_verdicts(_root=None) -> dict[str, dict]:
    """itemId -> LAST orchestrator_verdict entry (last-wins, matching :gym typedVerdictOf)."""
    last: dict[str, dict] = {}
    try:
        with _fleet_journal_path(_root).open(encoding="utf-8") as handle:
            for raw_line in handle:
                raw_line = raw_line.strip()
                if not raw_line:
                    continue
                try:
                    entry = json.loads(raw_line)
                except json.JSONDecodeError:
                    continue
                if entry.get("kind") == JOURNAL_KIND_ORCHESTRATOR_VERDICT and isinstance(entry.get("itemId"), str):
                    last[entry["itemId"]] = entry
    except OSError:
        pass
    return last


def cmd_verdict_backfill(path, dry_run=False, repair=False, _root=None, _ledger_paths=None, _this_file=None):
    """PROD-VERDICT-BACKFILL + PROD-BACKFILL-AUDIT-REPAIR: type the historical verdicts, honestly.
    Basis order per closed item without a typed record: (1) prose markers (negation/noise-safe);
    (2) status=verified -> accepted with basis=status-verified (the verified transition IS the
    orchestrator's structured accept — reading data, not inventing); (3) done-only markerless
    items STAY ungraded. Every record carries backfilled=true (+basis). --repair recomputes every
    already-BACKFILLED item with the CURRENT parser and appends a corrected=true record where the
    outcome differs (journal is append-only; consumers are last-wins, so a correction supersedes;
    review-time verdicts are NEVER touched). Idempotent in both modes."""
    ledger_paths = _ledger_paths if _ledger_paths is not None else sorted(
        str(p) for p in Path(path).resolve().parent.glob("*.toml")
    )
    last_verdicts = _journal_last_verdicts(_root)
    closed = _ledger_closed_items_with_notes(ledger_paths)
    seat = _canonical_seat() or "unknown"
    counts = {"accepted": 0, "redo": 0, "blocked": 0}
    status_verified = 0
    corrected = 0
    already = 0
    no_marker = 0
    contradictions = 0

    def desired_verdict(status: str, notes: str):
        parsed = _parse_prose_verdict(notes)
        if parsed is not None:
            outcome, clip_text, locus = parsed
            return outcome, (None if outcome == "accepted" else clip_text), locus, "prose"
        if status == "verified":
            return "accepted", None, None, "status-verified"
        return None

    for item_id, status, notes in closed:
        existing = last_verdicts.get(item_id)
        if existing is not None and not repair:
            already += 1
            continue
        if existing is not None:
            # --repair: only backfilled records are correctable; a review-time verdict is truth.
            if existing.get("backfilled") is not True:
                already += 1
                continue
            desired = desired_verdict(status, notes)
            if desired is None or desired[0] == existing.get("outcome"):
                already += 1
                continue
            outcome, gap, locus, basis = desired
            corrected += 1
            if not dry_run:
                _append_orchestrator_verdict_journal_line(
                    path, item_id, seat, outcome, gap, locus, False, _this_file, _root,
                    _backfilled=True, _basis=basis, _corrected=True,
                )
            continue
        desired = desired_verdict(status, notes)
        if desired is None:
            no_marker += 1
            continue
        outcome, gap, locus, basis = desired
        if basis == "status-verified":
            status_verified += 1
        else:
            counts[outcome] += 1
        if locus:
            contradictions += 1
        if dry_run:
            continue
        _append_orchestrator_verdict_journal_line(
            path, item_id, seat, outcome, gap, locus, False, _this_file, _root,
            _backfilled=True, _basis=basis,
        )
    mode = "[dry-run] " if dry_run else ""
    total = sum(counts.values()) + status_verified
    print(
        f"{mode}verdict-backfill: {total} backfilled "
        f"(prose accepted={counts['accepted']} redo={counts['redo']} blocked={counts['blocked']}; "
        f"status-verified={status_verified}; {contradictions} with contradiction locus); "
        f"corrected={corrected}; already-typed={already}; "
        f"no-marker-not-verified={no_marker} (stay ungraded — never invented)"
    )


def _append_policy_lineage_stamp_journal_line(
    path, item_id, seat, model_id, _this_file=None, _root=None, _basis=None,
):
    """PROD-D5: append one BACKFILLED policy_lineage_stamped line — the same wire :seatd's spawn
    path emits organically, so the stamp outranks the claim-line stub by the existing last-wins
    precedence with zero Kotlin change. backfilled=true keeps it distinguishable forever
    (campaign LAW on reconstructed flags) and keeps KPI 7d-activity from counting corpus repair
    as fleet activity. Same canonical gate + cross-language journal lock as every producer."""
    if not _is_canonical_ledger(path, _this_file):
        return
    try:
        journal_path = _fleet_journal_path(_root)
        journal_path.parent.mkdir(parents=True, exist_ok=True)
        lock_path = _journal_lock_path(journal_path)
        _acquire_journal_lock(lock_path)
        try:
            seq = _read_last_journal_seq(journal_path) + 1
            line = {
                "seq": seq,
                "ts": _journal_now_iso(),
                "episodeLabel": item_id,
                "kind": JOURNAL_KIND_SEATD_LIFECYCLE,
                "seat": seat,
                "event": POLICY_LINEAGE_STAMPED_EVENT,
                "detail": {"modelId": model_id},
                "backfilled": True,
            }
            if _basis:
                line["basis"] = _basis
            with open(journal_path, "a", encoding="utf-8") as handle:
                handle.write(json.dumps(line, ensure_ascii=False) + "\n")
                handle.flush()
                os.fsync(handle.fileno())
        finally:
            _release_journal_lock(lock_path)
    except (OSError, TimeoutError):
        pass


# Regex over dated NOTE PROSE only (the structural no-regex law binds code and structured data,
# not # comment lines a TOML parser discards). Only uuid-shaped owners are transcript-joinable;
# seat~machine claims and handover targets are honestly unjoinable and never match.
_CLAIM_OWNER_UUID_RE = re.compile(
    r"CLAIM: owner=([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"
    r" at=([0-9A-Za-z:.+-]+)"
)
_LEDGER_ITEM_ID_LINE_RE = re.compile(r'^id\s*=\s*"([^"]+)"')


def _ledger_claim_attributions(ledger_paths: list[str]) -> dict[str, tuple[str, str]]:
    """{item_id: (session_uuid, claim_at_iso)} from ledger CLAIM notes across [ledger_paths],
    last-uuid-claim-wins per item (same discipline as _block_owner's owner scan)."""
    out: dict[str, tuple[str, str]] = {}
    for ledger in ledger_paths:
        try:
            text = Path(ledger).read_text(encoding="utf-8")
        except OSError:
            continue
        current: str | None = None
        for raw in text.splitlines():
            stripped = raw.strip()
            id_match = _LEDGER_ITEM_ID_LINE_RE.match(stripped)
            if id_match:
                current = id_match.group(1)
                continue
            if current and stripped.startswith("#") and "CLAIM: owner=" in stripped:
                claim_match = _CLAIM_OWNER_UUID_RE.search(stripped)
                if claim_match:
                    out[current] = (claim_match.group(1), claim_match.group(2))
    return out


def _registry_seat_sessions(_registry_dir: str | Path | None = None) -> list[dict]:
    """Rows {seat, started, updated, session_id, transcript?} from the ~/.torad/sessions
    registry — the durable seat->session join for claims recorded under bare seat identities
    (the fleet-* tmux seats). An explicit `transcript` field on a row is the provider-complete
    onboarding path: harnesses whose transcript layout cannot carry the fleet session id in a
    filename (Grok Build, Cursor, ...) register the path directly and join with zero glob."""
    registry = Path(_registry_dir) if _registry_dir is not None else (Path.home() / ".torad" / "sessions")
    out: list[dict] = []
    try:
        files = sorted(registry.glob("*.json"))
    except OSError:
        return []
    for entry_path in files:
        try:
            data = json.loads(entry_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        seat = data.get("seat")
        started = data.get("started")
        updated = data.get("updated")
        transcript = data.get("transcript")
        if isinstance(seat, str) and seat and isinstance(started, str) and started:
            out.append({
                "seat": seat,
                "started": started,
                "updated": updated if isinstance(updated, str) and updated else "9999",
                "session_id": entry_path.stem,
                "transcript": transcript if isinstance(transcript, str) and transcript else None,
            })
    return out


def _registry_session_for(seat: str, ts: str, sessions: list[dict]) -> dict | None:
    """The registered session row whose [started, updated] window covers [ts] for [seat]; when
    windows overlap, the latest-started (tightest) wins. UTC Z strings compare lexically."""
    key = ts.replace("+00:00", "Z")
    best: dict | None = None
    for row in sessions:
        if row["seat"] != seat or not (row["started"] <= key <= row["updated"]):
            continue
        if best is None or row["started"] > best["started"]:
            best = row
    return best


# PROD-D5B: execution-record basis. A claim's execution is physically recorded in the claiming
# session's own transcript as a STRUCTURED tool-call record — the command string lives under a
# command/arguments/input key path. Compaction summaries quote claim OUTPUT as free text into
# later sessions, but never replicate the tool-call record, so structural matching is the
# contamination wall. Unique-match law: 0 files -> stays unattributed; >1 files -> journal-ts
# proximity disambiguates or the item is skipped as ambiguous (counted, never resolved by
# preference).
_TOOL_CALL_KEYS = ("command", "arguments", "input")
_EXEC_TS_WINDOW_SECONDS = 600.0


def _bank_prefilter_files(bank_dir: Path, item_ids: list[str]) -> list[Path]:
    """Phase 1: cheap file-level prefilter over the turns bank — rg with chunked literal
    alternation when available (6GB+ bank), pure-python substring scan as fallback."""
    if not bank_dir.is_dir():
        return []
    if shutil.which("rg"):
        found: set[str] = set()
        chunk = 40
        for start in range(0, len(item_ids), chunk):
            pattern = "claim (" + "|".join(re.escape(i) for i in item_ids[start:start + chunk]) + ")"
            proc = subprocess.run(
                ["rg", "-l", "--no-messages", "-e", pattern, str(bank_dir)],
                capture_output=True, text=True, check=False,
            )
            found.update(l for l in proc.stdout.splitlines() if l.strip())
        return sorted(Path(f) for f in found)
    out = []
    for f in sorted(bank_dir.rglob("*.jsonl")):
        try:
            text = f.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if any(f"claim {i}" in text for i in item_ids):
            out.append(f)
    return out


def _tool_call_contains(obj, needle: str, under_tool_key: bool = False) -> bool:
    """True iff [needle] appears in a string reached through a command/arguments/input key —
    the structural discriminator between an executed tool call and quoted summary text."""
    if isinstance(obj, str):
        return under_tool_key and needle in obj
    if isinstance(obj, dict):
        return any(
            _tool_call_contains(v, needle, under_tool_key or k in _TOOL_CALL_KEYS)
            for k, v in obj.items()
        )
    if isinstance(obj, list):
        return any(_tool_call_contains(v, needle, under_tool_key) for v in obj)
    return False


def _ts_seconds(ts: str | None) -> float | None:
    if not isinstance(ts, str) or not ts:
        return None
    try:
        return datetime.fromisoformat(ts.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def _execution_record_index(bank_dir: Path, item_ids: list[str]) -> dict[str, list[tuple[Path, str]]]:
    """{item_id: [(transcript, tool_call_ts)]} for every structured claim-execution record found
    in the bank. One candidate-file pass; every hit is per-(item, file) deduped."""
    index: dict[str, dict[Path, str]] = {}
    for f in _bank_prefilter_files(bank_dir, item_ids):
        try:
            handle = open(f, encoding="utf-8", errors="replace")
        except OSError:
            continue
        with handle:
            for raw in handle:
                hit_ids = [i for i in item_ids if f"claim {i}" in raw]
                if not hit_ids:
                    continue
                try:
                    obj = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                ts = obj.get("timestamp") if isinstance(obj.get("timestamp"), str) else obj.get("ts")
                for item in hit_ids:
                    if _tool_call_contains(obj, f"claim {item}"):
                        index.setdefault(item, {}).setdefault(f, ts if isinstance(ts, str) else "")
    return {item: sorted(files.items(), key=lambda kv: str(kv[0])) for item, files in index.items()}


def _resolve_execution_record(
    item_id: str, claim_ts: str, exec_index: dict[str, list[tuple[Path, str]]],
) -> str | None:
    """Model for [item_id] from its unique claim-execution record; journal-ts proximity breaks a
    multi-file tie, anything still ambiguous returns None."""
    candidates = exec_index.get(item_id) or []
    if len(candidates) > 1:
        claim_s = _ts_seconds(claim_ts)
        if claim_s is not None:
            near = [
                (f, ts) for f, ts in candidates
                if (s := _ts_seconds(ts)) is not None and abs(s - claim_s) <= _EXEC_TS_WINDOW_SECONDS
            ]
            candidates = near
    if len(candidates) != 1:
        return None
    transcript, tool_ts = candidates[0]
    return _model_at(_transcript_model_events(transcript), tool_ts or claim_ts)


def cmd_lineage_backfill(
    path, dry_run=False, _root=None, _ledger_paths=None, _this_file=None, _home=None, _registry_dir=None,
    _bank_dir=None,
):
    """PROD-D5 retrofit: attribute historical arcs to the model that actually drove them.
    Two join bases, most precise first: (1) ledger CLAIM owner uuid + claim ts -> that session's
    own transcript; (2) the journal claim line's bare seat + ts -> the sessions registry window
    -> that session's transcript. Either way the model is the last one stamped at-or-before
    claim time, appended as ONE policy_lineage_stamped line (backfilled=true, basis=transcript /
    registry-transcript). Skips are counted, never silent: already-attributed, no findable
    transcript. Idempotent: the existing-stamp check makes re-runs no-ops."""
    ledger_paths = _ledger_paths if _ledger_paths is not None else sorted(
        str(p) for p in Path(path).resolve().parent.glob("*.toml")
    )
    attributions = _ledger_claim_attributions(ledger_paths)
    registry_sessions = _registry_seat_sessions(_registry_dir)
    claim_model_items: set[str] = set()
    claim_lines: dict[str, tuple[str, str]] = {}
    stamped: set[str] = set()
    present_labels: set[str] = set()
    try:
        journal_handle = _fleet_journal_path(_root).open(encoding="utf-8")
    except OSError:
        sys.exit("error: no readable journal — lineage-backfill needs the C4 journal")
    with journal_handle:
        for raw_line in journal_handle:
            raw_line = raw_line.strip()
            if not raw_line:
                continue
            try:
                entry = json.loads(raw_line)
            except json.JSONDecodeError:
                continue
            label = entry.get("episodeLabel")
            if isinstance(label, str) and label.strip():
                present_labels.add(label)
            kind = entry.get("kind")
            if kind == JOURNAL_KIND_MANIFEST_VERB and entry.get("verb") == "claim":
                item = entry.get("itemId")
                if isinstance(item, str):
                    seat = entry.get("seat")
                    ts = entry.get("ts")
                    claim_lines[item] = (
                        seat if isinstance(seat, str) else "",
                        ts if isinstance(ts, str) else "",
                    )
                    if isinstance(entry.get("policyLineage"), dict) and entry["policyLineage"].get("modelId"):
                        claim_model_items.add(item)
            elif kind == JOURNAL_KIND_SEATD_LIFECYCLE and entry.get("event") == POLICY_LINEAGE_STAMPED_EVENT:
                if isinstance(label, str):
                    stamped.add(label)

    def resolve(item_id: str) -> tuple[str, str] | None:
        """(modelId, basis) — owner-uuid transcript first, then the registry seat-window join
        (its explicit transcript path first, then the session-id filename glob)."""
        attribution = attributions.get(item_id)
        if attribution is not None:
            model = _resolve_transcript_model(attribution[0], attribution[1], _home=_home)
            if model:
                return model, "transcript"
        seat, ts = claim_lines.get(item_id, ("", ""))
        if seat and ts:
            row = _registry_session_for(seat, ts, registry_sessions)
            if row is not None:
                if row["transcript"]:
                    model = _model_at(_transcript_model_events(Path(row["transcript"])), ts)
                    if model:
                        return model, "registry-transcript"
                model = _resolve_transcript_model(row["session_id"], ts, _home=_home)
                if model:
                    return model, "registry-transcript"
        return None

    stamped_now = 0
    by_model: dict[str, int] = {}
    by_basis: dict[str, int] = {}
    already = 0
    no_presence = 0
    no_transcript = 0
    resolved_items: dict[str, tuple[str, str]] = {}
    leftovers: list[str] = []
    for item_id in sorted(set(attributions) | set(claim_lines)):
        if item_id in claim_model_items or item_id in stamped:
            already += 1
            continue
        if item_id not in present_labels and item_id not in claim_lines:
            # A ledger-only attribution with zero journal presence: a stamp must join a real
            # arc, never mint one out of nothing.
            no_presence += 1
            continue
        resolved = resolve(item_id)
        if resolved is None:
            leftovers.append(item_id)
        else:
            resolved_items[item_id] = resolved
    # PROD-D5B: basis 3 for whatever bases 1+2 could not reach — one bank scan for ALL
    # leftovers (the scan is the expensive part; per-item scanning would re-read 6GB each).
    if leftovers:
        bank_dir = Path(_bank_dir) if _bank_dir is not None else (Path.home() / ".torad" / "turns")
        exec_index = _execution_record_index(bank_dir, leftovers)
        for item_id in leftovers:
            model = _resolve_execution_record(item_id, claim_lines.get(item_id, ("", ""))[1], exec_index)
            if model:
                resolved_items[item_id] = (model, "execution-record")
            else:
                no_transcript += 1
    for item_id, (model, basis) in sorted(resolved_items.items()):
        stamped_now += 1
        by_model[model] = by_model.get(model, 0) + 1
        by_basis[basis] = by_basis.get(basis, 0) + 1
        if dry_run:
            continue
        seat = claim_lines.get(item_id, ("", ""))[0] or _canonical_seat() or "lineage-backfill"
        _append_policy_lineage_stamp_journal_line(
            path, item_id, seat, model, _this_file, _root, _basis=basis,
        )
    mode = "[dry-run] " if dry_run else ""
    models = ", ".join(f"{model}={count}" for model, count in sorted(by_model.items())) or "none"
    bases = ", ".join(f"{basis}={count}" for basis, count in sorted(by_basis.items())) or "none"
    print(
        f"{mode}lineage-backfill: {stamped_now} stamped ({models}; via {bases}); "
        f"already-attributed={already}; no-journal-presence={no_presence}; "
        f"no-transcript={no_transcript} (stay unattributed — never invented)"
    )


def cmd_gym_kpi(path, _root=None, _now=None, _ledger_paths=None):
    """PROD-LP-KPI-INSTRUMENT: the campaign KPI, measured — read-only over the journal + ledgers.
    Prints verdict-coverage%, lineage-completeness% (overall + 7d), trajectories active in the 7d
    window, and honesty yield. The dated output IS the ratchet floor: future waves are judged by
    the delta (predicted-vs-observed, #948 §2 expected-delta discipline), never by prose."""
    now = _now or datetime.now(timezone.utc)
    window_start = now - timedelta(days=7)
    ledger_paths = _ledger_paths if _ledger_paths is not None else sorted(
        str(p) for p in Path(path).resolve().parent.glob("*.toml")
    )
    closed_items = _kpi_ledger_closed_items(ledger_paths)

    verdict_items: set[str] = set()
    backfilled_items: set[str] = set()
    # PROD-D5: lineage attribution is stamp-aware — a claim counts attributed when EITHER its own
    # line carries modelId OR a policy_lineage_stamped line exists for the episode (stamps are
    # how the seatd lane and the retro backfill attribute; the old metric could not see them).
    # Stamps can land after their claim in file order, so claims collect first, compute after.
    claims: list[tuple[str | None, bool, bool]] = []
    stamped_labels: set[str] = set()
    contradictions = 0
    active_labels_7d: set[str] = set()
    try:
        journal_handle = _fleet_journal_path(_root).open(encoding="utf-8")
    except OSError:
        sys.exit("error: no readable journal — the KPI instrument needs the C4 journal")
    with journal_handle:
        for raw_line in journal_handle:
            raw_line = raw_line.strip()
            if not raw_line:
                continue
            try:
                entry = json.loads(raw_line)
            except json.JSONDecodeError:
                continue
            ts_text = entry.get("ts")
            in_window = False
            if isinstance(ts_text, str):
                try:
                    in_window = datetime.fromisoformat(ts_text.replace("Z", "+00:00")) >= window_start
                except ValueError:
                    in_window = False
            kind = entry.get("kind")
            if kind == JOURNAL_KIND_ORCHESTRATOR_VERDICT:
                item_id = entry.get("itemId")
                if isinstance(item_id, str):
                    verdict_items.add(item_id)
                    if entry.get("backfilled") is True:
                        backfilled_items.add(item_id)
                if entry.get("contradictionFound") is True:
                    contradictions += 1
            elif kind == JOURNAL_KIND_MANIFEST_VERB and entry.get("verb") == "claim":
                has_model = isinstance(entry.get("policyLineage"), dict) and bool(
                    entry["policyLineage"].get("modelId")
                )
                claim_item = entry.get("itemId")
                claims.append((claim_item if isinstance(claim_item, str) else None, in_window, has_model))
            elif kind == JOURNAL_KIND_SEATD_LIFECYCLE and entry.get("event") == POLICY_LINEAGE_STAMPED_EVENT:
                stamp_label = entry.get("episodeLabel")
                if isinstance(stamp_label, str) and stamp_label.strip():
                    stamped_labels.add(stamp_label)
            label = entry.get("episodeLabel")
            # Backfilled records are corpus repair, not fleet activity — they must not make
            # historical episodes look 7d-active (found live: the first backfill run inflated
            # trajectories-7d from 116 to 623).
            if in_window and entry.get("backfilled") is not True and isinstance(label, str) and label.strip():
                active_labels_7d.add(label)

    def pct(numerator: int, denominator: int) -> str:
        return "n/a" if denominator == 0 else f"{numerator * 100.0 / denominator:.1f}%"

    def attributed(item: str | None, has_model: bool) -> bool:
        return has_model or (item is not None and item in stamped_labels)

    claims_total = len(claims)
    claims_with_model = sum(1 for item, _w, model in claims if attributed(item, model))
    claims_7d_total = sum(1 for _item, in_win, _m in claims if in_win)
    claims_7d_with_model = sum(1 for item, in_win, model in claims if in_win and attributed(item, model))
    covered = len(closed_items & verdict_items)
    covered_backfilled = len(closed_items & backfilled_items)
    print(f"gym-kpi @ {now.isoformat(timespec='seconds')} (window: 7d, ledgers: {len(ledger_paths)})")
    print(
        f"  verdict-coverage:      {covered}/{len(closed_items)} closed items typed-verdicted "
        f"({pct(covered, len(closed_items))}; {covered_backfilled} via backfill)"
    )
    print(f"  lineage-completeness:  overall {claims_with_model}/{claims_total} claims lineage-attributed ({pct(claims_with_model, claims_total)}); 7d {claims_7d_with_model}/{claims_7d_total} ({pct(claims_7d_with_model, claims_7d_total)})")
    print(f"  trajectories-7d:       {len(active_labels_7d)} distinct episodes active")
    print(f"  honesty-yield:         {contradictions} typed contradiction record(s) all-time")


# NOTIFY-1: the actionable manifest verbs — completions/blocks and ownership churn wake the
# orchestrator; progress notes deliberately do NOT (anti-spam; the beacon + heartbeat floor
# cover slow-burn visibility). `--verbs note` opts in explicitly.
EVENTS_DEFAULT_VERBS = "set-status,claim,handover"


def cmd_events(path, after_seq=None, exclude_seat=None, verbs=None, _root=None):
    """NOTIFY-1: READ-ONLY journal query — the orchestrator's Monitor condition AND drain
    command (one classifier, two uses). Prints matching journal lines; exits 0 when at least
    one matches (the Monitor fires), 1 when none (keep waiting). Fail-safe by construction:
    takes NO lock (a torn concurrent append is skipped by the json-decode guard and picked up
    on the next poll), never writes, never journals itself (a read verb emitting events would
    self-oscillate), and a missing/unreadable journal is a clean no-match — degrading exactly
    to the timer-driven heartbeat floor, never worse. Seat exclusion matches bare seats and
    seat~session forms. `path` is accepted for CLI-shape consistency; events reads only the
    journal."""
    try:
        after = int(after_seq) if after_seq is not None else -1
    except ValueError:
        sys.exit(f"error: --after-seq must be an integer, got {after_seq!r}")
    wanted = {v.strip() for v in (verbs or EVENTS_DEFAULT_VERBS).split(",") if v.strip()}
    excluded = {s.strip() for s in (exclude_seat or "").split(",") if s.strip()}
    matches: list[str] = []
    try:
        with _fleet_journal_path(_root).open(encoding="utf-8") as handle:
            for raw_line in handle:
                line = raw_line.strip()
                if not line:
                    continue
                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    continue  # torn/malformed line: skip, keep scanning (fail-safe)
                if not isinstance(event, dict):
                    continue
                seq = event.get("seq")
                if not isinstance(seq, int) or seq <= after:
                    continue
                if event.get("kind") != JOURNAL_KIND_MANIFEST_VERB:
                    continue
                if event.get("verb") not in wanted:
                    continue
                seat = str(event.get("seat") or "")
                if any(seat == ex or seat.startswith(ex + "~") for ex in excluded):
                    continue
                matches.append(line)
    except OSError:
        pass  # no journal = no events; the heartbeat floor covers (never a crash)
    for line in matches:
        print(line)
    sys.exit(0 if matches else 1)


def _hydrate_traces(path: str, _hydrate_script: str | None = None) -> None:
    repo = _git_repo_root(path)
    if repo is None:
        try:
            repo = Path(path).resolve(strict=False).parents[2]
        except IndexError:
            return
    script = Path(_hydrate_script) if _hydrate_script is not None else Path(__file__).resolve().parents[2] / "scripts" / "hydrate-traces.py"
    if not script.is_file():
        return
    out_path = Path("dev") / "campaigns" / "traces" / f"{Path(path).stem}.jsonl"
    try:
        proc = subprocess.run(
            [sys.executable, str(script), path, "--out", str(out_path)],
            cwd=repo,
            capture_output=True,
            text=True,
            timeout=20,
            check=False,
        )
    except subprocess.TimeoutExpired:
        print(f"warning: trace hydration timed out for {Path(path).name}", file=sys.stderr)
        return
    except OSError as exc:
        print(f"warning: trace hydration failed for {Path(path).name}: {exc}", file=sys.stderr)
        return
    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout or "").strip().splitlines()
        tail = f": {detail[0]}" if detail else ""
        print(
            f"warning: trace hydration failed for {Path(path).name} via {script.name} (rc={proc.returncode}){tail}",
            file=sys.stderr,
        )


def _atomic_write_json(path: Path, data: dict) -> None:
    """MIRRORED from .claude/hooks/modules/stop/07_stall_detector.py's own copy (same repo,
    same pattern) — the toolkit atomic-write convention: write to a sibling temp file, fsync,
    then os.replace so a concurrent reader never observes a partial pointer file."""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f".{path.name}.tmp.{os.getpid()}")
    with tmp.open("w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(tmp, path)


def _resolve_pointer_session_id(seat: str, _registry_dir: Path | str | None = None) -> str | None:
    """THE SEAM (R3B): pointer files are keyed by the harness session_id (consumers:
    06_inflight_beacon/07_stall_detector's _pointer_path, 12_inflight_reanchor), but claim
    receives a SEAT name — resolve via the registry: scan ~/.torad/sessions/*.json
    (TORAD_REGISTRY_DIR override honored, injectable here for tests) for entries whose seat
    field == seat OR file stem == seat; take the newest by "updated"; return that entry's stem."""
    if _registry_dir is not None:
        registry_dir = Path(_registry_dir)
    else:
        override = os.environ.get("TORAD_REGISTRY_DIR")
        registry_dir = Path(override) if override else Path.home() / ".torad" / "sessions"
    if not registry_dir.is_dir():
        return None
    best_stem: str | None = None
    best_updated = ""
    for registry in sorted(registry_dir.glob("*.json")):
        try:
            with registry.open(encoding="utf-8") as handle:
                data = json.load(handle)
        except (OSError, json.JSONDecodeError):
            continue
        if not isinstance(data, dict):
            continue
        raw_seat = data.get("seat")
        entry_seat = raw_seat if isinstance(raw_seat, str) else ""
        if registry.stem != seat and entry_seat != seat:
            continue
        raw_updated = data.get("updated")
        updated = raw_updated if isinstance(raw_updated, str) else ""
        if best_stem is None or updated > best_updated:
            best_stem = registry.stem
            best_updated = updated
    return best_stem


def _write_pointer(
    path: str,
    item_id: str,
    seat: str,
    _registry_dir: Path | str | None = None,
    _this_file: str | None = None,
    _missing_registry_warning: str | None = None,
) -> None:
    """S1: after a successful claim, write root/.claude/state/ledger-active-<session>.json —
    canonical-guard reuse (G5) first, since fixture/tmp claims must never write real pointers;
    no registry match or no repo root -> silent skip unless the caller asks for a warning;
    the write itself is fail-open."""
    if not _is_canonical_ledger(path, _this_file):
        return
    root = _git_repo_root(path)
    if root is None:
        return
    session_id = _resolve_pointer_session_id(seat, _registry_dir)
    if session_id is None:
        if _missing_registry_warning is not None:
            print(_missing_registry_warning, file=sys.stderr)
        return
    pointer = root / ".claude" / "state" / f"ledger-active-{session_id}.json"
    try:
        _atomic_write_json(
            pointer,
            {
                "item_id": item_id,
                "ledger_path": str(Path(path).resolve()),
                "seat": seat,
                "updated_at": _utc_iso_seconds(),
            },
        )
    except OSError:
        pass


def _clear_pointers(path: str, item_id: str, _this_file: str | None = None) -> None:
    """S2: on done/verified/release-stale, remove any pointer file naming this exact
    (item_id, ledger_path) pair — scanned by glob since the closer may be a different
    session than the one that claimed. Fail-open throughout."""
    if not _is_canonical_ledger(path, _this_file):
        return
    root = _git_repo_root(path)
    if root is None:
        return
    state_dir = root / ".claude" / "state"
    if not state_dir.is_dir():
        return
    target_ledger = str(Path(path).resolve())
    try:
        for pointer in state_dir.glob("ledger-active-*.json"):
            try:
                with pointer.open(encoding="utf-8") as handle:
                    data = json.load(handle)
            except (OSError, json.JSONDecodeError):
                continue
            if not isinstance(data, dict):
                continue
            if data.get("item_id") == item_id and data.get("ledger_path") == target_ledger:
                pointer.unlink(missing_ok=True)
    except OSError:
        pass


def _run_git(cwd: Path, args: list[str], env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    git_env = os.environ.copy()
    if env:
        git_env.update(env)
    return subprocess.run(
        ["git", "-C", str(cwd), *args],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=git_env,
    )


def _snapshot_tree(repo: Path) -> str:
    with tempfile.TemporaryDirectory(prefix="torad-attest-index-") as tmp:
        index = Path(tmp) / "index"
        env = {"GIT_INDEX_FILE": str(index)}
        try:
            _run_git(repo, ["rev-parse", "--verify", "HEAD"], env=env)
            _run_git(repo, ["read-tree", "HEAD"], env=env)
        except subprocess.CalledProcessError:
            pass
        _run_git(repo, ["add", "-A"], env=env)
        return _run_git(repo, ["write-tree"], env=env).stdout.strip()


def _normalize_scope(scope: str) -> str:
    normalized = scope.replace("\\", "/").strip()
    if not normalized:
        return ""
    if normalized.endswith("/**"):
        normalized = normalized[:-3]
    elif normalized.endswith("*"):
        normalized = normalized[:-1]
    return normalized.rstrip("/")


def _scope_matches(path: str, scopes: list[str]) -> bool:
    normalized = path.replace("\\", "/")
    for scope in scopes:
        prefix = _normalize_scope(str(scope))
        if not prefix:
            continue
        if normalized == prefix or normalized.startswith(prefix + "/"):
            return True
    return False


def _bracket_paths(repo: Path, start_tree: str, end_tree: str) -> list[str]:
    output = _run_git(
        repo,
        ["diff-tree", "-r", "--name-only", "--no-commit-id", start_tree, end_tree],
    ).stdout
    paths = [line.strip() for line in output.splitlines() if line.strip()]
    return [path for path in paths if not _attest_excluded(path)]


def _diff_for_paths(repo: Path, start_tree: str, end_tree: str, paths: list[str]) -> str:
    if not paths:
        return ""
    return _run_git(repo, ["diff-tree", "-r", "-p", "--no-commit-id", start_tree, end_tree, "--", *paths]).stdout


def _diffstat_for_paths(repo: Path, start_tree: str, end_tree: str, paths: list[str]) -> list[str]:
    if not paths:
        return ["(no scoped file changes)"]
    output = _run_git(
        repo,
        ["diff-tree", "--stat", "--no-commit-id", start_tree, end_tree, "--", *paths],
    ).stdout
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    if not lines:
        return ["(no scoped file changes)"]
    capped = lines[:ATTR_DIFFSTAT_LINES]
    if len(lines) > ATTR_DIFFSTAT_LINES:
        capped.append(f"... {len(lines) - ATTR_DIFFSTAT_LINES} more diffstat lines")
    return capped


def _sidecar_rel_path(item_id: str) -> Path:
    safe_id = re.sub(r"[^A-Za-z0-9_.-]+", "_", item_id)
    return ATTR_DIFF_DIR / f"{safe_id}.diff"


def _sidecar_files_rel_path(item_id: str) -> Path:
    """FENCE-AUTONARROW: sibling of the .diff sidecar holding the EXACT scoped_paths list
    _done_attribution computed -- not the rendered diffstat text (git --stat can abbreviate
    long paths), so a later set-status verified can narrow an item's fence to precisely what
    it attested without re-deriving anything or risking a double-attest on a later call."""
    return _sidecar_rel_path(item_id).with_suffix(".files.json")


def _format_path_list(paths: list[str], cap: int = 8) -> str:
    if not paths:
        return "none"
    capped = paths[:cap]
    suffix = f", ... {len(paths) - cap} more" if len(paths) > cap else ""
    return ", ".join(capped) + suffix


def cmd_scope_diff(_path, base_sha, repo, files):
    """S6-4b: T4's own bracket-attribution (_capture_in_flight_attribution/_done_attribution)
    always resolves its repo via the LEDGER file's own directory (_git_repo_root(path)) — correct
    for a real ledger-history episode (its changes land in that same repo), but inert for a Gym A
    snapshot-worktree episode (S6-1's own pattern): the episode's changes land in a SEPARATE
    scratch repo T4 never even looks at, so scope_signal always sees "(no scoped file changes)"
    regardless of what the episode actually did.

    This verb is T4's own bracket_paths/scope_matches/diffstat_for_paths logic (mirrored, not
    re-authored), parameterized by an EXPLICIT --repo instead of deriving one from the ledger
    path — so gym/reward/scope_signal.py can compute an episode's real scope from its own
    worktree diff directly, the fix the item's own title names. `_path` (the ledger) is accepted
    for CLI-shape consistency with every other verb but genuinely unused here — this verb's own
    repo is always the explicit --repo argument, never derived from a ledger.

    The "end" state is _snapshot_tree(repo) — the SAME temp-index write-tree T4's own
    _capture_in_flight_attribution/_done_attribution use, deliberately reused rather than an
    explicit end-ref argument: an episode's own code changes may still be UNCOMMITTED in its
    scratch worktree (a builder script edits files directly, nothing forces a commit), and a bare
    git diff-tree only compares tree-ish objects — a plain "HEAD" would miss exactly the
    uncommitted changes this verb exists to see.
    """
    repo_path = Path(repo)
    end_tree = _snapshot_tree(repo_path)
    changed_paths = _bracket_paths(repo_path, base_sha, end_tree)
    scoped_paths = [p for p in changed_paths if _scope_matches(p, files)]
    off_scope_paths = [p for p in changed_paths if not _scope_matches(p, files)]
    stat = _diffstat_for_paths(repo_path, base_sha, end_tree, scoped_paths)
    print(json.dumps({"scoped": scoped_paths, "off_scope": off_scope_paths, "stat": stat}))


def _git_status_paths(repo: Path) -> list[str]:
    """Every untracked/modified/staged path from `git status --porcelain` (v1 format: a 2-char
    status code + a space + the path; a rename shows as "old -> new", where only the NEW path
    is the one that matters for "does this path exist under the fence right now")."""
    result = _run_git(repo, ["status", "--porcelain"])
    paths = []
    for line in result.stdout.splitlines():
        if not line.strip():
            continue
        entry_path = line[3:]
        if " -> " in entry_path:
            entry_path = entry_path.split(" -> ", 1)[1]
        paths.append(entry_path)
    return paths


def cmd_fence_uncommitted(path, item_id):
    """G52 (recurring orchestrator process failure, hit twice 2026-07-07): a VERIFIED item's own
    declared fence should always be fully committed — a builder's new file (a new test file,
    most dangerously) that never lands in a commit is invisible to the clean-tree gate (it
    materializes committed HEAD; a file that was never committed at all trivially satisfies "the
    committed tree is clean" without ever failing). This verb derives completeness from the
    item's own fence directly (git status --porcelain, filtered by the SAME _scope_matches
    prefix logic T4/S6-4b already use) rather than trusting an orchestrator's own hand-listed
    file set for `git add` — the identical single-source-of-truth discipline G49/G50 already
    apply to enforcement-layer files, carried here to commit completeness.

    Prints {"dirty": [...], "warned": [...], "available": bool} as JSON (available=False only
    when this ledger's own directory isn't inside a git repo at all — nothing to check, not a
    violation) and exits nonzero when dirty is non-empty, so both a human and an automated sweep
    can use this one verb.

    EXCLUDES the campaign ledger itself (dev/campaigns/*.toml, plus .claude/ledger-diffs/,
    .claude/state/, dev/campaigns/traces/ — the SAME _attest_excluded() category already used to
    scope ATTEST diffstats) from the BLOCKING dirty check, but reports it under "warned" instead
    of dropping it silently. Found necessary in practice: any item whose fence names a ledger
    file (e.g. dev/campaigns/seat-substrate.toml) false-fired on EVERY push, because the
    orchestrator appends notes to the ledger continuously — including the very notes recording
    each fix — so it is essentially always dirty at push time. That dirtiness is not a builder's
    forgotten CODE file; it's the ledger's own live, append-only state, governed by the CLI's own
    locking/commit discipline, not the "did this item's code land" question G52 exists to answer.
    But the ledger IS the campaign's own memory (concept #945) — an uncommitted ledger risks real
    loss (one machine failure erases notes that live only in a working tree), so its dirtiness
    stays VISIBLE as a non-blocking warning rather than silently dropped."""
    with open(path, "rb") as handle:
        doc = tomllib.load(handle)
    item = next((row for row in doc.get("items", []) if row.get("id") == item_id), None)
    if not isinstance(item, dict):
        sys.exit(f"error: item {item_id!r} not found in {path}")
    files = [str(f) for f in item.get("files", [])] if isinstance(item.get("files"), list) else []
    repo = _git_repo_root(path)
    if repo is None or not files:
        print(json.dumps({"dirty": [], "warned": [], "available": repo is not None}))
        return
    matched = [p for p in _git_status_paths(repo) if _scope_matches(p, files)]
    dirty = [p for p in matched if not _attest_excluded(p)]
    warned = [p for p in matched if _attest_excluded(p)]
    print(json.dumps({"dirty": dirty, "warned": warned, "available": True}))
    if dirty:
        sys.exit(1)


def _has_activity_after_claim(lines: list[str], s: int, e: int) -> bool:
    """G53: "activity" means any owner-authored ledger content strictly after the item's most
    recent claim/handover line — using LINE POSITION as the "happened after" signal rather than
    comparing note timestamps (ordinary cmd_note lines carry only day-granularity "# [DATE]"
    stamps, not the full at=<ISO-seconds> precision CLAIM/ATTEST-START lines get; but the ledger
    is strictly append-only, so a line's POSITION after the last claim line is an exact,
    reusable proxy for "happened after that claim" regardless of stamp precision).

    The one thing that must NOT count as activity: the ATTEST-START line cmd_claim itself
    appends immediately after every CLAIM line, in the very same call (cmd_handover appends no
    such pairing). Counting it would make every freshly-claimed item look "active" forever,
    silently defeating the entire check — exactly the invisible-idle-claim failure mode G53
    exists to catch."""
    claim_idx = None
    for j in range(s, e):
        if lines[j].lstrip().startswith("#") and "CLAIM: owner=" in lines[j]:
            claim_idx = j
    if claim_idx is None:
        return False
    j = claim_idx + 1
    if j < e and f"] {ATTR_START_NOTE}:" in lines[j]:
        j += 1
    return any(lines[k].strip() for k in range(j, e))


def cmd_stale_claims(path, minutes):
    """G53 (process failure, hit 2026-07-07: S6-5 sat claimed-by-leverage ~1.5h with zero
    progress — the ledger handover recorded but the activating packet never landed, and the
    orchestrator never noticed since a never-activated seat produces no block, no Stop beacon,
    no diff). Distinct from G42/G48 (those poke on a BLOCKED seat mid-work); this is the
    NEVER-ACTIVATED / idle-claimed class.

    Sweeps every in_flight item; for each, finds its most recent claim/handover timestamp (reusing
    _last_claim_at — the SAME last-claim-wins scan cmd_release_stale already trusts, not
    re-derived here) and flags it when BOTH hold: the claim is at least `minutes` old, AND
    _has_activity_after_claim finds nothing appended since. Same single-source-of-truth
    discipline as G49/G50/G52: derived entirely from the ledger's own claim/note lines, never
    from a hand-tracked "last I heard from them" side-channel.

    Deliberately does NOT cross-check a git diff for "real" activity: an owner can be deep in
    genuine, uncommitted work with zero ledger notes yet, and a diff-based check would have to
    reason about uncommitted/shared state — the exact false-positive class G52 already hit and
    had to design around. Ledger notes are cheap for an owner to post ("starting", "still going")
    and are the intended low-cost signal; silence in the ledger is the thing this check watches.

    Prints one JSON object per stale item ({"id", "owner", "claimed_at", "minutes_idle"}), one
    per line, then a summary line, and exits nonzero when any item is stale — so this composes
    as a periodic/pre-flight check the same way fence-uncommitted does."""
    lines = _read(path)
    now = datetime.now(timezone.utc)
    stale = []
    for item_id, s, e in _blocks(lines):
        status = _block_status(lines, s, e)
        if status != "in_flight":
            continue
        claimed_at = _last_claim_at(lines, s, e)
        if claimed_at is None:
            continue
        idle_minutes = (now - claimed_at).total_seconds() / 60.0
        if idle_minutes < minutes:
            continue
        if _has_activity_after_claim(lines, s, e):
            continue
        stale.append({
            "id": item_id,
            "owner": _owned_owner(lines, s, e),
            "claimed_at": claimed_at.isoformat().replace("+00:00", "Z"),
            "minutes_idle": round(idle_minutes, 1),
        })
    for row in stale:
        print(json.dumps(row))
    print(f"stale-claims: {len(stale)} item(s) claimed >= {minutes}m ago with no owner activity")
    if stale:
        sys.exit(1)


def cmd_seed_episode(path, out_path):
    """S6-9 (design ruling: per-episode FRESH LEDGER, not relaxing the canonical constraint —
    relaxing it would weaken the journal/notify-poke gates every producer relies on, S6-2/S6-4/
    S6-8's own finding). Reads `path` (the campaign's own current ledger) and writes a FRESH
    ledger at `out_path` carrying each item's own id/phase/title/files/status/verify EXACTLY as
    they stand right now — "the item rows at their episode-start status", never forced to a fixed
    status, so an episode that starts mid-campaign (some items already done/verified) seeds
    faithfully. Dated notes are deliberately NOT copied: the episode writes its own fresh history
    from a clean slate, and a seeded ledger is a throwaway, cleaned-up-after file (S6-9's own
    ruling), not a permanent record needing its ancestry preserved.

    out_path MUST itself be canonical (dev/campaigns/, the same directory manifest.py lives in)
    for the episode's OWN future claim/note/set-status calls against it to journal at all
    (_is_canonical_ledger) — checked here so a caller's mistaken non-canonical target fails loud
    at seed time, not silently much later when the episode's own journal writes go missing.
    """
    out = Path(out_path)
    if not _is_canonical_ledger(str(out)):
        sys.exit(
            f"error: seed-episode's out_path must be canonical (in {Path(__file__).resolve().parent}): {out}"
        )
    with open(path, "rb") as handle:
        doc = tomllib.load(handle)
    campaign = doc.get("campaign", {}) if isinstance(doc.get("campaign"), dict) else {}
    items = doc.get("items", []) if isinstance(doc.get("items"), list) else []

    lines = [
        "[campaign]\n",
        f'name = {_toml_basic_string(str(campaign.get("name", "episode")))}\n',
        f'next = {_toml_string_list([str(v) for v in campaign.get("next", [])])}\n',
        "\n",
    ]
    for item in items:
        lines.append("[[items]]\n")
        lines.append(f'id = {_toml_basic_string(str(item.get("id", "")))}\n')
        lines.append(f'phase = {_toml_basic_string(str(item.get("phase", "")))}\n')
        lines.append(f'title = {_toml_basic_string(str(item.get("title", "")))}\n')
        lines.append(f'files = {_toml_string_list([str(v) for v in item.get("files", [])])}\n')
        lines.append(f'status = {_toml_basic_string(str(item.get("status", "todo")))}\n')
        lines.append(f'verify = {_toml_basic_string(str(item.get("verify", "")))}\n')
        lines.append("\n")

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("".join(lines), encoding="utf-8")
    print(f"seeded {out} from {path} ({len(items)} items)")


def _attest_excluded(path: str) -> bool:
    normalized = path.replace("\\", "/")
    return (
        any(normalized.startswith(prefix) for prefix in ATTR_EXCLUDED_PATHS)
        or (normalized.startswith("dev/campaigns/") and normalized.endswith(".toml"))
    )


def _is_git_tree_hash(token: str) -> bool:
    token = token.strip().lower()
    return len(token) == 40 and all(ch in HEX_DIGITS for ch in token)


def _attest_line(tag: str, detail: str) -> str:
    return f"# [{date.today().isoformat()}] {tag}: {detail}\n"


def _iso_to_utc(value: str) -> datetime:
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    dt = datetime.fromisoformat(text)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def _utc_to_iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _note_body(line: str) -> str | None:
    if not line.startswith("# ["):
        return None
    parts = line.split("] ", 1)
    if len(parts) != 2:
        return None
    return parts[1].strip()


# MIRRORED from scripts/hydrate-traces.py (its hyphenated filename can't be `import`ed as a
# module, so this stays a hand-kept copy — same behavior, same anchoring law): strips up to
# `limit` leading "[YYYY-MM-DD] " date-group prefixes so a poke-trigger check on caller-supplied
# note text isn't fooled by stray/copy-pasted dating ahead of the real content.
def _looks_like_date_group_prefix(text: str) -> bool:
    if len(text) < 13:
        return False
    if text[0] != "[" or text[11] != "]" or text[12] != " ":
        return False
    date_text = text[1:11]
    if date_text[4] != "-" or date_text[7] != "-":
        return False
    return all(date_text[index].isdigit() for index in (0, 1, 2, 3, 5, 6, 8, 9))


def _strip_leading_date_groups(body: str, limit: int = 3) -> str:
    text = body
    for _ in range(limit):
        if not _looks_like_date_group_prefix(text):
            break
        text = text[13:]
    return text.strip()


def _note_field(body: str, field: str) -> str | None:
    marker = f"{field}="
    idx = body.find(marker)
    if idx == -1:
        return None
    value = body[idx + len(marker):].split()[0].strip().rstrip(",")
    return value or None


def _parse_timestamp_field(body: str, field: str) -> datetime | None:
    value = _note_field(body, field)
    if value is None:
        return None
    try:
        return _iso_to_utc(value)
    except ValueError:
        return None


def _read_item_claim_window(lines: list[str], item_id: str, now: datetime) -> tuple[datetime, datetime, str]:
    s, e = _find(lines, item_id)
    claims: list[tuple[datetime, str]] = []
    for line in lines[s:e]:
        body = _note_body(line)
        if body is None or not body.startswith("CLAIM:"):
            continue
        owner = _note_field(body, "owner")
        at = _parse_timestamp_field(body, "at")
        if owner is None or at is None:
            continue
        claims.append((at, owner))
    if not claims:
        raise ValueError("missing CLAIM note")
    return claims[0][0], now, claims[-1][1]


def _load_pricing(path: Path) -> dict[str, dict[str, object]]:
    with path.open(encoding="utf-8") as handle:
        data = json.load(handle)
    if not isinstance(data, dict):
        raise ValueError("pricing file is not a JSON object")
    pricing: dict[str, dict[str, object]] = {}
    for key, value in data.items():
        if isinstance(key, str) and isinstance(value, dict):
            pricing[key] = value
    return pricing


def _decimal(value: object | None) -> Decimal:
    if value is None:
        raise ValueError("missing decimal value")
    return Decimal(str(value))


def _quantize_money(value: Decimal) -> str:
    return f"{value.quantize(Decimal('0.0001'), rounding=ROUND_HALF_UP):f}"


def _stale_binding_reason(source: str, last_seen: datetime) -> str:
    label = "rollout" if source == "codex" else "transcript"
    return (
        f"bound {label} ends {_utc_to_iso(last_seen)}, before window start "
        f"(stale binding; relaunch/E2 refresh)"
    )


def _read_db_binding(db_path: Path, owner: str) -> tuple[str, str | None, str | None, str | None]:
    with sqlite3.connect(str(db_path)) as conn:
        row = conn.execute(
            """
            SELECT provider, claude_session_id, codex_rollout_path, claude_project_dir
            FROM bindings
            WHERE tmux_session = ?
            ORDER BY updated_at DESC, id DESC
            LIMIT 1
            """,
            (owner,),
        ).fetchone()
    if row is None:
        raise LookupError(f"no binding for tmux_session={owner}")
    provider, claude_session_id, codex_rollout_path, claude_project_dir = row
    if not isinstance(provider, str):
        raise ValueError("binding provider missing")
    return provider, claude_session_id, codex_rollout_path, claude_project_dir


def _codex_usage_window(path: Path, start: datetime, end: datetime) -> tuple[str, int, int, int, bool]:
    current_model: str | None = None
    samples: list[tuple[datetime, str, int, int, int]] = []
    first_seen: datetime | None = None
    with path.open(encoding="utf-8") as handle:
        for raw in handle:
            raw = raw.strip()
            if not raw:
                continue
            data = json.loads(raw)
            if not isinstance(data, dict):
                continue
            ts_raw = data.get("timestamp")
            if not isinstance(ts_raw, str):
                continue
            ts = _iso_to_utc(ts_raw)
            payload = data.get("payload")
            if data.get("type") == "turn_context" and isinstance(payload, dict):
                model = payload.get("model")
                if isinstance(model, str) and model.strip():
                    current_model = model.strip()
                continue
            if data.get("type") != "event_msg" or not isinstance(payload, dict) or payload.get("type") != "token_count":
                continue
            info = payload.get("info")
            if not isinstance(info, dict):
                raise ValueError("codex token_count missing info")
            totals = info.get("total_token_usage")
            if not isinstance(totals, dict):
                raise ValueError("codex token_count missing totals")
            if current_model is None:
                raise ValueError("codex model unavailable")
            samples.append(
                (
                    ts,
                    current_model,
                    int(totals.get("input_tokens", 0)),
                    int(totals.get("cached_input_tokens", 0)),
                    int(totals.get("output_tokens", 0)),
                )
            )
            if first_seen is None or ts < first_seen:
                first_seen = ts
    if not samples:
        raise ValueError("codex transcript has no token_count entries")

    baseline = None
    window_last = None
    window_models: set[str] = set()
    for sample in samples:
        ts, model, input_total, cached_total, output_total = sample
        if ts < start:
            baseline = sample
        if start <= ts <= end:
            window_last = sample
            window_models.add(model)

    if len(window_models) > 1:
        models = ", ".join(sorted(window_models))
        raise ValueError(f"mixed models in window: {models}")

    if samples[-1][0] < start:
        raise ValueError(_stale_binding_reason("codex", samples[-1][0]))

    if window_models:
        model = next(iter(window_models))
    else:
        model = samples[-1][1]

    if window_last is None:
        return model, 0, 0, 0, first_seen is not None and first_seen > start

    baseline_input = baseline[2] if baseline is not None else 0
    baseline_cached = baseline[3] if baseline is not None else 0
    baseline_output = baseline[4] if baseline is not None else 0
    input_total = window_last[2] - baseline_input
    cached_total = window_last[3] - baseline_cached
    output_total = window_last[4] - baseline_output
    partial = first_seen is not None and first_seen > start
    return model, input_total, cached_total, output_total, partial


def _claude_usage_window(path: Path, start: datetime, end: datetime) -> tuple[str, int, int, int, int, bool]:
    samples: list[tuple[datetime, str, int, int, int, int]] = []
    first_seen: datetime | None = None
    with path.open(encoding="utf-8") as handle:
        for raw in handle:
            raw = raw.strip()
            if not raw:
                continue
            data = json.loads(raw)
            if not isinstance(data, dict):
                continue
            ts_raw = data.get("timestamp")
            if not isinstance(ts_raw, str):
                continue
            ts = _iso_to_utc(ts_raw)
            message = data.get("message")
            usage = data.get("usage")
            if not isinstance(message, dict) or not isinstance(usage, dict):
                continue
            if message.get("role") != "assistant":
                continue
            model = message.get("model")
            if not isinstance(model, str) or not model.strip():
                raise ValueError("claude model unavailable")
            samples.append(
                (
                    ts,
                    model.strip(),
                    int(usage.get("input_tokens", 0)),
                    int(usage.get("cache_creation_input_tokens", 0)),
                    int(usage.get("cache_read_input_tokens", 0)),
                    int(usage.get("output_tokens", 0)),
                )
            )
            if first_seen is None or ts < first_seen:
                first_seen = ts
    if not samples:
        raise ValueError("claude transcript has no assistant entries")

    window_samples = [sample for sample in samples if start <= sample[0] <= end]
    window_models = {sample[1] for sample in window_samples}
    if len(window_models) > 1:
        models = ", ".join(sorted(window_models))
        raise ValueError(f"mixed models in window: {models}")

    if samples[-1][0] < start:
        raise ValueError(_stale_binding_reason("claude", samples[-1][0]))

    if window_models:
        model = next(iter(window_models))
    else:
        model = samples[-1][1]

    input_total = sum(sample[2] for sample in window_samples)
    creation_total = sum(sample[3] for sample in window_samples)
    read_total = sum(sample[4] for sample in window_samples)
    output_total = sum(sample[5] for sample in window_samples)
    partial = first_seen is not None and first_seen > start
    return model, input_total, creation_total, read_total, output_total, partial


def _cost_note(path: str, item_id: str, db_path: Path, pricing_path: Path, now: datetime) -> str:
    lines = _read(path)
    try:
        start_ts, end_ts, owner = _read_item_claim_window(lines, item_id, now)
    except Exception as exc:
        return f"COST: {item_id} unavailable — {str(exc).splitlines()[0] or exc.__class__.__name__}"

    try:
        provider, claude_session_id, codex_rollout_path, claude_project_dir = _read_db_binding(db_path, owner)
    except Exception as exc:
        return f"COST: {item_id} unavailable — {str(exc).splitlines()[0] or exc.__class__.__name__}"

    try:
        pricing = _load_pricing(pricing_path)
    except Exception as exc:
        return f"COST: {item_id} unavailable — {str(exc).splitlines()[0] or exc.__class__.__name__}"

    try:
        if provider == "codex":
            if not codex_rollout_path:
                raise ValueError("codex rollout path missing")
            transcript_path = Path(codex_rollout_path).expanduser()
            model, input_total, cached_total, output_total, partial = _codex_usage_window(transcript_path, start_ts, end_ts)
            row = pricing.get(model)
            if not isinstance(row, dict):
                raise ValueError(f"pricing missing model {model}")
            input_cost = _decimal(row.get("input_cost_per_token"))
            cache_cost = _decimal(row.get("cache_read_input_token_cost", row.get("input_cost_per_token")))
            output_cost = _decimal(row.get("output_cost_per_token"))
            total_cost = (
                Decimal(input_total) * input_cost
                + Decimal(cached_total) * cache_cost
                + Decimal(output_total) * output_cost
            )
            note = (
                f"COST: {item_id} in={input_total + cached_total} out={output_total} "
                f"${_quantize_money(total_cost)} model={model} window={_utc_to_iso(start_ts)}..{_utc_to_iso(end_ts)}"
            )
            if partial:
                note += " partial=true"
            return note
        if provider == "claude":
            if not claude_project_dir or not claude_session_id:
                raise ValueError("claude transcript path missing")
            transcript_path = Path(claude_project_dir).expanduser() / f"{claude_session_id}.jsonl"
            model, input_total, creation_total, read_total, output_total, partial = _claude_usage_window(
                transcript_path, start_ts, end_ts
            )
            row = pricing.get(model)
            if not isinstance(row, dict):
                raise ValueError(f"pricing missing model {model}")
            input_cost = _decimal(row.get("input_cost_per_token"))
            creation_cost = _decimal(row.get("cache_creation_input_token_cost", row.get("input_cost_per_token")))
            read_cost = _decimal(row.get("cache_read_input_token_cost", row.get("input_cost_per_token")))
            output_cost = _decimal(row.get("output_cost_per_token"))
            total_cost = (
                Decimal(input_total) * input_cost
                + Decimal(creation_total) * creation_cost
                + Decimal(read_total) * read_cost
                + Decimal(output_total) * output_cost
            )
            note = (
                f"COST: {item_id} in={input_total + creation_total + read_total} out={output_total} "
                f"${_quantize_money(total_cost)} model={model} window={_utc_to_iso(start_ts)}..{_utc_to_iso(end_ts)}"
            )
            if partial:
                note += " partial=true"
            return note
        raise ValueError(f"unsupported provider {provider}")
    except Exception as exc:
        return f"COST: {item_id} unavailable — {str(exc).splitlines()[0] or exc.__class__.__name__}"


def cmd_cost(path, item_id, db_path=DEFAULT_WORKSPACES_DB, pricing_path=DEFAULT_LITELLM_PRICING, now_text=None):
    if now_text is None:
        now = datetime.now(timezone.utc)
    else:
        now = _iso_to_utc(now_text)

    def mutate(lines):
        note = _cost_note(path, item_id, Path(db_path), Path(pricing_path), now)
        s, e = _find(lines, item_id)
        body = [f"# [{date.today().isoformat()}] {note}\n"]
        print(note)
        return _append_block_lines(lines, s, e, body)

    _locked_rewrite(path, mutate)


def _capture_in_flight_attribution(path: str) -> tuple[str | None, str]:
    try:
        repo = _git_repo_root(path)
        if repo is None:
            return None, "git root unavailable"
        tree = _snapshot_tree(repo)
        return tree, f"tree={tree} at={_utc_iso_seconds()}"
    except (subprocess.CalledProcessError, OSError) as exc:
        return None, str(exc).splitlines()[0] or exc.__class__.__name__


def _done_attribution(path: str, item_id: str) -> tuple[str | None, str]:
    try:
        lines = _read(path)
        s, e = _find(lines, item_id)
        start_tree = None
        for line in reversed(lines[s:e]):
            payload = line.partition(f"{ATTR_START_NOTE}:")[2].strip()
            if not payload:
                continue
            if payload.startswith("tree="):
                tail = payload.partition("tree=")[2].strip()
                candidate = tail.split()[0].strip() if tail else ""
                if _is_git_tree_hash(candidate):
                    start_tree = candidate
                    break
        if not start_tree:
            return None, "Attribution unavailable: missing in_flight tree snapshot"

        with open(path, "rb") as f:
            doc = tomllib.load(f)
        item = next((row for row in doc.get("items", []) if row.get("id") == item_id), None)
        if not isinstance(item, dict):
            return None, "Attribution unavailable: item missing from manifest"
        scopes = [str(s) for s in item.get("files", [])] if isinstance(item.get("files"), list) else []
        repo = _git_repo_root(path)
        if repo is None:
            return None, "Attribution unavailable: git root unavailable"
        end_tree = _snapshot_tree(repo)
        changed_paths = _bracket_paths(repo, start_tree, end_tree)
        scoped_paths = [p for p in changed_paths if _scope_matches(p, scopes)]
        off_scope_paths = [p for p in changed_paths if not _scope_matches(p, scopes)]
        sidecar_rel = _sidecar_rel_path(item_id)
        if scoped_paths:
            sidecar_abs = repo / sidecar_rel
            sidecar_abs.parent.mkdir(parents=True, exist_ok=True)
            sidecar_abs.write_text(
                _diff_for_paths(repo, start_tree, end_tree, scoped_paths),
                encoding="utf-8",
            )
            sidecar_display = sidecar_rel.as_posix()
            _atomic_write_json(repo / _sidecar_files_rel_path(item_id), {"scoped_paths": scoped_paths})
        else:
            sidecar_display = "none"
        stat = " | ".join(_diffstat_for_paths(repo, start_tree, end_tree, scoped_paths))
        note = (
            f"{ATTR_NOTE}: scoped diffstat: {stat}; sidecar: {sidecar_display}; "
            f"off-scope: {_format_path_list(off_scope_paths)}"
        )
        return end_tree, note
    except (subprocess.CalledProcessError, OSError, ValueError) as exc:
        return None, f"Attribution unavailable: {str(exc).splitlines()[0] or exc.__class__.__name__}"


def _attested_scoped_files(path: str, item_id: str) -> list[str] | None:
    """FENCE-AUTONARROW: the exact scoped-file list _done_attribution persisted for this item
    (from whichever transition last computed it -- done, or a done-skipping verified). None
    when unavailable (attribution never ran, or nothing was scoped) -- callers must leave the
    existing fence untouched rather than narrow to an absent/empty list."""
    repo = _git_repo_root(path)
    if repo is None:
        return None
    try:
        data = json.loads((repo / _sidecar_files_rel_path(item_id)).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    scoped = data.get("scoped_paths") if isinstance(data, dict) else None
    if not isinstance(scoped, list):
        return None
    cleaned = [p for p in scoped if isinstance(p, str) and p.strip()]
    return cleaned or None


def _toml_basic_string(value: str) -> str:
    return json.dumps(value)


def _toml_string_list(values: list[str]) -> str:
    return "[" + ", ".join(_toml_basic_string(v) for v in values) + "]"


def _replace_item_field(lines: list[str], s: int, e: int, field: str, new_value: str, note_tag: str):
    for j in range(s, e):
        lhs, sep, _rhs = lines[j].partition("=")
        if not sep or lhs.strip() != field:
            continue
        old_line = lines[j].rstrip("\n")
        if field == "files" and ("[" not in old_line or "]" not in old_line):
            sys.exit("error: unsupported multiline files field")
        new_line = f"{lhs.rstrip()} = {new_value}\n"
        lines[j] = new_line
        note = (
            f"# [{date.today().isoformat()}] {note_tag}: "
            f"{old_line} -> {new_line.rstrip()} (by=orchestrator implied; no flag)\n"
        )
        return _append_block_lines(lines, s, e, [note])
    sys.exit(f"error: item has no {field} line")


def _parse_single_flag(rest: list[str], flag: str) -> str:
    value = None
    i = 0
    while i < len(rest):
        token = rest[i]
        if token != flag:
            sys.exit(f"error: unexpected argument {token!r}")
        if value is not None:
            sys.exit(f"error: {flag} may appear only once")
        if i + 1 >= len(rest):
            sys.exit(f"error: {flag} requires a value")
        value = rest[i + 1]
        i += 2
    if value is None:
        sys.exit(f"error: {flag} requires a value")
    return value


def _pop_bare_flag(rest: list[str], flag: str) -> tuple[list[str], bool]:
    """Extract a value-less boolean switch (e.g. --override-retired) from an argv tail
    BEFORE it reaches _parse_single_flag/_split_two_flags, which reject any token they do
    not recognize — every occurrence is removed so a caller providing the flag twice still
    sees a clean remainder, and the return says whether it was present at all."""
    present = flag in rest
    return [token for token in rest if token != flag], present


def _split_two_flags(rest: list[str], flag_a: str, flag_b: str) -> tuple[list[str], list[str]]:
    """Partition an argv tail carrying exactly two distinct flags (any order) into two
    single-flag groups, so each can then go through _parse_single_flag for the strict
    'appears exactly once' check — an unrecognized token refuses immediately."""
    group_a: list[str] = []
    group_b: list[str] = []
    i = 0
    while i < len(rest):
        token = rest[i]
        if token not in (flag_a, flag_b):
            sys.exit(f"error: unexpected argument {token!r}")
        if i + 1 >= len(rest):
            sys.exit(f"error: {token} requires a value")
        (group_a if token == flag_a else group_b).extend([token, rest[i + 1]])
        i += 2
    return group_a, group_b


def _malformed_lease(reason: str) -> None:
    sys.exit(f"error: malformed --lease clause: {reason}")


def _positive_lease_integer(value: str, field: str, maximum: int | None = None) -> int:
    if not value.isascii() or not value.isdecimal():
        _malformed_lease(f"{field} must be a positive decimal integer")
    parsed = int(value)
    if parsed <= 0:
        _malformed_lease(f"{field} must be greater than zero")
    if maximum is not None and parsed > maximum:
        _malformed_lease(f"{field} must be at most {maximum}")
    return parsed


def _parse_lease_clause(raw: str) -> dict[str, str]:
    """Parse the phase-1 declaration grammar; it grants no resources and enforces nothing."""
    if not raw or raw != raw.strip():
        _malformed_lease("the clause must be non-empty and contain no surrounding whitespace")

    declared: dict[str, str] = {}
    for component in raw.split(","):
        if not component or component != component.strip() or component.count("=") != 1:
            _malformed_lease("each component must be exactly key=value with no whitespace")
        key, value = component.split("=", 1)
        if key not in LEASE_FIELDS:
            _malformed_lease(f"unknown field {key!r}")
        if key in declared:
            _malformed_lease(f"duplicate field {key!r}")
        if not value:
            _malformed_lease(f"{key} requires a value")

        if key == "mem":
            if len(value) < 2 or value[-1] not in LEASE_MEMORY_UNITS:
                _malformed_lease("mem must be a positive integer followed by K, M, G, or T")
            _positive_lease_integer(value[:-1], key)
        elif key == "cpu":
            _positive_lease_integer(value, key, maximum=10_000)
        elif key == "wall":
            if len(value) < 2 or value[-1] not in LEASE_WALL_UNITS:
                _malformed_lease("wall must be a positive integer followed by s, m, h, or d")
            _positive_lease_integer(value[:-1], key)
        elif key == "tier":
            if value not in LEASE_TIERS:
                _malformed_lease("tier must be A, B, or C")
        else:
            _positive_lease_integer(value, key)
        declared[key] = value
    return declared


def _seatd_connection_material_for_owner_check() -> tuple[Path, str] | None:
    """(socket_path, token) if THIS process has seatd auth material (TORAD_SEAT_TOKEN) and a
    live seatd.sock, else None — mirrors _seatd_verified_seat's own reachability check above,
    but for probing an ARBITRARY owner's liveness rather than verifying this process's own
    identity, so it does not require TORAD_SEAT (a caller checking someone else's claim need
    not itself be a seatd-spawned seat). None is not a probe failure: it means seatd plainly
    doesn't apply here, so the caller falls back to the tmux path exactly as before seatd
    existed."""
    token = os.environ.get("TORAD_SEAT_TOKEN")
    if not token:
        return None
    socket_path = _seatd_fleet_root() / "seatd.sock"
    if not socket_path.exists():
        return None
    return socket_path, token


def _owner_alive(session_id: str, _machine_id_value: str | None = None) -> bool:
    """REV2-F3: this answers "may a caller safely treat this owner as dead" — so the two cases where we
    have NO evidence either way (no registry at all; a registry present but no row names this owner) must
    return True (assume alive, fail closed), exactly like the pre-existing tmux-unavailable branch below
    already does. Returning False here is a claim of PROVEN death (a registry row was found AND tmux
    confirmed the session is gone) — absence of a registry is not evidence of absence of a live owner.

    G30: session_id may now be a qualified identity (seat~machine-id). The registry/tmux this
    function checks are LOCAL-only, so a qualifier matching the LOCAL machine strips to the
    bare seat and falls through to the existing check unchanged; a FOREIGN qualifier can never
    be confirmed dead from here (no visibility into another machine's tmux) — same fail-closed
    "assume alive" as every other zero-evidence branch below. An old-format/unqualified owner
    (no '~') is untouched: _split_qualified returns it back with qualifier=None.

    G56 bucket-B: seatd's alive(seat) is tried FIRST when this process has seatd auth material
    (TORAD_SEAT_TOKEN + a live seatd.sock) — the SAME S3-3 dual-path 12_file_fence.py's own
    _resolve_owner_alive already applies for the file-fence liveness check, vendored here so
    this shared primitive gets the identical fix (claim/release-stale/handover all call THIS
    function, not a wrapper). Falls back to the unchanged tmux-registry check whenever seatd
    isn't reachable at all (no token, no socket) — a probe FAILURE (socket connects but errors
    mid-exchange) fails closed directly, it does NOT fall through to the tmux path (REV2-F3:
    once we've committed to asking seatd, a failed answer is itself the fail-closed answer)."""
    bare, qualifier = _split_qualified(session_id)
    if qualifier is not None:
        local_machine_id = _machine_id() if _machine_id_value is None else _machine_id_value
        if qualifier != local_machine_id:
            return True
        session_id = bare
    seatd = _seatd_connection_material_for_owner_check()
    if seatd is not None:
        socket_path, token = seatd
        try:
            return _seatd_alive_probe(session_id, socket_path, token)
        except Exception:
            return True
    return _owner_alive_via_tmux_registry(session_id)


def _owner_alive_via_tmux_registry(session_id: str) -> bool:
    """The pre-seatd tmux-registry check — extracted verbatim (byte-identical logic) from the
    original _owner_alive body. Still the FULL answer whenever seatd isn't reachable at all."""
    registry_dir = Path.home() / ".torad" / "sessions"
    if not registry_dir.is_dir():
        return True
    target = None
    for registry in sorted(registry_dir.glob("*.json")):
        try:
            with registry.open(encoding="utf-8") as handle:
                data = json.load(handle)
        except (OSError, json.JSONDecodeError):
            continue
        if not isinstance(data, dict):
            continue
        seat = data.get("seat") if isinstance(data.get("seat"), str) else ""
        if registry.stem == session_id or seat == session_id:
            target = seat or registry.stem
            break
    if target is None:
        return True
    try:
        # Auto-send liveness probe against the REAL fleet server (delivery path, same class as
        # notify_orchestrator's exemptions). Reviewed exemption (CR-RULES-EXCLUDE-SCOPE).
        # ast-grep-ignore: tmux-test-socket-isolation-python
        proc = subprocess.run(
            ["tmux", "has-session", "-t", target],
            timeout=3,
            capture_output=True,
            text=True,
            check=False,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError):
        return True
    return proc.returncode == 0


def _item_committed_in_git(root: str | Path, item_id: str) -> bool:
    """G50/GATE-SHIELD-COMMIT-TAG-MISS: this repo's commit convention tags a landing commit's
    subject/body with the item's id, either bracketed (e.g. "... [G50]") or as a conventional-
    commit parenthetical scope (e.g. "fix(G50): ..." or "fix(OTHER,G50,OTHER2): ..." — the style
    this repo's own commits actually use most often). Either form is a durable, cheap way to
    answer "has this item's OWN work already landed?" — independent of whatever is dirty on its
    fenced files RIGHT NOW. A verified item that has landed has nothing left for the
    verified-shield to protect; any current dirtiness on its fenced files is necessarily someone
    else's newer, unrelated work. Fails CLOSED (False, meaning "still shield") on any git error or
    on a repo with no commits yet — a wrongly-open gate here would silently disable the V3-wipe
    protection the shield exists for. Matches item_id as a WHOLE TOKEN (word-boundary) in both
    forms, so e.g. "W5" never falsely matches a commit tagging "W53"; a bare mention of item_id in
    running prose (outside brackets or a parenthesized scope) does not count either.

    scope_re is deliberately anchored to the START of the SUBJECT line only (not searched
    anywhere in subject+body) — a false POSITIVE here is the unsafe direction (it LIFTS the
    shield on work that is not actually landed), so it must reject: (a) `git revert`'s default
    message `Revert "fix(ID): ..."`, which contains a bogus (ID) even though the revert un-lands
    the work; (b) body prose like "deferred (see ID) to a follow-up", which is not a commit-scope
    tag at all. Anchoring to `type(...)：` at the very start of the subject rejects both — a
    revert's subject starts with "Revert ", not a bare type word directly followed by "(", and
    body text is never on the subject line. bracket_re stays unanchored (searched anywhere in the
    full message) since a bracketed tag is unambiguous wherever it appears."""
    try:
        # Coarse, git-side pre-filter: any candidate MUST contain the literal id substring
        # somewhere, so a cheap fixed-string grep safely narrows history before the precise
        # (and git-regex-dialect-independent) boundary check runs in Python below.
        proc = subprocess.run(
            ["git", "-C", str(root), "log", "--all", "-F", "--grep", item_id, "--format=%B%x1e"],
            capture_output=True,
            text=True,
            timeout=3,
            check=False,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError):
        return False
    if proc.returncode != 0:
        return False
    escaped = re.escape(item_id)
    bracket_re = re.compile(r"\[" + escaped + r"\]")
    scope_re = re.compile(r"^[A-Za-z]+\([^()]*\b" + escaped + r"\b[^()]*\)!?:")
    for message in (proc.stdout or "").split("\x1e"):
        if not message.strip():
            continue
        subject = message.split("\n", 1)[0]
        if bracket_re.search(message) or scope_re.match(subject):
            return True
    return False


def _validate_or_die(path: str, backup: str):
    try:
        with open(path, "rb") as f:
            tomllib.load(f)
    except Exception as exc:  # roll back — never leave a broken manifest
        shutil.copy(backup, path)
        sys.exit(f"error: write produced invalid TOML ({exc}); ROLLED BACK")


def _locked_rewrite(path: str, mutate, *, after_commit=None):
    """flock the manifest, apply mutate(lines)->lines, validate, fsync.

    REV2-F5: optional after_commit(noop: bool) runs BEFORE the flock releases (either right
    after a no-op mutate, or right after the write validates) — this is what lets a caller's
    pointer maintenance (claim/handover/release-stale) happen atomically with the ledger
    mutation instead of in a separate step after _locked_rewrite returns, closing the crash
    window where a pointer file could observably disagree with the ledger's actual claim."""
    with open(path, "a+", encoding="utf-8") as lockf:
        fcntl.flock(lockf, fcntl.LOCK_EX)
        lines = _read(path)
        original = list(lines)
        backup = tempfile.NamedTemporaryFile(
            "w", delete=False, suffix=".manifest.bak", encoding="utf-8"
        )
        backup.writelines(lines)
        backup.close()
        try:
            new = mutate(lines)
            if new == original:
                if after_commit is not None:
                    after_commit(noop=True)
                return
            with open(path, "w", encoding="utf-8") as f:
                f.writelines(new)
                f.flush()
                os.fsync(f.fileno())
            _validate_or_die(path, backup.name)
            if after_commit is not None:
                after_commit(noop=False)
        finally:
            os.unlink(backup.name)
            fcntl.flock(lockf, fcntl.LOCK_UN)


def cmd_list(path, status=None, phase=None):
    lines = _read(path)
    for iid, s, e in _blocks(lines):
        st = ph = ti = ""
        for l in lines[s:e]:
            if m := STATUSLINE.match(l):
                st = m.group(2)
            elif l.startswith("phase"):
                ph = l.split('"')[1] if '"' in l else ""
            elif l.startswith("title"):
                ti = l.split("=", 1)[1].strip().strip('"')[:90]
        owner = _owned_owner(lines, s, e)
        if status and st != status:
            continue
        if phase and ph != phase:
            continue
        # G30: owner may be a qualified seat~machine-id — display stays the friendly seat name.
        suffix = f" @{_display_identity(owner)[:12]}" if st == "in_flight" and owner else ""
        print(f"{iid:6} {ph:2} {st:9} {ti}{suffix}")


def _law_lines(lines: list[str]) -> list[str]:
    return [l.rstrip() for l in lines if l.startswith("# LAW")]


def cmd_laws(path, aggregate=False):
    """Print LAW banner lines. aggregate=True (the no-explicit-path invocation, e.g.
    the SessionStart hook) unions every dev/campaigns/*.toml so each campaign's laws
    reach every session; identical lines (shared laws) are deduped, order stable."""
    files = [path]
    if aggregate:
        d = os.path.dirname(os.path.abspath(path))
        files = sorted(
            os.path.join(d, f) for f in os.listdir(d) if f.endswith(".toml")
        )
    seen = set()
    for f in files:
        for l in _law_lines(_read(f)):
            if l not in seen:
                seen.add(l)
                print(l)


def cmd_get(path, item_id):
    lines = _read(path)
    s, e = _find(lines, item_id)
    sys.stdout.write("".join(lines[s:e]).rstrip() + "\n")
    marker = _retirement_marker(lines, s, e)
    if marker is not None:
        print(f"⚠️  RETIREMENT WARNING: {item_id} carries a retirement marker in its own notes:")
        print(f"    {marker}")
        print("    Do not re-queue without a fresh, explicit operator decision (claim/handover")
        print("    refuse this item unless --override-retired is passed).")
    # Re-anchor the laws on every item read (they bind every agent; get is the
    # one command each work unit starts with). Titles only — full text: `laws`.
    laws = _law_lines(lines)
    if laws:
        titles = []
        for l in laws:
            # Plain string ops, no regex: "# LAW [date]: TITLE (context): body" or "# LAW: TITLE ...".
            body = l.removeprefix("# LAW")
            if body.startswith(" ["):
                body = body.partition("]")[2]
            body = body.removeprefix(":").strip()
            titles.append(body.partition("(")[0].partition(" - ")[0].strip(" :")[:40])
        print(f"-- LAWS ({len(laws)}) bind this work: " + " | ".join(titles))
        print("--   full text: manifest.py laws")


def _campaign_section_bounds(lines: list[str]) -> tuple[int, int] | None:
    """[campaign_header_index, section_end_index) for the [campaign] header — the only
    place a top-level `next = [...]` line can legally live."""
    campaign_idx = None
    for i, line in enumerate(lines):
        if line.strip() == "[campaign]":
            campaign_idx = i
            break
    if campaign_idx is None:
        return None
    end = len(lines)
    for j in range(campaign_idx + 1, len(lines)):
        if HDR.match(lines[j]) or re.match(r"^\[[a-z]", lines[j]):
            end = j
            break
    return campaign_idx, end


def _read_next_ids(path: str) -> list[str]:
    """Parse campaign.next into an actual ID list — reuses tomllib on just the captured
    array literal rather than hand-parsing quoted/comma-separated strings."""
    lines = _read(path)
    bounds = _campaign_section_bounds(lines)
    if bounds is None:
        return []
    start, end = bounds
    for j in range(start + 1, end):
        lhs, sep, rhs = lines[j].partition("=")
        if not sep or lhs.strip() != "next":
            continue
        try:
            parsed = tomllib.loads(f"next = {rhs.strip()}")
        except tomllib.TOMLDecodeError:
            return []
        value = parsed.get("next")
        return [v for v in value if isinstance(v, str)] if isinstance(value, list) else []
    return []


def cmd_set_next(path, ids_text):
    ids = [part.strip() for part in ids_text.split(",") if part.strip()]
    if not ids:
        sys.exit("error: set-next requires at least one non-empty ID")
    new_value = _toml_string_list(ids)

    def mutate(lines):
        bounds = _campaign_section_bounds(lines)
        if bounds is None:
            # A CLI-born ledger (first verb = add) has laws + items but no
            # [campaign] section; insert one after the leading law banner so
            # the birth path stays CLI-only end to end.
            at = len(lines)
            for i, line in enumerate(lines):
                if line.strip() and not line.lstrip().startswith("#"):
                    at = i
                    break
            stem = Path(path).stem
            lines[at:at] = [
                "[campaign]\n",
                f'name = "{stem}"\n',
                f'created = "{date.today().isoformat()}"\n',
                "next = []\n",
                "\n",
            ]
            bounds = (at, at + 4)
        start, end = bounds
        for j in range(start + 1, end):
            lhs, sep, _rhs = lines[j].partition("=")
            if sep and lhs.strip() == "next":
                lines[j] = f"next = {new_value}\n"
                return lines
        return lines[: start + 1] + [f"next = {new_value}\n"] + lines[start + 1 :]

    _locked_rewrite(path, mutate)
    print(f"next -> {new_value}")


def cmd_next(path):
    lines = _read(path)
    joined = "".join(lines[:60])
    if m := re.search(r"next\s*=\s*\[([^\]]*)\]", joined):
        print(f"campaign.next = [{m.group(1)}]")
    cmd_list(path, status="in_flight")


def cmd_set_status(
    path,
    item_id,
    new_status,
    _notify=None,
    _this_file=None,
    _hydrate_script=None,
    _journal_root=None,
    proof=None,
    _proof_public_keys=None,
):
    if new_status not in STATUSES:
        sys.exit(f"error: status must be one of {sorted(STATUSES)}")
    if proof is not None and new_status != "verified":
        sys.exit("error: --proof is valid only for a transition to verified")
    require_proof = new_status == "verified" and _campaign_proof_required()

    notify = _notify_orchestrator if _notify is None else _notify
    attest_lines = []
    if new_status == "in_flight":
        tree, detail = _capture_in_flight_attribution(path)
        attest_lines = [_attest_line(ATTR_START_NOTE, detail if tree else f"unavailable ({detail})")]
    elif new_status == "done":
        _tree, attest_note = _done_attribution(path, item_id)
        attest_lines = [f"# [{date.today().isoformat()}] {attest_note}\n"]
    elif new_status == "verified":
        # G24: close an OPEN bracket on any forward transition — a builder that skipped
        # `done` must not leave an unattributed landing (the P6-K3 hole). Only fires when
        # an ATTEST-START exists with no closing ATTEST line.
        lines0 = _read(path)
        s0, e0 = _find(lines0, item_id)
        block_text = "".join(lines0[s0:e0])
        if f"{ATTR_START_NOTE}: tree=" in block_text and f"] {ATTR_NOTE}: " not in block_text:
            _tree, attest_note = _done_attribution(path, item_id)
            attest_lines = [f"# [{date.today().isoformat()}] {attest_note}\n"]

    # FENCE-AUTONARROW: a verified item should fence only what it actually touched, not
    # whatever directory it was declared against -- a stale broad fence otherwise blocks every
    # foreign seat's unrelated work under that directory forever (BD-2/W1/W5). Only narrows
    # when the ATTEST'd list is known and actually differs from today's fence; never narrows to
    # an absent/empty list (scoped_paths is always a subset of what the old fence matched, so
    # this can only ever shrink the fence, never widen it).
    narrowed_files = None
    if new_status == "verified":
        candidate = _attested_scoped_files(path, item_id)
        if candidate and sorted(candidate) != sorted(_item_files(path, item_id) or []):
            narrowed_files = candidate

    def mutate(lines):
        s, e = _find(lines, item_id)

        def refuse(reason: str):
            _append_manifest_verb_journal_line(
                path,
                "set-status-refused",
                item_id,
                _canonical_seat() or "unknown",
                _this_file,
                _journal_root,
            )
            raise _CampaignProofRefusal(reason)

        if require_proof and proof is None:
            refuse("strict campaign verification requires --proof")
        if proof is not None:
            if attest_lines:
                reason = "signed verification requires the done transition to close attribution before proof signing"
                if require_proof:
                    refuse(reason)
                sys.exit(f"error: {reason}")
            try:
                _validated_campaign_transition_proof(
                    path,
                    item_id,
                    lines[s:e],
                    proof,
                    required_status="done",
                    public_keys=_proof_public_keys,
                )
            except ValueError as exc:
                if require_proof:
                    refuse(f"invalid campaign transition proof: {exc}")
                sys.exit(f"error: invalid campaign transition proof: {exc}")
        for j in range(s, e):
            if m := STATUSLINE.match(lines[j]):
                lines[j] = f"{m.group(1)}{new_status}{m.group(3)}\n"
                if attest_lines:
                    lines = _append_block_lines(lines, s, e, attest_lines)
                if proof is not None:
                    s, e = _find(lines, item_id)
                    lines = _upsert_item_string_field(lines, s, e, CAMPAIGN_PROOF_FIELD, proof)
                if narrowed_files:
                    s, e = _find(lines, item_id)
                    lines = _replace_item_field(
                        lines, s, e, "files", _toml_string_list(narrowed_files), "FENCE-AUTONARROWED"
                    )
                return lines
        sys.exit(f"error: item {item_id!r} has no status line")

    try:
        _locked_rewrite(path, mutate)
    except _CampaignProofRefusal as exc:
        sys.exit(f"error: verified transition refused: {exc}")
    print(f"{item_id} -> {new_status}")
    _append_manifest_verb_journal_line(
        path, "set-status", item_id, _canonical_seat() or "unknown", _this_file, _journal_root,
        _status_value=new_status,
    )
    is_canonical = _is_canonical_ledger(path, _this_file)
    if new_status in {"done", "verified"}:
        _clear_pointers(path, item_id, _this_file)
        if is_canonical:
            _hydrate_traces(path, _hydrate_script=_hydrate_script)
    if new_status == "done" and is_canonical and _should_poke_orchestrator():
        notify(path, f"{item_id} done — see ledger ({Path(path).stem})")
    # GYM-VERDICT-NUDGE (campaign leverage point, #992-C adoption): `verified` IS the review
    # verdict moment, and the trajectory join's crown-jewel field (the typed accept|redo|blocked
    # + honesty edge) is exactly the one nothing else forces into existence — the first live
    # retro run joined 494 trajectories with ZERO typed verdicts. Advisory by design: a reminder
    # that blocks gets bypassed; escalation to a gate is a later operator call with adoption data.
    if new_status == "verified" and is_canonical and not _has_orchestrator_verdict_record(item_id, _journal_root):
        print(
            f"reminder: no typed verdict recorded for {item_id} — the trajectory join's "
            f"orchestrator_verdict field is EMPTY for this item. Record it now (one action "
            f"with the builder poke): python3 dev/campaigns/manifest.py {path} verdict "
            f"{item_id} --outcome accepted|redo|blocked [--gap TEXT] [--contradiction LOCUS]"
        )


def cmd_note(path, item_id, text, _notify=None, _this_file=None, _journal_root=None):
    notify = _notify_orchestrator if _notify is None else _notify

    def mutate(lines):
        s, e = _find(lines, item_id)
        stamp = date.today().isoformat()
        body = [f"# [{stamp}] {l}\n" for l in text.splitlines() if l.strip()]
        return _append_block_lines(lines, s, e, body)

    _locked_rewrite(path, mutate)
    print(f"note appended to {item_id}")
    _append_manifest_verb_journal_line(path, "note", item_id, _canonical_seat() or "unknown", _this_file, _journal_root)
    if (
        _strip_leading_date_groups(text).lstrip().startswith(("BLOCKED", "PREMISE-BLOCK"))
        and _is_canonical_ledger(path, _this_file)
        and _should_poke_orchestrator()
    ):
        notify(path, f"{item_id} BLOCKED — see ledger ({Path(path).stem})")


def cmd_verdict(
    path,
    item_id,
    outcome,
    gap=None,
    contradiction=None,
    env_failure=False,
    _this_file=None,
    _journal_root=None,
):
    """GYM-TRAJECTORY-JOIN (#992-C): capture the orchestrator's validation verdict as STRUCTURED
    fields, not only prose — the accept|redo|blocked edge (#948 §2b redo-not-accept) and the
    honesty signal (#973 §i: report-vs-journal contradiction) become a typed journal record the
    gym trajectory join consumes, while the dated ledger note stays the durable prose home.
    #991 §1 wake rule: the verdict record and the poke-back to the builder are ONE action — this
    verb prints the exact sendMessage packet line so recording and poking cannot drift apart.
    Malformed verdicts are refused at the CLI boundary (the _malformed_lease discipline): they
    reach neither the ledger nor the journal."""
    if outcome not in ("accepted", "redo", "blocked"):
        sys.exit("error: verdict --outcome must be accepted|redo|blocked")
    gap_named = (gap or "").strip() or None
    locus = contradiction.strip() if contradiction is not None else None
    if outcome == "redo" and gap_named is None:
        sys.exit("error: a redo verdict requires --gap <the named gap> (#948 §2b redo-not-accept)")
    if outcome == "accepted" and gap_named is not None:
        sys.exit("error: an accepted verdict must not carry --gap")
    if contradiction is not None and not locus:
        sys.exit("error: --contradiction requires a non-empty locus")

    parts = [f"VERDICT: outcome={outcome}"]
    if gap_named:
        parts.append(f"gap={gap_named}")
    if locus:
        parts.append(f"CONTRADICTION locus={locus}")
    if env_failure:
        parts.append("env-failure=true")
    note_text = " ".join(parts)

    def mutate(lines):
        s, e = _find(lines, item_id)
        stamp = date.today().isoformat()
        return _append_block_lines(lines, s, e, [f"# [{stamp}] {note_text}\n"])

    _locked_rewrite(path, mutate)
    print(f"verdict recorded on {item_id}: {note_text}")
    _append_orchestrator_verdict_journal_line(
        path,
        item_id,
        _canonical_seat() or "unknown",
        outcome,
        gap_named,
        locus,
        bool(env_failure),
        _this_file,
        _journal_root,
    )
    with open(path, encoding="utf-8") as handle:
        lines = handle.readlines()
    s, e = _find(lines, item_id)
    owner = _block_owner(lines, s, e) or "<builder-seat>"
    print(
        f"NOW POKE THE BUILDER (one action with this record, #991 §1): "
        f"mcp__torad-fleet__sendMessage to={owner!r} "
        f"message='{item_id} verdict: {outcome} — see ledger ({Path(path).stem})'"
    )


def cmd_edit_fence(path, item_id, files_text, override_bare_dir=False):
    files = [part.strip() for part in files_text.split(",")]
    if not files_text.strip() or any(not f for f in files):
        sys.exit("error: edit-fence requires non-empty comma-separated files")
    # G51b: a JSON-list-string arg (e.g. '["a.py","b.py"]', a builder's wrong-quoting mistake)
    # splits on its OWN internal comma just like any other input, producing entries like
    # '["a.py"' and '"b.py"]' — neither empty, so the check above lets both through as bogus
    # fence entries (double-encoding). A real file path never starts with '[' or contains '"',
    # so either is a reliable, cheap signal this wasn't comma-separated paths at all.
    if any(f.startswith("[") or '"' in f for f in files):
        sys.exit("error: edit-fence expects comma-separated paths, not a JSON list — e.g. a.py, b.py")
    bare_dirs = _bare_directory_fence_entries(files)
    if bare_dirs and not override_bare_dir:
        sys.exit(_bare_dir_fence_block_error(item_id, bare_dirs))

    def mutate(lines):
        s, e = _find(lines, item_id)
        return _replace_item_field(lines, s, e, "files", _toml_string_list(files), "FENCE-EDITED")

    _locked_rewrite(path, mutate)
    print(f"{item_id} -> files")
    for warning in _directory_fence_overlap_warnings(item_id, files, _live_peer_fences(path, item_id)):
        print(warning)


def cmd_scan_bare_fences(path):
    """G62: non-blocking inventory of every EXISTING item's fence entries that are bare
    directories (same _is_bare_directory_fence single source of truth the write-time block in
    cmd_add/cmd_edit_fence uses) — a burn-down list for retroactive narrowing, never a gate
    against historical data. The write-time check is what stops NEW over-claiming; this just
    makes the existing backlog visible, matching the mesh-escape-guard/substrate-residual-guard
    inventory-mode convention already established in this repo."""
    with open(path, "rb") as f:
        data = tomllib.load(f)
    items = data.get("items") or []
    found: list[tuple[str, list[str]]] = []
    for item in items:
        if not isinstance(item, dict):
            continue
        item_id = item.get("id")
        files = item.get("files")
        if not isinstance(item_id, str) or not isinstance(files, list):
            continue
        bare_dirs = _bare_directory_fence_entries(files)
        if bare_dirs:
            found.append((item_id, bare_dirs))

    if not found:
        print("scan-bare-fences: OK — no bare-directory fence entries.")
        return

    total = sum(len(entries) for _, entries in found)
    print(f"scan-bare-fences: {len(found)} item(s), {total} bare-directory fence entry(ies) (inventory, non-blocking):")
    for item_id, entries in found:
        for entry in entries:
            print(f"  {item_id}: {entry!r}")


def cmd_edit_verify(path, item_id, verify_text):
    if not verify_text.strip():
        sys.exit("error: edit-verify requires a non-empty value")

    def mutate(lines):
        s, e = _find(lines, item_id)
        return _replace_item_field(
            lines, s, e, "verify", _toml_basic_string(verify_text), "VERIFY-EDITED"
        )

    _locked_rewrite(path, mutate)
    print(f"{item_id} -> verify")


def cmd_claim(
    path, item_id, session_id, _alive=None, _registry_dir=None, _this_file=None,
    _canonical=None, _machine_id_value=None, _journal_root=None, override_retired=False,
    lease: dict[str, str] | None = None,
):
    _validate_identity(session_id, "--session")
    alive = _owner_alive if _alive is None else _alive
    tree, detail = _capture_in_flight_attribution(path)
    attest_note = _attest_line(ATTR_START_NOTE, detail if tree else f"unavailable ({detail})")
    claim_identity, requested_session, bare_seat = _claim_identity(
        session_id, _canonical=_canonical, _machine_id_value=_machine_id_value
    )
    noop = False

    def mutate(lines):
        nonlocal noop
        s, e = _find(lines, item_id)
        marker = _retirement_marker(lines, s, e)
        if marker is not None and not override_retired:
            sys.exit(_retirement_block_error(item_id, marker))
        status = _block_status(lines, s, e)
        if status in {"done", "verified"}:
            sys.exit(f"error: item {item_id!r} is {status} — claim only todo/in_flight items")
        owner = _owned_owner(lines, s, e)
        supersedes = None
        if owner and owner == claim_identity:
            noop = True
            return lines
        if owner and owner != claim_identity:
            if alive(owner):
                sys.exit(f"error: item {item_id!r} owned by {owner} (registry+tmux live)")
            supersedes = owner
        _rewrite_status(lines, s, e, "in_flight")
        return _append_block_lines(
            lines,
            s,
            e,
            _claim_note(claim_identity, supersedes=supersedes, requested=requested_session) + [attest_note],
        )

    def after_commit(**_kwargs):
        # REV2-F5: pointer write now happens WHILE the flock is still held (previously ran
        # after _locked_rewrite returned) — closes the crash window where the ledger says
        # owned but no pointer exists yet. Called unconditionally, including on a noop
        # re-claim (idempotent write) — a crash-lost pointer can now self-repair on re-claim
        # instead of staying permanently missing.
        # G30: the pointer/registry machinery is LOCAL-only (bare seat names) — never the
        # qualified (seat~machine-id) recording/comparison identity.
        _write_pointer(path, item_id, bare_seat, _registry_dir=_registry_dir, _this_file=_this_file)

    _locked_rewrite(path, mutate, after_commit=after_commit)
    print(f"{item_id} -> OWN" if noop else f"{item_id} -> in_flight")
    _append_manifest_verb_journal_line(
        path, "claim", item_id, bare_seat, _this_file, _journal_root,
        _policy_lineage=_claim_policy_lineage(session_id),
    )
    if lease is not None:
        _append_lease_declared_journal_line(
            path, item_id, bare_seat, lease, _this_file, _journal_root,
        )
    # G32: same mechanical fence-authoring help as cmd_edit_fence/cmd_packet — a directory
    # fence collision is most actionable right when the item goes in_flight.
    own_files = _item_files(path, item_id)
    if own_files is not None:
        for warning in _directory_fence_overlap_warnings(item_id, own_files, _live_peer_fences(path, item_id)):
            print(warning)
    # G49: a claim is the FIRST moment a builder is about to touch the fenced files — the
    # earliest point a pre-emptive grant can be issued, before any pre-ask-in-chat invisibility.
    _print_enforcement_fence_reminder_if_any(item_id, own_files)


# G30: a FOREIGN-qualified owner can never be confirmed dead from here (no visibility into
# another machine's tmux) — --force-foreign is the operator-invoked escape hatch, and it
# demands a much larger margin than the same-machine tmux check since it is trusting a
# timestamp alone, not live evidence.
FOREIGN_STALE_SECONDS = 24 * 60 * 60


def _last_claim_at(lines: list[str], s: int, e: int) -> datetime | None:
    stamp = None
    for line in lines[s:e]:
        if line.lstrip().startswith("#") and "CLAIM: owner=" in line:
            tail = line.partition("at=")[2].split()
            if tail:
                stamp = tail[0]
    if stamp is None:
        return None
    try:
        parsed = datetime.fromisoformat(stamp.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def cmd_release_stale(
    path, item_id, by, _alive=None, _this_file=None,
    _canonical=None, _machine_id_value=None, force_foreign=False,
):
    _validate_identity(by, "--by")
    alive = _owner_alive if _alive is None else _alive
    by_identity, requested_by, _bare_by = _claim_identity(
        by, _canonical=_canonical, _machine_id_value=_machine_id_value
    )

    def mutate(lines):
        s, e = _find(lines, item_id)
        owner = _owned_owner(lines, s, e)
        if owner is None:
            sys.exit(f"error: item {item_id!r} not claimed")

        # G30: a FOREIGN owner (qualified with a DIFFERENT machine-id than this one) can never
        # satisfy the same-seat "owner == by_identity" identity check below by construction —
        # two machines never share a machine-id, so that check would refuse EVERY foreign
        # release unconditionally, defeating --force-foreign entirely. A foreign owner takes
        # its own, narrower gate instead: no identity match required, no live-evidence
        # possible, so the ONLY signal is claim age plus the explicit --force-foreign opt-in.
        _, owner_qualifier = _split_qualified(owner)
        local_machine_id = _machine_id() if _machine_id_value is None else _machine_id_value
        is_foreign = owner_qualifier is not None and owner_qualifier != local_machine_id
        if is_foreign:
            if not force_foreign:
                sys.exit(
                    f"error: item {item_id!r} owned by {owner} — a FOREIGN machine claim cannot be "
                    f"confirmed dead from here; use --force-foreign if it is truly abandoned "
                    f"(requires claim age > {FOREIGN_STALE_SECONDS // 3600}h)"
                )
            claimed_at = _last_claim_at(lines, s, e)
            age_seconds = (
                (datetime.now(timezone.utc) - claimed_at).total_seconds() if claimed_at is not None else None
            )
            if age_seconds is None or age_seconds < FOREIGN_STALE_SECONDS:
                age_text = "unknown" if age_seconds is None else f"{int(age_seconds)}s"
                sys.exit(
                    f"error: item {item_id!r} owned by {owner} — --force-foreign requires a claim "
                    f"older than {FOREIGN_STALE_SECONDS // 3600}h (age: {age_text})"
                )
            _rewrite_status(lines, s, e, "todo")
            return _append_block_lines(lines, s, e, _released_note(owner, by_identity))

        # Local (or old-format/unqualified) owner: byte-identical to the pre-G30 gate.
        if owner != by_identity:
            sys.exit(_release_stale_mismatch_error(item_id, owner, by_identity, requested_by or by))
        if alive(owner):
            sys.exit(f"error: item {item_id!r} owned by {owner} (registry+tmux live)")
        _rewrite_status(lines, s, e, "todo")
        return _append_block_lines(lines, s, e, _released_note(owner, by_identity))

    def after_commit(**_kwargs):
        # REV2-F5: clear the pointer WHILE still holding the flock — a crash between the
        # ledger write and this call used to leave a stale pointer naming an item the
        # ledger has already released.
        _clear_pointers(path, item_id, _this_file)

    _locked_rewrite(path, mutate, after_commit=after_commit)
    print(f"{item_id} -> todo")


def cmd_handover(
    path, item_id, to_seat, by_seat, _registry_dir=None, _this_file=None,
    _canonical=None, _machine_id_value=None, _journal_root=None, override_retired=False,
):
    """A LIVE owner voluntarily transfers a claim — no status change (stays in_flight), no
    liveness check (the current owner making the call IS the liveness proof). Pointer
    maintenance runs WHILE the flock is still held (REV2-F5; previously ran outside the
    lock, mirroring cmd_claim/cmd_release_stale, G6): clear any pointer naming this
    item+ledger, then write a fresh one for --to (canonical guard + registry-no-match skip
    both apply exactly as they do for a normal claim) — atomically with the ledger write, so
    a crash can no longer leave the OLD owner's pointer (or no pointer at all) while the
    ledger already names --to as owner.

    G30: the recorded new owner is --to qualified with the CALLER's own machine-id (a
    handover recipient is always local to the caller's tmux server) — _write_pointer below
    still receives the bare --to, since the registry/pointer machinery is local-only."""
    _validate_identity(to_seat, "--to")
    _validate_identity(by_seat, "--by")

    def mutate(lines):
        s, e = _find(lines, item_id)
        marker = _retirement_marker(lines, s, e)
        if marker is not None and not override_retired:
            sys.exit(_retirement_block_error(item_id, marker))
        status = _block_status(lines, s, e)
        if status != "in_flight":
            sys.exit(f"error: item {item_id!r} is {status} — handover only in_flight items")
        owner = _owned_owner(lines, s, e)
        by_identity, requested_by, _bare_by = _claim_identity(
            by_seat, _canonical=_canonical, _machine_id_value=_machine_id_value
        )
        if owner != by_identity:
            sys.exit(_handover_mismatch_error(item_id, owner or "nobody", by_identity, requested_by or by_seat))
        to_identity = _qualified_recipient(to_seat, _canonical=_canonical, _machine_id_value=_machine_id_value)
        return _append_block_lines(lines, s, e, _handover_note(to_identity, by_identity))

    def after_commit(**_kwargs):
        _clear_pointers(path, item_id, _this_file)
        _write_pointer(
            path,
            item_id,
            to_seat,
            _registry_dir=_registry_dir,
            _this_file=_this_file,
            _missing_registry_warning=(
                f"warning: handover pointer skipped for {to_seat} (no registry row)"
            ),
        )

    _locked_rewrite(path, mutate, after_commit=after_commit)
    print(f"{item_id} -> handed over to {to_seat}")
    _append_manifest_verb_journal_line(path, "handover", item_id, by_seat, _this_file, _journal_root)
    # G49: a handover moves the claim to a NEW builder who has not yet seen a grant for this
    # item — the same pre-emptive-ask invisibility class as a fresh claim.
    _print_enforcement_fence_reminder_if_any(item_id, _item_files(path, item_id))
    # G54: the ledger claim and the MCP packet are two separate channels that cannot be
    # auto-coupled (hit twice 2026-07-07 — S6-5 leverage, G43 builder: the orchestrator did the
    # handover, got pulled into another thread, and forgot sendMessage, leaving the new owner
    # claimed-but-never-activated until G53's stale-claims detector caught it minutes later).
    # This is the PROACTIVE half — loud and unmissable, same reminder-at-handover pattern as the
    # G49 grant nudge above, which has fired reliably.
    _print_send_packet_reminder(item_id, to_seat)


def cmd_add(path, item_id, phase, title, files, verify, status="todo", override_bare_dir=False):
    bare_dirs = _bare_directory_fence_entries([f.strip() for f in files.split(",")])
    if bare_dirs and not override_bare_dir:
        sys.exit(_bare_dir_fence_block_error(item_id, bare_dirs))

    # D11: a duplicate id in ANOTHER ledger is legal (per-ledger namespaces) but usually a slip —
    # warn at creation so a genuinely-unintended reuse is caught now, not at a confused cold-read.
    # Non-blocking (id reuse is not an error); prints to stderr so stdout stays machine-parseable.
    elsewhere = _ledger_stems_with_id(path, item_id)
    if elsewhere:
        print(
            f"note: id {item_id!r} also exists in {', '.join(elsewhere)} — legal (per-ledger id "
            f"namespace) but confirm it is intentional; add a disambiguation note if so.",
            file=sys.stderr,
        )

    def mutate(lines):
        blocks = list(_blocks(lines))
        for iid, _, _ in blocks:
            if iid == item_id:
                sys.exit(f"error: item {item_id!r} already exists")
        # insert right after the last existing item's block end (before any
        # trailing section such as [baselines], or EOF if none). _blocks()
        # already ends a block at the next [section] header, so this lands in
        # the same place the old "back up over comment lines before
        # [baselines]" scan intended -- but without misattributing the
        # preceding item's own trailing note comments to the new item, which
        # is what a naive "#"-prefix backward scan does when that item's notes
        # butt directly up against [baselines].
        at = blocks[-1][2] if blocks else len(lines)
        files_toml = ", ".join(f'"{f.strip()}"' for f in files.split(","))
        block = (
            f"\n[[items]]\nid = \"{item_id}\"\nphase = \"{phase}\"\n"
            f"title = \"{title}\"\nfiles = [{files_toml}]\n"
            f"status = \"{status}\"\nverify = \"{verify}\"\n"
        )
        return lines[:at] + [block] + lines[at:]

    _locked_rewrite(path, mutate)
    print(f"added {item_id}")


def _packet_eligible(item: dict) -> str | None:
    """The ITEM-level refusal checks a packet emission must pass — extracted so cmd_packet
    (which sys.exit()s on failure) and next-packet's eligibility scan (which just needs a
    reason string per candidate) share ONE definition instead of duplicating the rules."""
    files = item.get("files")
    if not isinstance(files, list) or not files:
        return "item missing files"
    if any(not isinstance(f, str) or not f.strip() for f in files):
        return "item missing files"

    verify = item.get("verify")
    if not isinstance(verify, str) or not verify.strip():
        return "item missing verify"
    if verify.strip().startswith("TBD"):
        return "item verify starts with TBD"

    title = item.get("title") or ""
    try:
        destructive_scan = shlex.split(f"{title} {verify}")
    except ValueError:
        destructive_scan = (title + " " + verify).split()
    if any(token in {"rm", "delete", "clean", "mv", "move"} for token in destructive_scan) and any(
        "*" in f for f in files
    ):
        return "destructive item with un-enumerated fence"

    return None


def _fence_prefix(entry: str) -> str:
    normalized = entry.strip().replace("\\", "/").rstrip("/")
    for suffix in ("/**", "/*", "**", "*"):
        if normalized.endswith(suffix):
            normalized = normalized[: -len(suffix)]
            break
    return normalized.rstrip("/")


def _fences_overlap(files_a: list[str], files_b: list[str]) -> bool:
    """Prefix-intersection fence check: two fences collide if either's normalized path
    prefix equals, or is a path-ancestor of, the other's — the same shape as the mechanical
    file-fence guard (F1), reimplemented here since this fence lives in dev/campaigns/manifest.py
    only and does not import from hook modules."""
    prefixes_a = [_fence_prefix(f) for f in files_a if isinstance(f, str) and f.strip()]
    prefixes_b = [_fence_prefix(f) for f in files_b if isinstance(f, str) and f.strip()]
    for a in prefixes_a:
        for b in prefixes_b:
            if a == b or a.startswith(b + "/") or b.startswith(a + "/"):
                return True
    return False


def _enforcement_layer_protected_lists(_this_file: str | None = None) -> tuple[tuple[str, ...], tuple[str, ...]]:
    """G49: SINGLE SOURCE OF TRUTH — dynamically loads 07_enforcement_layer_ack.py's own
    _PROTECTED/_PROTECTED_BASENAMES instead of hand-maintaining a second list here. A
    hand-copied list drifted the moment 07's own grew: .rules/, .github/workflows/,
    plugin/scripts/validate-hooks.sh, and the detekt-baseline.xml basename were all already
    protected by 07 (and needed grants) but invisible to the first cut of this reminder — the
    same two-lists-that-should-be-one lesson as S1-8's discovery-vs-hand-list. Falls back to an
    empty pair (no match, no reminder) if the hook file cannot be found or fails to load — this
    reminder is advisory, not a gate, so failing OPEN here is correct; 07 itself remains the
    actual FAIL_CLOSED enforcement, unaffected by this fallback."""
    this_file = Path(_this_file) if _this_file is not None else Path(__file__)
    repo_root = this_file.resolve().parents[2]
    hook_path = repo_root / ".claude" / "hooks" / "modules" / "pretooluse" / "07_enforcement_layer_ack.py"
    if not hook_path.is_file():
        return (), ()
    spec = importlib.util.spec_from_file_location("manifest_g49_enforcement_layer_ack", hook_path)
    if spec is None or spec.loader is None:
        return (), ()
    try:
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
    except Exception:
        return (), ()
    return tuple(getattr(module, "_PROTECTED", ())), tuple(getattr(module, "_PROTECTED_BASENAMES", ()))


def _enforcement_fence_hit(files: list[str], _this_file: str | None = None) -> str | None:
    """G49: an item whose fence touches the enforcement layer (07_enforcement_layer_ack.py's own
    _PROTECTED/_PROTECTED_BASENAMES — hooks, ast-grep rules, CI workflows, gate configs, the
    ledger CLI, detekt baselines) needs an orchestrator grant BEFORE the builder starts editing.
    Without a proactive grant the builder either self-blocks silently on its first write or has
    to interrupt the session to pre-ask in chat — both invisible to the enforcement guard's own
    poke path (the class G42 closed for grant blocks, reopened here one level earlier). Returns
    the matching raw fence entry (for the reminder message), or None if nothing in files touches
    the enforcement layer."""
    protected, protected_basenames = _enforcement_layer_protected_lists(_this_file)
    for raw in files:
        if not isinstance(raw, str) or not raw.strip():
            continue
        normalized = _fence_prefix(raw)
        if Path(normalized).name in protected_basenames:
            return raw
        for pref in protected:
            if normalized == pref.rstrip("/") or normalized.startswith(pref):
                return raw
    return None


def _print_enforcement_fence_reminder_if_any(item_id: str, files: list | None) -> None:
    if not files:
        return
    hit = _enforcement_fence_hit(files)
    if hit is None:
        return
    print(
        f"reminder: {item_id} is enforcement-fenced ({hit!r}) — issue an orchestrator grant "
        f"before dispatch: python3 .claude/hooks/enforcement_grant.py grant --minutes 30 "
        f'--reason "{item_id}: <describe the work>"'
    )


def _print_send_packet_reminder(item_id: str, to_seat: str) -> None:
    """G54: the ledger claim (this handover) and the MCP activation packet are two different
    channels — a completed handover leaves the new owner claimed but NOT yet told, silently,
    until G53's stale-claims detector notices minutes later. Loud and unmissable so the missing
    half can't be forgotten quietly, mirroring G49's own grant-nudge reminder just above it."""
    print(
        f"reminder: {item_id} was handed over to {to_seat!r} on the LEDGER only — "
        f"NOW SEND THE PACKET: mcp__torad-fleet__sendMessage to={to_seat!r} "
        f"with {item_id}'s brief, or {to_seat!r} stays claimed but never activated."
    )


def _is_bare_directory_fence(entry: str) -> bool:
    """G32: a 'bare directory' fence entry is the convention's directory marker (a trailing
    '/', per _fence_covers_file's docstring) with no glob suffix — the entry an orchestrator
    reaches for when a whole subtree feels easier to name than enumerating files, and exactly
    the shape that collided with a peer's fence 4+ times in one night (K7, K13, E2, REV1-F1).
    A globbed directory (a/b/**) is a deliberate, already-visible wildcard — not this warning's
    target."""
    normalized = entry.strip().replace("\\", "/")
    return normalized.endswith("/") and "*" not in normalized


def _directory_fence_overlap_warnings(
    item_id: str, files: list[str], live_peers: list[tuple[str, list]]
) -> list[str]:
    """G32: mechanical help, not a gate — WARN (never block) when item_id's fence contains a
    bare directory entry that prefix-overlaps another LIVE (in_flight) item's own bare
    directory fence entry, naming both items and both entries so the orchestrator can narrow
    to file-level fences instead of discovering the collision only when the file-fence hook
    actually blocks a builder."""
    own_dirs = [f for f in files if isinstance(f, str) and _is_bare_directory_fence(f)]
    if not own_dirs:
        return []
    warnings = []
    for peer_id, peer_files in live_peers:
        if peer_id == item_id or not isinstance(peer_files, list):
            continue
        peer_dirs = [f for f in peer_files if isinstance(f, str) and _is_bare_directory_fence(f)]
        for own in own_dirs:
            own_prefix = _fence_prefix(own)
            for peer in peer_dirs:
                peer_prefix = _fence_prefix(peer)
                if (
                    own_prefix == peer_prefix
                    or own_prefix.startswith(peer_prefix + "/")
                    or peer_prefix.startswith(own_prefix + "/")
                ):
                    warnings.append(
                        f"WARNING: directory fence {own!r} overlaps live item {peer_id}'s "
                        f"directory fence {peer!r} — consider narrowing to specific files"
                    )
    return warnings


def _bare_directory_fence_entries(files: list[str]) -> list[str]:
    """G62: the subset of a fence's own entries that are bare directories (reuses G32's own
    _is_bare_directory_fence — one definition of the shape, not a second one). Unlike G32's
    overlap warning (which only fires when a PEER also fences the same subtree), this is
    unconditional: a bare directory over-claims whatever ANY future sibling adds under it,
    live peer or not — the actual failure mode this session hit twice (G58's
    .claude/hooks/modules/pretooluse/, P6-K10's .claude/hooks/modules/ each silently covered
    an untracked file neither item ever authored, tripping the verified-shield on a false
    attribution)."""
    return [f for f in files if isinstance(f, str) and _is_bare_directory_fence(f)]


def _bare_dir_fence_block_error(item_id: str, entries: list[str]) -> str:
    named = "\n".join(f"  - {entry!r}" for entry in entries)
    return (
        f"error: {item_id}'s fence has {len(entries)} bare-directory entry(ies) — fences must "
        "list specific files, never a bare directory (G62: a bare directory over-claims "
        "whatever ANY future sibling adds under it, not just what THIS item wrote):\n"
        f"{named}\n"
        "Fix: list the specific files this item actually touches. If this is a genuine new-"
        "module buildout where the exact file list isn't known yet, re-run with "
        "--override-bare-dir (then narrow the fence via edit-fence as files land)."
    )


def _item_files(path: str, item_id: str) -> list | None:
    """Read-only lookup of one item's own files= fence, for a post-claim directory-overlap
    warning (G32) — mirrors _live_peer_fences' tomllib(rb) read pattern."""
    with open(path, "rb") as f:
        data = tomllib.load(f)
    for item in data.get("items") or []:
        if isinstance(item, dict) and item.get("id") == item_id:
            files = item.get("files")
            return files if isinstance(files, list) else None
    return None


def _live_peer_fences(path: str, exclude_id: str) -> list[tuple[str, list]]:
    """Read-only (b)-mode tomllib load, same pattern as cmd_packet/cmd_next_packet — every
    in_flight item's own fence except exclude_id's, for a directory-overlap check against a
    fence being authored or about to be handed out."""
    with open(path, "rb") as f:
        data = tomllib.load(f)
    items = data.get("items") or []
    peers = []
    for item in items:
        if not isinstance(item, dict) or item.get("id") == exclude_id:
            continue
        if item.get("status") != "in_flight":
            continue
        files = item.get("files")
        if isinstance(files, list):
            peers.append((item.get("id"), files))
    return peers


_FENCE_CHECK_EXCLUDE_DIRS = (".git", "node_modules", ".gradle", "build", "dist")


def _tree_grep_literal(root: Path, pattern: str) -> list[str]:
    """Literal (non-regex) content grep across the working tree for a retired-path string,
    excluding vendored/generated dirs. This is a PATH-LITERAL TEXT sweep over file CONTENTS
    (finding which files still reference a retired path string like "tenets/") — the
    ast-grep-for-code law governs CODE-STRUCTURE search/rewrite, not this kind of plain
    literal-string grep, so `grep -F` is the correct tool here, not ast-grep."""
    exclude_args = []
    for d in _FENCE_CHECK_EXCLUDE_DIRS:
        exclude_args += ["--exclude-dir", d]
    proc = subprocess.run(
        ["grep", "-rlIF", *exclude_args, "--", pattern, "."],
        cwd=str(root),
        capture_output=True,
        text=True,
    )
    if proc.returncode not in (0, 1):  # 1 == no matches, still a valid outcome
        sys.exit(f"error: fence-check grep failed: {proc.stderr.strip()}")
    hits = []
    for line in proc.stdout.splitlines():
        line = line.strip()
        if not line:
            continue
        hits.append(line[2:] if line.startswith("./") else line)
    return sorted(hits)


def _fence_covers_file(entry: str, file_rel: str) -> bool:
    """Per-file fence coverage per the G17 slot's exact spec: entry E covers file F iff
    F == E, or E ends with '/' and F startswith E. Deliberately NOT _fence_prefix/
    _fences_overlap above (or _normalize_scope/_scope_matches) — those strip glob suffixes
    (/**, *) for a DIFFERENT purpose (pairwise fence-overlap / attest scoping) and would
    silently widen coverage for a bare directory entry with no trailing slash. This
    predicate is intentionally the narrower, exact one the slot specifies."""
    return file_rel == entry or (entry.endswith("/") and file_rel.startswith(entry))


def cmd_fence_check(path, item_id, pattern):
    # Item/files lookup mirrors cmd_packet's tomllib-based item lookup (below) rather than
    # reimplementing TOML item search.
    with open(path, "rb") as f:
        data = tomllib.load(f)
    items = data.get("items") or []
    target = None
    for item in items:
        if isinstance(item, dict) and item.get("id") == item_id:
            target = item
            break
    if target is None:
        sys.exit(f"error: item {item_id!r} not found")
    files = target.get("files")
    if not isinstance(files, list):
        sys.exit(f"error: item {item_id!r} has no files= fence")
    fence = [f for f in files if isinstance(f, str)]

    root = _git_repo_root(path) or Path.cwd()
    hits = _tree_grep_literal(root, pattern)

    if not hits:
        print(f"PASS: fence-check {item_id} {pattern!r} — 0 matches in the tree (zero-match: double-check the pattern is correct).")
        return

    uncovered = [f for f in hits if not any(_fence_covers_file(e, f) for e in fence)]
    if uncovered:
        print(f"FAIL: fence-check {item_id} {pattern!r} — {len(uncovered)} of {len(hits)} matching file(s) NOT covered by the fence:")
        for f in uncovered:
            print(f"  - {f}")
        sys.exit(1)

    print(f"PASS: fence-check {item_id} {pattern!r} — all {len(hits)} matching file(s) covered by the fence.")


def cmd_packet(path, item_id):
    with open(path, "rb") as f:
        data = tomllib.load(f)

    campaign = data.get("campaign") or {}
    campaign_name = campaign.get("name")
    if not campaign_name:
        sys.exit("error: manifest missing campaign name")

    items = data.get("items") or []
    item_map = {}
    target = None
    for item in items:
        if isinstance(item, dict) and "id" in item:
            item_map[item["id"]] = item
            if item["id"] == item_id:
                target = item
    if target is None:
        sys.exit(f"error: item {item_id!r} not found")

    reason = _packet_eligible(target)
    if reason is not None:
        sys.exit(f"error: {reason}")

    files = target.get("files")
    verify = target.get("verify")
    assert isinstance(files, list) and isinstance(verify, str)  # _packet_eligible guarantees this

    repo_root_name = (_git_repo_root(path) or Path.cwd()).name
    out = [
        f"PACKET {item_id} — {campaign_name} campaign. ONE item.",
        f'0. pwd must be {repo_root_name} root; if not, STOP and reply "{item_id} blocked — wrong home".',
        f"1. Read the full spec: python3 dev/campaigns/manifest.py {path} get {item_id} — the SLOT note is the complete design, zero design freedom. Laws: manifest.py laws.",
        "1a. Claim identity = your tmux seat name.",
        f"2. Fence = exactly: {', '.join(files)}.",
    ]

    peer_lines = []
    live_peers: list[tuple[str, list]] = []
    block_lines = _read(path)
    for peer_id, s, e in _blocks(block_lines):
        if peer_id == item_id or _block_status(block_lines, s, e) != "in_flight":
            continue
        owner = _owned_owner(block_lines, s, e)
        # G30: owner may be a qualified seat~machine-id — display stays the friendly seat name.
        owner_display = _display_identity(owner) if owner else "unclaimed"
        peer = item_map.get(peer_id, {})
        peer_files = peer.get("files")
        peer_files_text = ", ".join(peer_files) if isinstance(peer_files, list) else ""
        peer_lines.append(f"   - {peer_id} @{owner_display}: {peer_files_text}")
        if isinstance(peer_files, list):
            live_peers.append((peer_id, peer_files))

    if peer_lines:
        out.append("3. COORDINATION FENCES — live peer items, do NOT touch their files:")
        out.extend(peer_lines)

    # G32: mechanical help for the files-not-directories fence-authoring discipline — a bare
    # directory fence entry that collides with another live item's directory fence is exactly
    # the pattern that blocked builders 4+ times in one night (K7, K13, E2, REV1-F1); WARN here
    # rather than only discovering it later as a file-fence hook block.
    for warning in _directory_fence_overlap_warnings(item_id, files, live_peers):
        out.append(warning)

    out.append(
        f"4. Hook block = fix your code or note evidence on {item_id} and stop; never bypass. Premise contradiction = note evidence, stop blocked."
    )
    out.append(
        "4a. READ BUDGET: fence files + files the SLOT cites by exact path; anything else = "
        f"one-line justification note on {item_id} FIRST."
    )
    out.append(
        "4b. Premise-blocks require file:line EVIDENCE; belief that a requirement is unnecessary is not "
        "a skip reason — note the disagreement AND do the work. Every numbered slot point ships or gets "
        "an evidence-cited note."
    )
    out.append("5. NO COMMITS.")
    out.append(
        f"6. Note progress + evidence on {item_id} via the manifest CLI as you work; verify: {verify}; "
        f"set-status {item_id} done only when green — set-status IS the report (it pokes the "
        f'orchestrator mechanically). Optionally reply ONE line after: "{item_id} done — see ledger".'
    )
    sys.stdout.write("\n".join(out) + "\n")


def cmd_dispatch(path, item_id, to_seat, _this_file=None, _journal_root=None):
    """#924 ANTIBODY: the fail-closed dispatch verb. dispatch (sendMessage) was DECOUPLED from
    item-existence — nothing failed when the orchestrator referenced an uncut ID, so the seat
    silently blocked (2026-07-12: two Codex seats idled on non-existent IDs until the operator
    flagged it twice). This makes that UNREPRESENTABLE: an uncut ID is refused nonzero; a real,
    packet-eligible item records the dispatch (claim intent, an audit line that exists only for
    genuinely-dispatched items) and emits the computed packet. Never auto-claims (the seat claims
    under its own identity on pickup) and never pokes (only BLOCKED notes poke)."""
    if not to_seat or not to_seat.strip():
        sys.exit("error: dispatch requires --to <seat>")
    with open(path, "rb") as f:
        data = tomllib.load(f)
    items = data.get("items") or []
    target = next(
        (it for it in items if isinstance(it, dict) and it.get("id") == item_id),
        None,
    )
    # THE ANTIBODY: an uncut ID is refused, nonzero, naming the fix — the failure the whole item exists for.
    if target is None:
        sys.exit(
            f"error: item {item_id!r} not cut — add it first "
            f"(manifest.py add --id {item_id} --phase <P> --title <T> --files <...> --verify <...>); "
            f"refusing to dispatch to a non-existent item"
        )
    # An ill-formed item (missing/placeholder fence or verify) fails closed too — reuse the packet gate.
    reason = _packet_eligible(target)
    if reason is not None:
        sys.exit(f"error: cannot dispatch {item_id!r}: {reason}")

    # Record claim intent BEFORE emitting the packet: the DISPATCH note is the audit signal whose
    # absence would have surfaced the idle-seat bug immediately (a dispatch that referenced nothing).
    def mutate(lines):
        s, e = _find(lines, item_id)
        stamp = date.today().isoformat()
        return _append_block_lines(
            lines, s, e, [f"# [{stamp}] DISPATCH: to={to_seat} (packet emitted)\n"]
        )

    _locked_rewrite(path, mutate)
    _append_manifest_verb_journal_line(
        path, "dispatch", item_id, to_seat, _this_file, _journal_root
    )
    # Emit the computed packet last — the orchestrator's copy-paste payload (cmd_packet unchanged).
    cmd_packet(path, item_id)


def cmd_next_packet(path, session_id, _alive=None, _registry_dir=None, _this_file=None):
    """Self-serve dispatch (#924): pull the first campaign.next candidate that (a) is
    still todo, (b) would pass cmd_packet's own eligibility checks, and (c) is fence-disjoint
    from every in_flight item; claim it (full claim path: liveness, pointer, ATTEST-START)
    and emit its packet. Curation IS the gate — only IDs present in campaign.next are ever
    considered, however eligible an unlisted item might otherwise be."""
    with open(path, "rb") as f:
        data = tomllib.load(f)
    items = data.get("items") or []
    item_map = {item["id"]: item for item in items if isinstance(item, dict) and "id" in item}

    review_debt = sum(1 for item in item_map.values() if item.get("status") == "done")
    if review_debt >= 2:
        sys.exit(f"review debt {review_debt} — orchestrator review required before next dispatch")

    in_flight_entries: list[tuple[str, list]] = []
    for iid, item in item_map.items():
        entry_files = item.get("files")
        if item.get("status") == "in_flight" and isinstance(entry_files, list):
            in_flight_entries.append((iid, entry_files))

    skip_reasons = []
    selected = None
    for candidate_id in _read_next_ids(path):
        item = item_map.get(candidate_id)
        if item is None:
            skip_reasons.append(f"{candidate_id}: not found in manifest")
            continue
        status = item.get("status")
        if status != "todo":
            skip_reasons.append(f"{candidate_id}: status is {status}, not todo")
            continue
        reason = _packet_eligible(item)
        if reason is not None:
            skip_reasons.append(f"{candidate_id}: {reason}")
            continue
        files = item.get("files")
        assert isinstance(files, list)  # _packet_eligible guarantees this
        blocker = next(
            (biid for biid, peer_files in in_flight_entries if _fences_overlap(files, peer_files)),
            None,
        )
        if blocker is not None:
            skip_reasons.append(f"{candidate_id}: fence overlaps with in_flight {blocker}")
            continue
        selected = candidate_id
        break

    if selected is None:
        print(f"queue empty or blocked: {'; '.join(skip_reasons)}")
        return

    cmd_claim(path, selected, session_id, _alive=_alive, _registry_dir=_registry_dir, _this_file=_this_file)
    cmd_packet(path, selected)


def cmd_add_law(path, text):
    """Append a dated # LAW line to the header comment banner (before [campaign])."""

    def mutate(lines):
        stamp = date.today().isoformat()
        body = [f"# LAW [{stamp}]: {l}\n" for l in text.splitlines() if l.strip()]
        at = len(lines)
        for i, l in enumerate(lines):
            if re.match(r"^\[[a-z]", l) or HDR.match(l):
                at = i
                break
        # land after the existing banner, separated by the banner's own style
        return lines[:at] + body + lines[at:]

    _locked_rewrite(path, mutate)
    print("law appended to header")


def cmd_amend_header(path, old, new):
    """Replace substring old->new in exactly ONE header-banner comment line.
    The banner is hook-08-protected against raw edits; this is the sanctioned
    channel for fixing a rotted pointer without hand-editing the ledger."""

    def mutate(lines):
        end = len(lines)
        for i, l in enumerate(lines):
            if re.match(r"^\[[a-z]", l) or HDR.match(l):
                end = i
                break
        hits = [i for i in range(end) if lines[i].startswith("#") and old in lines[i]]
        if len(hits) != 1:
            sys.exit(
                f"error: amend-header needs exactly one banner line containing {old!r}; found {len(hits)}"
            )
        lines[hits[0]] = lines[hits[0]].replace(old, new)
        return lines

    _locked_rewrite(path, mutate)
    print("header line amended")


def cmd_selftest(path):
    """Wraps the real selftest body with TORAD_LEDGER_NOTIFY=off (save/restore) so a selftest
    run — which flips several fixture items to done/BLOCKED — never pokes a real seat.

    S3-4: also redirects TORAD_FLEET_ROOT to an isolated scratch directory for the WHOLE run.
    Several existing fixtures (NOTIFYTEST/POINTERTEST/HANDOVERTEST/etc.) deliberately mark
    themselves canonical (via _this_file=fixture_script pointing at a copy alongside the
    fixture ledger) specifically to exercise the notify/pointer canonical-gated paths — and
    the journal-append gate (_is_canonical_ledger) is the SAME gate. Without this override, a
    selftest run genuinely appends real journal lines (NOTIFYTEST claims, POINTERTEST claims,
    ...) into the operator's REAL $TORAD_FLEET_ROOT/journal — discovered live during S3-4's own
    landing (see that item's ledger note): a plain-Python `PYTEST_CURRENT_TEST`-style guard
    doesn't exist here, so the whole-run env override (matching the TMUX precedent already
    established one line up) is the fix, not a per-call `_journal_root` thread through every
    existing canonical-marked fixture. The scratch dir is removed on exit either way."""
    previous = os.environ.get(NOTIFY_OFF_ENV)
    previous_tmux = os.environ.get("TMUX")
    previous_fleet_root = os.environ.get(FLEET_ROOT_ENV)
    previous_require_proof = os.environ.get(CAMPAIGN_REQUIRE_PROOF_ENV)
    scratch_fleet_root = tempfile.mkdtemp(prefix="manifest-selftest-fleet-root-")
    os.environ[NOTIFY_OFF_ENV] = "off"
    os.environ.pop("TMUX", None)
    os.environ[FLEET_ROOT_ENV] = scratch_fleet_root
    os.environ.pop(CAMPAIGN_REQUIRE_PROOF_ENV, None)
    try:
        _cmd_selftest_body(path)
    finally:
        if previous is None:
            os.environ.pop(NOTIFY_OFF_ENV, None)
        else:
            os.environ[NOTIFY_OFF_ENV] = previous
        if previous_tmux is None:
            os.environ.pop("TMUX", None)
        else:
            os.environ["TMUX"] = previous_tmux
        if previous_fleet_root is None:
            os.environ.pop(FLEET_ROOT_ENV, None)
        else:
            os.environ[FLEET_ROOT_ENV] = previous_fleet_root
        if previous_require_proof is None:
            os.environ.pop(CAMPAIGN_REQUIRE_PROOF_ENV, None)
        else:
            os.environ[CAMPAIGN_REQUIRE_PROOF_ENV] = previous_require_proof
        shutil.rmtree(scratch_fleet_root, ignore_errors=True)


def cmd_remove(path, item_id):
    """Remove an item — the missing undo for a mistaken `add` (there was no way to un-add before,
    D11 CLI hygiene). DELIBERATELY NARROW: only a pristine item may be removed — status 'todo', no
    CLAIM owner, and zero dated notes. Anything that has been claimed, advanced, or annotated is
    real work with history and is refused, so this can never silently delete a live or done item."""
    def mutate(lines):
        s, e = _find(lines, item_id)
        status = _block_status(lines, s, e)
        owner = _block_owner(lines, s, e)
        has_notes = any(line.lstrip().startswith("# [") for line in lines[s:e])
        if status != "todo" or owner is not None or has_notes:
            sys.exit(
                f"error: remove refuses {item_id!r} — only a pristine, unclaimed, note-free "
                f"'todo' item may be removed (got status={status!r}, owner={owner!r}, "
                f"has_notes={has_notes}). Anything with history is real work."
            )
        start = s
        if start > 0 and lines[start - 1].strip() == "":
            start -= 1  # also drop the single blank separator line above the block
        return lines[:start] + lines[e:]

    _locked_rewrite(path, mutate)
    print(f"{item_id} removed")


def _ledger_stems_with_id(this_path, item_id: str) -> list[str]:
    """D11 (add-time): stems of OTHER campaign ledgers that already contain `item_id` — the
    creation-time collision catch. Empty when the id is genuinely new across the campaign set."""
    campaigns_dir = Path(__file__).resolve().parent
    try:
        this_resolved = Path(this_path).resolve()
    except OSError:
        this_resolved = None
    stems: list[str] = []
    for ledger in sorted(campaigns_dir.glob("*.toml")):
        if this_resolved is not None and ledger.resolve() == this_resolved:
            continue
        try:
            lines = ledger.read_text(encoding="utf-8").splitlines(keepends=True)
        except OSError:
            continue
        if any(iid == item_id for iid, _s, _e in _blocks(lines)):
            stems.append(ledger.stem)
    return stems


def _cross_ledger_duplicate_ids() -> dict[str, list[str]]:
    """D11: item ids must be unique ACROSS every campaign ledger, not just within one — a
    collision (two NOTIFY-1s in different .toml files) makes a cold `get`/cross-reference
    ambiguous and a future auto-tool pick the wrong block. There is no id-rename verb; this scan
    catches the NEXT collision at selftest time. Returns {id: [ledger stems...]} for ids that
    appear in >1 ledger. Scans the real dev/campaigns/*.toml beside this file (never a fixture)."""
    from collections import defaultdict

    campaigns_dir = Path(__file__).resolve().parent
    id_to_ledgers: dict[str, set[str]] = defaultdict(set)
    for ledger in sorted(campaigns_dir.glob("*.toml")):
        try:
            lines = ledger.read_text(encoding="utf-8").splitlines(keepends=True)
        except OSError:
            continue
        for item_id, _s, _e in _blocks(lines):
            if item_id:
                id_to_ledgers[item_id].add(ledger.stem)
    return {iid: sorted(stems) for iid, stems in id_to_ledgers.items() if len(stems) > 1}


def _cmd_selftest_body(path):
    tmp = tempfile.NamedTemporaryFile("w", delete=False, suffix=".toml")
    tmp.close()
    shutil.copy(path, tmp.name)
    cmd_add(tmp.name, "ZZTEST", "Z", "selftest item", "a/**, b/**", "n/a")
    cmd_set_status(tmp.name, "ZZTEST", "in_flight")
    cmd_note(tmp.name, "ZZTEST", "note line one")
    cmd_add_law(tmp.name, "selftest law line")
    cmd_amend_header(tmp.name, "selftest law line", "selftest law line AMENDED")
    lines = _read(tmp.name)
    s, e = _find(lines, "ZZTEST")
    blk = "".join(lines[s:e])
    assert 'status = "in_flight"' in blk, blk
    assert "note line one" in blk, blk
    assert "ATTEST-START: unavailable (" in blk, blk
    header = "".join(lines[:s])
    assert "LAW" in header and "selftest law line" in header, header[:400]
    assert any("selftest law line AMENDED" in l for l in _law_lines(lines)), (
        "laws verb misses seeded+amended law"
    )
    with open(tmp.name, "rb") as f:
        tomllib.load(f)

    tmp_fence = tempfile.NamedTemporaryFile("w", delete=False, suffix=".toml")
    tmp_fence.close()
    shutil.copy(path, tmp_fence.name)
    cmd_add(tmp_fence.name, "FENCETEST", "F", "fence item", "a/**", "n/a")
    cmd_edit_fence(tmp_fence.name, "FENCETEST", "one/**, two/**")
    lines = _read(tmp_fence.name)
    s, e = _find(lines, "FENCETEST")
    blk = "".join(lines[s:e])
    assert 'files = ["one/**", "two/**"]' in blk, blk
    assert "FENCE-EDITED" in blk, blk
    with open(tmp_fence.name, "rb") as f:
        tomllib.load(f)

    # G32: a bare directory fence entry overlapping another LIVE item's own bare directory
    # fence entry must WARN (never block) — from both edit-fence and claim, naming both items.
    from contextlib import redirect_stdout
    import io

    tmp_dirfence = tempfile.NamedTemporaryFile("w", delete=False, suffix=".toml")
    tmp_dirfence.close()
    shutil.copy(path, tmp_dirfence.name)
    # G62: these fixtures deliberately test G32's own bare-directory OVERLAP warning, which
    # requires bare-directory fences to exercise at all — override_bare_dir=True opts each one
    # out of G62's own (separate, unconditional) block, matching a real --override-bare-dir use.
    cmd_add(tmp_dirfence.name, "DIRFENCEA", "D", "dir fence claimant", "shared/sub/", "n/a", override_bare_dir=True)
    cmd_claim(tmp_dirfence.name, "DIRFENCEA", "dir-owner-a", _alive=lambda _sid: False)

    cmd_add(tmp_dirfence.name, "DIRFENCEB", "D", "edit-fence overlap target", "elsewhere/", "n/a", override_bare_dir=True)
    edit_warn_buf = io.StringIO()
    with redirect_stdout(edit_warn_buf):
        cmd_edit_fence(tmp_dirfence.name, "DIRFENCEB", "shared/sub/nested/", override_bare_dir=True)
    edit_warn_text = edit_warn_buf.getvalue()
    assert "WARNING" in edit_warn_text, edit_warn_text
    assert "DIRFENCEA" in edit_warn_text, edit_warn_text
    assert "shared/sub/nested/" in edit_warn_text, edit_warn_text

    cmd_add(tmp_dirfence.name, "DIRFENCEC", "D", "claim overlap target", "shared/", "n/a", override_bare_dir=True)
    claim_warn_buf = io.StringIO()
    with redirect_stdout(claim_warn_buf):
        cmd_claim(tmp_dirfence.name, "DIRFENCEC", "dir-owner-c", _alive=lambda _sid: False)
    claim_warn_text = claim_warn_buf.getvalue()
    assert "WARNING" in claim_warn_text, claim_warn_text
    assert "DIRFENCEA" in claim_warn_text, claim_warn_text

    # Non-overlapping directories -> silent (no false positives).
    cmd_add(tmp_dirfence.name, "DIRFENCED", "D", "unrelated dir", "totally/unrelated/", "n/a", override_bare_dir=True)
    no_warn_buf = io.StringIO()
    with redirect_stdout(no_warn_buf):
        cmd_claim(tmp_dirfence.name, "DIRFENCED", "dir-owner-d", _alive=lambda _sid: False)
    assert "WARNING" not in no_warn_buf.getvalue(), no_warn_buf.getvalue()

    # A file (not a bare directory) peer under the same tree -> silent — this warning is
    # scoped to directory-vs-directory collisions only, per the slot.
    tmp_dirfence_file_peer = tempfile.NamedTemporaryFile("w", delete=False, suffix=".toml")
    tmp_dirfence_file_peer.close()
    shutil.copy(path, tmp_dirfence_file_peer.name)
    cmd_add(tmp_dirfence_file_peer.name, "DIRFENCEE", "D", "exact file claimant", "shared/sub/exact.kt", "n/a")
    cmd_claim(tmp_dirfence_file_peer.name, "DIRFENCEE", "dir-owner-e", _alive=lambda _sid: False)
    cmd_add(tmp_dirfence_file_peer.name, "DIRFENCEF", "D", "dir vs file peer", "shared/sub/", "n/a", override_bare_dir=True)
    file_peer_buf = io.StringIO()
    with redirect_stdout(file_peer_buf):
        cmd_claim(tmp_dirfence_file_peer.name, "DIRFENCEF", "dir-owner-f", _alive=lambda _sid: False)
    assert "WARNING" not in file_peer_buf.getvalue(), file_peer_buf.getvalue()

    # G30: qualified seat identity — two machines, SAME seat name, must never collide. Seat
    # names here are deliberately synthetic (never a real campaign seat) since _owner_alive's
    # local branch falls through to the REAL ~/.torad/sessions + real tmux has-session with no
    # injectable override — a real seat name could accidentally hit live state on this machine.
    tmp_g30 = tempfile.NamedTemporaryFile("w", delete=False, suffix=".toml")
    tmp_g30.close()
    shutil.copy(path, tmp_g30.name)
    cmd_add(tmp_g30.name, "G30ITEM", "Q", "qualified identity item", "a/**", "n/a")

    # Machine A claims under seat "g30-fixture-seat" with machine-id aaaaaaaa.
    cmd_claim(
        tmp_g30.name, "G30ITEM", "session-uuid-a",
        _alive=lambda _owner: False,
        _canonical="g30-fixture-seat", _machine_id_value="aaaaaaaa",
    )
    lines = _read(tmp_g30.name)
    s, e = _find(lines, "G30ITEM")
    blk = "".join(lines[s:e])
    assert "CLAIM: owner=g30-fixture-seat~aaaaaaaa" in blk, blk

    # Machine B: the SAME seat name, a DIFFERENT machine-id. Must be FOREIGN — no false OWN, no
    # silent steal — even though a bare-name comparison would have wrongly matched.
    try:
        cmd_claim(
            tmp_g30.name, "G30ITEM", "session-uuid-b",
            _alive=lambda owner: _owner_alive(owner, _machine_id_value="bbbbbbbb"),
            _canonical="g30-fixture-seat", _machine_id_value="bbbbbbbb",
        )
        raise AssertionError("machine B's same-named seat must not silently reclaim machine A's item")
    except SystemExit as exc:
        assert "owned by g30-fixture-seat~aaaaaaaa" in str(exc) and "registry+tmux live" in str(exc), str(exc)
    lines = _read(tmp_g30.name)
    s, e = _find(lines, "G30ITEM")
    blk = "".join(lines[s:e])
    assert "CLAIM: owner=g30-fixture-seat~bbbbbbbb" not in blk, blk

    # _owner_alive in isolation: a foreign qualifier can never be confirmed dead (fail closed).
    assert _owner_alive("g30-fixture-seat~aaaaaaaa", _machine_id_value="bbbbbbbb") is True
    # Single machine (A checking its own claim): strips to the bare seat and asks the real
    # registry+tmux question exactly as an unqualified owner always has — no row named this
    # synthetic seat exists, so this is the same fail-closed "no evidence -> True" as ever.
    assert _owner_alive("g30-fixture-seat~aaaaaaaa", _machine_id_value="aaaaaaaa") is True

    # single-machine behavior byte-identical: an old-format/unqualified owner is untouched —
    # _owner_alive without a '~' never even looks at machine identity.
    assert _owner_alive("plain-unqualified-owner") is True

    # --force-foreign refuses a FRESH foreign claim (age unknown/too young).
    try:
        cmd_release_stale(
            tmp_g30.name, "G30ITEM", "session-uuid-b",
            _alive=lambda owner: _owner_alive(owner, _machine_id_value="bbbbbbbb"),
            _canonical="g30-fixture-seat", _machine_id_value="bbbbbbbb",
            force_foreign=True,
        )
        raise AssertionError("a fresh foreign claim must not release even with --force-foreign")
    except SystemExit as exc:
        assert "--force-foreign" in str(exc), str(exc)

    # Backdate the claim past FOREIGN_STALE_SECONDS -> --force-foreign now succeeds.
    lines = _read(tmp_g30.name)
    s, e = _find(lines, "G30ITEM")
    for j in range(s, e):
        if "CLAIM: owner=g30-fixture-seat~aaaaaaaa" in lines[j]:
            lines[j] = re.sub(r"at=\S+", "at=2000-01-01T00:00:00Z", lines[j])
    Path(tmp_g30.name).write_text("".join(lines), encoding="utf-8")
    cmd_release_stale(
        tmp_g30.name, "G30ITEM", "session-uuid-b",
        _alive=lambda owner: _owner_alive(owner, _machine_id_value="bbbbbbbb"),
        _canonical="g30-fixture-seat", _machine_id_value="bbbbbbbb",
        force_foreign=True,
    )
    lines = _read(tmp_g30.name)
    s, e = _find(lines, "G30ITEM")
    blk = "".join(lines[s:e])
    assert 'status = "todo"' in blk, blk
    assert "CLAIM-RELEASED" in blk, blk

    # Handover qualifies the recipient with the CALLER's own machine-id.
    cmd_claim(
        tmp_g30.name, "G30ITEM", "session-uuid-a",
        _alive=lambda _owner: False,
        _canonical="g30-fixture-seat", _machine_id_value="aaaaaaaa",
    )
    cmd_handover(
        tmp_g30.name, "G30ITEM", "g30-fixture-seat-2", "g30-fixture-seat",
        _canonical="g30-fixture-seat", _machine_id_value="aaaaaaaa",
    )
    lines = _read(tmp_g30.name)
    s, e = _find(lines, "G30ITEM")
    blk = "".join(lines[s:e])
    assert (
        "CLAIM: owner=g30-fixture-seat-2~aaaaaaaa at=" in blk
        and "handover-from=g30-fixture-seat~aaaaaaaa" in blk
    ), blk

    # _machine_id itself: read-or-create, stable across repeated reads, test-injectable path.
    g30_machine_id_dir = Path(tempfile.mkdtemp(prefix="manifest-g30-machine-id-"))
    try:
        g30_machine_id_path = g30_machine_id_dir / "machine-id"
        first = _machine_id(g30_machine_id_path)
        second = _machine_id(g30_machine_id_path)
        assert MACHINE_ID_RE.match(first), first
        assert first == second, (first, second)
    finally:
        shutil.rmtree(g30_machine_id_dir, ignore_errors=True)

    os.unlink(tmp_g30.name)

    # S3-4 (seat-substrate): claim/note/set-status/handover each append a C4 journal line to
    # $TORAD_FLEET_ROOT/journal/events.jsonl (routed here to an isolated scratch root via
    # _journal_root, never the real machine's journal) — the same on-disk contract FleetJournal.kt
    # (S0-5, Kotlin/JS, part of the host module) writes, so both producers interleave on one clock.
    def _assert_valid_journal_line(entry, *, expect_kind=None):
        # Mirrors FleetJournal.kt's validateJournalLine rules exactly (seq non-negative int, ts
        # non-blank str, episodeLabel present as a key (str or None), kind one of the 4 known
        # values) — the PRACTICAL cross-producer proof: a line from either language must satisfy
        # the identical shape check.
        assert isinstance(entry.get("seq"), int) and entry["seq"] >= 0, entry
        assert isinstance(entry.get("ts"), str) and entry["ts"].strip(), entry
        assert "episodeLabel" in entry and (entry["episodeLabel"] is None or isinstance(entry["episodeLabel"], str)), entry
        assert entry.get("kind") in {"manifest_verb", "gate_result", "hook_event", "seatd_lifecycle"}, entry
        if expect_kind is not None:
            assert entry["kind"] == expect_kind, entry
        if entry["kind"] == "manifest_verb":
            for field in ("verb", "itemId", "seat"):
                assert isinstance(entry.get(field), str) and entry[field].strip(), entry
            # GYM-S6-EPISODE-LABEL-STAMP: manifest-verb events are born episode-keyed with the
            # ratified convention episodeLabel == itemId (claim->set-status arc endpoints).
            assert entry["episodeLabel"] == entry["itemId"], entry

    journal_root_dir = Path(tempfile.mkdtemp(prefix="manifest-s34-journal-"))
    # PROD-V3: pin the claim-lane lineage env contract for the S34 fixture claims below.
    previous_seat_model = os.environ.get("TORAD_SEAT_MODEL")
    os.environ["TORAD_SEAT_MODEL"] = "selftest-model"
    try:
        journal_ledger_dir = Path(tempfile.mkdtemp(prefix="manifest-s34-ledger-"))
        journal_fixture = journal_ledger_dir / "fixture.toml"
        shutil.copy(path, journal_fixture)
        journal_fixture_script = journal_ledger_dir / "manifest.py"
        shutil.copy(__file__, journal_fixture_script)

        cmd_add(journal_fixture.as_posix(), "S34ITEM", "S", "journal fixture item", "a/**", "n/a")
        cmd_claim(
            journal_fixture.as_posix(), "S34ITEM", "session-s34",
            _alive=lambda _sid: False, _this_file=journal_fixture_script.as_posix(),
            _canonical="s34-fixture-seat", _machine_id_value="deadbeef",
            _journal_root=journal_root_dir,
        )
        cmd_note(
            journal_fixture.as_posix(), "S34ITEM", "a note",
            _this_file=journal_fixture_script.as_posix(), _journal_root=journal_root_dir,
        )
        cmd_set_status(
            journal_fixture.as_posix(), "S34ITEM", "done",
            _this_file=journal_fixture_script.as_posix(), _journal_root=journal_root_dir,
        )
        cmd_add(journal_fixture.as_posix(), "S34HANDOVER", "S", "handover fixture item", "b/**", "n/a")
        cmd_claim(
            journal_fixture.as_posix(), "S34HANDOVER", "session-s34-owner",
            _alive=lambda _sid: False, _this_file=journal_fixture_script.as_posix(),
            _canonical="s34-fixture-owner", _machine_id_value="deadbeef",
            _journal_root=journal_root_dir,
        )
        cmd_handover(
            journal_fixture.as_posix(), "S34HANDOVER", "s34-fixture-new-owner", "s34-fixture-owner",
            _this_file=journal_fixture_script.as_posix(),
            _canonical="s34-fixture-owner", _machine_id_value="deadbeef",
            _journal_root=journal_root_dir,
        )

        s34_journal_path = journal_root_dir / "journal" / "events.jsonl"
        assert s34_journal_path.is_file(), "expected a journal file to have been created"
        s34_lines = [json.loads(l) for l in s34_journal_path.read_text(encoding="utf-8").splitlines() if l.strip()]
        assert len(s34_lines) == 5, s34_lines
        for entry in s34_lines:
            _assert_valid_journal_line(entry, expect_kind="manifest_verb")
        assert [e["verb"] for e in s34_lines] == ["claim", "note", "set-status", "claim", "handover"], s34_lines
        # seq is strictly monotonic across ALL 5 appends (one shared clock, not one per verb).
        assert [e["seq"] for e in s34_lines] == [0, 1, 2, 3, 4], s34_lines
        assert s34_lines[0]["itemId"] == "S34ITEM" and s34_lines[0]["seat"] == "s34-fixture-seat", s34_lines[0]
        assert s34_lines[4]["itemId"] == "S34HANDOVER" and s34_lines[4]["seat"] == "s34-fixture-owner", s34_lines[4]
        # GYM-S6-EPISODE-LABEL-STAMP: manifest-verb events are born episode-keyed (episodeLabel ==
        # itemId, the claim->set-status arc convention) — the pre-stamp null-label contract is dead.
        assert all(e["episodeLabel"] == e["itemId"] for e in s34_lines), "verb events must be born episode-keyed"
        # PROD-V3: CLAIM lines carry the ledger-lane policy lineage (modelId from the
        # TORAD_SEAT_MODEL env contract pinned above; baseSha = repo HEAD, fail-soft) — the
        # tmux-lane half of #992 gap 4. Non-claim verbs carry NO lineage key.
        claim_lineage = s34_lines[0].get("policyLineage")
        assert isinstance(claim_lineage, dict), s34_lines[0]
        assert claim_lineage.get("modelId") == "selftest-model", claim_lineage
        base_sha = claim_lineage.get("baseSha")
        assert base_sha is None or (isinstance(base_sha, str) and len(base_sha) == 40), claim_lineage
        assert "policyLineage" not in s34_lines[1], "note lines carry no lineage"
        # PROD-LP-KPI-INSTRUMENT: set-status lines carry the status VALUE; other verbs do not.
        assert s34_lines[2]["status"] == "done", s34_lines[2]
        assert "status" not in s34_lines[0], "claim lines carry no status field"

        # Selftest's OWN fixture manipulations (everywhere else in this function) must NEVER
        # pollute the journal — proven by never having passed _journal_root/a canonical
        # _this_file to any of them, combined with the _is_canonical_ledger gate: a scratch
        # ledger copy with no _this_file override is never canonical, so cmd_claim's default
        # (real $TORAD_FLEET_ROOT) path is never even touched by the rest of this function.

        # CROSS-PRODUCER byte-compatibility proof (the real risk named at dispatch): simulate a
        # Kotlin-side FleetJournal.kt append (a DIFFERENT kind, seatd_lifecycle, to also prove
        # cross-KIND interleaving on one clock) landing BETWEEN two manifest.py verbs, then
        # confirm manifest.py's own tail-recovery correctly continues from THAT foreign line's
        # seq rather than its own last-known value.
        simulated_kotlin_line = {
            "seq": s34_lines[-1]["seq"] + 1,
            "ts": "2026-07-07T03:00:00.000Z",
            "episodeLabel": None,
            "kind": "seatd_lifecycle",
            "seat": "s34-fixture-seat",
            "event": "spawn",
        }
        _assert_valid_journal_line(simulated_kotlin_line, expect_kind="seatd_lifecycle")
        with s34_journal_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(simulated_kotlin_line, ensure_ascii=False) + "\n")

        cmd_note(
            journal_fixture.as_posix(), "S34ITEM", "a note after the simulated kotlin append",
            _this_file=journal_fixture_script.as_posix(), _journal_root=journal_root_dir,
        )
        s34_lines_after = [
            json.loads(l) for l in s34_journal_path.read_text(encoding="utf-8").splitlines() if l.strip()
        ]
        assert len(s34_lines_after) == 7, s34_lines_after
        assert s34_lines_after[5] == simulated_kotlin_line, s34_lines_after[5]
        _assert_valid_journal_line(s34_lines_after[5], expect_kind="seatd_lifecycle")
        _assert_valid_journal_line(s34_lines_after[6], expect_kind="manifest_verb")
        assert s34_lines_after[6]["seq"] == simulated_kotlin_line["seq"] + 1, (
            "manifest.py's next append must continue from the FOREIGN (Kotlin-shaped) line's "
            f"seq, not its own last-known value: {s34_lines_after[6]}"
        )

        # GYM-TRAJECTORY-JOIN (#992-C) verdict capture: a redo landing carries its named gap +
        # honesty contradiction as TYPED fields; an accepted landing carries neither; a blocked
        # landing can carry the env-failure mask (#973 sweep-2). Malformed verdicts are refused
        # at the CLI boundary and journal NOTHING (the _malformed_lease discipline).
        cmd_verdict(
            journal_fixture.as_posix(), "S34ITEM", "redo",
            gap="tests missing for the malformed path",
            contradiction="note claims gates green; journal has no gate run",
            _this_file=journal_fixture_script.as_posix(), _journal_root=journal_root_dir,
        )
        cmd_verdict(
            journal_fixture.as_posix(), "S34ITEM", "accepted",
            _this_file=journal_fixture_script.as_posix(), _journal_root=journal_root_dir,
        )
        cmd_verdict(
            journal_fixture.as_posix(), "S34HANDOVER", "blocked",
            gap="premise wrong", env_failure=True,
            _this_file=journal_fixture_script.as_posix(), _journal_root=journal_root_dir,
        )
        verdict_lines = [
            json.loads(l) for l in s34_journal_path.read_text(encoding="utf-8").splitlines() if l.strip()
        ][-3:]
        assert [e["kind"] for e in verdict_lines] == [JOURNAL_KIND_ORCHESTRATOR_VERDICT] * 3, verdict_lines
        redo_line, accepted_line, blocked_line = verdict_lines
        assert redo_line["outcome"] == "redo" and redo_line["gapNamed"], redo_line
        assert redo_line["contradictionFound"] is True and redo_line["contradictionLocus"], redo_line
        assert redo_line["episodeLabel"] == redo_line["itemId"] == "S34ITEM", redo_line
        assert accepted_line["outcome"] == "accepted" and accepted_line["gapNamed"] is None, accepted_line
        assert accepted_line["contradictionFound"] is False, accepted_line
        assert accepted_line["contradictionLocus"] is None, accepted_line
        assert blocked_line["outcome"] == "blocked" and blocked_line["envFailure"] is True, blocked_line
        # seq continues on the ONE shared journal clock straight through the verdict records.
        assert [e["seq"] for e in verdict_lines] == [7, 8, 9], verdict_lines
        # The dated VERDICT note landed as the durable prose home alongside the typed record.
        fixture_text = journal_fixture.read_text(encoding="utf-8")
        assert "VERDICT: outcome=redo gap=tests missing for the malformed path" in fixture_text
        journal_len_before = len(s34_journal_path.read_text(encoding="utf-8").splitlines())
        for bad_kwargs in (
            {"outcome": "maybe"},
            {"outcome": "redo"},
            {"outcome": "accepted", "gap": "should not be here"},
            {"outcome": "redo", "gap": "g", "contradiction": "  "},
        ):
            try:
                cmd_verdict(
                    journal_fixture.as_posix(), "S34ITEM",
                    _this_file=journal_fixture_script.as_posix(), _journal_root=journal_root_dir,
                    **bad_kwargs,
                )
                raise AssertionError(f"malformed verdict must be refused: {bad_kwargs}")
            except SystemExit as exc:
                assert exc.code != 0, bad_kwargs
        assert len(s34_journal_path.read_text(encoding="utf-8").splitlines()) == journal_len_before, (
            "a refused verdict must journal NOTHING"
        )

        # GYM-VERDICT-NUDGE: `verified` with NO typed verdict record prints the one-line
        # reminder naming the verdict command; `verified` on an item whose verdict exists
        # (S34ITEM got one above) stays silent. Advisory only — both transitions succeed.
        import contextlib
        import io

        cmd_add(journal_fixture.as_posix(), "S34NUDGE", "S", "nudge fixture item", "c/**", "n/a")
        nudge_out = io.StringIO()
        with contextlib.redirect_stdout(nudge_out):
            cmd_set_status(
                journal_fixture.as_posix(), "S34NUDGE", "verified",
                _this_file=journal_fixture_script.as_posix(), _journal_root=journal_root_dir,
            )
        assert "no typed verdict recorded for S34NUDGE" in nudge_out.getvalue(), nudge_out.getvalue()
        assert "verdict S34NUDGE --outcome" in nudge_out.getvalue(), nudge_out.getvalue()
        quiet_out = io.StringIO()
        with contextlib.redirect_stdout(quiet_out):
            cmd_set_status(
                journal_fixture.as_posix(), "S34ITEM", "verified",
                _this_file=journal_fixture_script.as_posix(), _journal_root=journal_root_dir,
            )
        assert "no typed verdict recorded" not in quiet_out.getvalue(), (
            "an item with a typed verdict must NOT be nagged: " + quiet_out.getvalue()
        )

        # PROD-LP-KPI-INSTRUMENT: the KPI instrument over the fixture journal + a PURPOSE-BUILT
        # minimal ledger (never the copied real one — assertions must not float with live
        # campaign state). Known state: closed = {S34ITEM done, S34NUDGE verified} (S34HANDOVER
        # stays in_flight); typed verdicts exist for S34ITEM + S34HANDOVER -> coverage 1/2 (the
        # verdicted-but-open handover and the closed-but-unverdicted nudge are BOTH excluded —
        # exactly the drift the instrument exists to expose); both fixture claims carried
        # selftest-model -> lineage 100%; one contradiction verdict above -> yield 1.
        kpi_ledger = journal_ledger_dir / "kpi-fixture.toml"
        kpi_ledger.write_text(
            "\n".join(
                [
                    "[[items]]", 'id = "S34ITEM"', 'status = "done"',
                    "[[items]]", 'id = "S34HANDOVER"', 'status = "in_flight"',
                    "[[items]]", 'id = "S34NUDGE"', 'status = "verified"', "",
                ]
            ),
            encoding="utf-8",
        )
        kpi_bytes_before = s34_journal_path.read_bytes()
        kpi_out = io.StringIO()
        with contextlib.redirect_stdout(kpi_out):
            cmd_gym_kpi(
                journal_fixture.as_posix(), _root=journal_root_dir,
                _now=datetime.now(timezone.utc), _ledger_paths=[kpi_ledger.as_posix()],
            )
        kpi_text = kpi_out.getvalue()
        assert "verdict-coverage:      1/2" in kpi_text, kpi_text
        assert "; 0 via backfill" in kpi_text, kpi_text
        assert "overall 2/2 claims lineage-attributed (100.0%)" in kpi_text, kpi_text
        assert "trajectories-7d:       3 distinct episodes active" in kpi_text, kpi_text
        assert "honesty-yield:         1 typed contradiction record(s)" in kpi_text, kpi_text
        assert s34_journal_path.read_bytes() == kpi_bytes_before, "gym-kpi must be READ-ONLY"

        # PROD-VERDICT-BACKFILL over a purpose-built ledger: S34NUDGE (closed, VERIFIED marker,
        # no typed record) gains a backfilled=true accepted record; S34NOMARK (closed, no marker)
        # stays ungraded; S34NEGATED (closed, only UNVERIFIED/UNBLOCKED prose) must parse as NO
        # marker — the PROD-FIX-PROSE-NEGATION poison class; dry-run writes nothing; the applied
        # run is idempotent.
        backfill_ledger = journal_ledger_dir / "backfill-fixture.toml"
        backfill_ledger.write_text(
            "\n".join(
                [
                    "[[items]]", 'id = "S34NUDGE"', 'status = "verified"',
                    '# [2026-01-03] VERIFIED + LANDED (abc12345)',
                    "[[items]]", 'id = "S34NOMARK"', 'status = "done"',
                    '# [2026-01-03] plain progress note, nothing decisive',
                    "[[items]]", 'id = "S34NEGATED"', 'status = "done"',
                    '# [2026-01-03] gates red, label:UNVERIFIED noted; UNBLOCKED the sibling lane',
                    "[[items]]", 'id = "S34STATUSV"', 'status = "verified"',
                    '# [2026-01-03] landed quietly, no decisive marker in prose',
                    "[[items]]", 'id = "S34REPAIR"', 'status = "done"',
                    '# [2026-01-03] the verified-shield blocked the write mid-flight',
                    '# [2026-01-04] BLOCKED: fence held by a live peer', "",
                ]
            ),
            encoding="utf-8",
        )
        # PROD-BACKFILL-AUDIT-REPAIR fixture: a deliberately WRONG pre-existing backfilled record
        # (the pre-noise-fix parser would have read the shield line as accepted).
        _append_orchestrator_verdict_journal_line(
            journal_fixture.as_posix(), "S34REPAIR", "s34-fixture-seat", "accepted", None, None,
            False, journal_fixture_script.as_posix(), journal_root_dir, _backfilled=True, _basis="prose",
        )
        dry_bytes_before = s34_journal_path.read_bytes()
        dry_out = io.StringIO()
        with contextlib.redirect_stdout(dry_out):
            cmd_verdict_backfill(
                journal_fixture.as_posix(), dry_run=True, _root=journal_root_dir,
                _ledger_paths=[backfill_ledger.as_posix()], _this_file=journal_fixture_script.as_posix(),
            )
        assert "[dry-run] verdict-backfill: 2 backfilled (prose accepted=1" in dry_out.getvalue(), dry_out.getvalue()
        assert "status-verified=1" in dry_out.getvalue(), dry_out.getvalue()
        assert "no-marker-not-verified=2" in dry_out.getvalue(), dry_out.getvalue()
        assert s34_journal_path.read_bytes() == dry_bytes_before, "dry-run must write NOTHING"
        apply_out = io.StringIO()
        with contextlib.redirect_stdout(apply_out):
            cmd_verdict_backfill(
                journal_fixture.as_posix(), dry_run=False, _root=journal_root_dir,
                _ledger_paths=[backfill_ledger.as_posix()], _this_file=journal_fixture_script.as_posix(),
            )
        assert "verdict-backfill: 2 backfilled" in apply_out.getvalue(), apply_out.getvalue()

        def _verdicts_of(item):
            lines_now = [
                json.loads(l) for l in s34_journal_path.read_text(encoding="utf-8").splitlines() if l.strip()
            ]
            return [
                e for e in lines_now
                if e.get("kind") == JOURNAL_KIND_ORCHESTRATOR_VERDICT and e.get("itemId") == item
            ]

        nudge_backfills = _verdicts_of("S34NUDGE")
        assert len(nudge_backfills) == 1 and nudge_backfills[0]["backfilled"] is True, nudge_backfills
        assert nudge_backfills[0]["outcome"] == "accepted" and nudge_backfills[0]["gapNamed"] is None, nudge_backfills
        assert nudge_backfills[0]["basis"] == "prose", nudge_backfills
        statusv = _verdicts_of("S34STATUSV")
        assert len(statusv) == 1 and statusv[0]["basis"] == "status-verified", statusv
        assert statusv[0]["outcome"] == "accepted" and statusv[0]["backfilled"] is True, statusv
        assert not _verdicts_of("S34NEGATED"), "negated prose never mints"
        assert not _verdicts_of("S34NOMARK"), "done-only markerless stays ungraded"
        # --repair: the wrong accepted record is superseded by a corrected blocked one (last-wins);
        # a second repair run corrects nothing further.
        repair_out = io.StringIO()
        with contextlib.redirect_stdout(repair_out):
            cmd_verdict_backfill(
                journal_fixture.as_posix(), repair=True, _root=journal_root_dir,
                _ledger_paths=[backfill_ledger.as_posix()], _this_file=journal_fixture_script.as_posix(),
            )
        assert "corrected=1" in repair_out.getvalue(), repair_out.getvalue()
        repair_records = _verdicts_of("S34REPAIR")
        assert len(repair_records) == 2, repair_records
        assert repair_records[-1]["outcome"] == "blocked" and repair_records[-1]["corrected"] is True, repair_records
        repair2_out = io.StringIO()
        with contextlib.redirect_stdout(repair2_out):
            cmd_verdict_backfill(
                journal_fixture.as_posix(), repair=True, _root=journal_root_dir,
                _ledger_paths=[backfill_ledger.as_posix()], _this_file=journal_fixture_script.as_posix(),
            )
        assert "corrected=0" in repair2_out.getvalue(), repair2_out.getvalue()
        # The KPI sees the backfilled split over the same fixtures (closed=5; typed = NUDGE,
        # STATUSV, REPAIR — all via backfill).
        kpi2_out = io.StringIO()
        with contextlib.redirect_stdout(kpi2_out):
            cmd_gym_kpi(
                journal_fixture.as_posix(), _root=journal_root_dir,
                _now=datetime.now(timezone.utc), _ledger_paths=[backfill_ledger.as_posix()],
            )
        assert "verdict-coverage:      3/5" in kpi2_out.getvalue(), kpi2_out.getvalue()
        assert "; 3 via backfill" in kpi2_out.getvalue(), kpi2_out.getvalue()

        # PROD-D5-LINEAGE-AUTOSTAMP: (a) write-time — claim lineage derives modelId from the
        # session's OWN transcript when the env override is absent (env still wins when set;
        # synthetic placeholder models never resolve); (b) claim-TIME precision — the model
        # at-or-before the claim instant wins over later switches; (c) retro — lineage-backfill
        # joins ledger CLAIM uuid notes to transcripts and appends ONE backfilled stamp,
        # dry-run writes nothing, re-runs are no-ops, and the KPI counts the stamp while the
        # backfilled line never inflates 7d activity.
        os.environ.pop("TORAD_SEAT_MODEL", None)
        d5_home = Path(tempfile.mkdtemp(prefix="manifest-d5-home-"))
        d5_sid = "aaaabbbb-cccc-dddd-eeee-ffff00001111"
        d5_proj = d5_home / ".claude" / "projects" / "d5-proj"
        d5_proj.mkdir(parents=True)
        (d5_proj / f"{d5_sid}.jsonl").write_text(
            json.dumps({"type": "assistant", "timestamp": "2026-07-01T00:00:00.000Z",
                        "message": {"model": "model-early"}}) + "\n"
            + json.dumps({"type": "assistant", "timestamp": "2026-07-02T00:00:00.000Z",
                          "message": {"model": "<synthetic>"}}) + "\n"
            + json.dumps({"type": "assistant", "timestamp": "2026-07-03T00:00:00.000Z",
                          "message": {"model": "model-late"}}) + "\n",
            encoding="utf-8",
        )
        d5_lineage = _claim_policy_lineage(d5_sid, _home=d5_home)
        assert d5_lineage and d5_lineage.get("modelId") == "model-late", d5_lineage
        assert d5_lineage.get("modelIdBasis") == "transcript", d5_lineage
        os.environ["TORAD_SEAT_MODEL"] = "env-wins"
        d5_env_lineage = _claim_policy_lineage(d5_sid, _home=d5_home)
        assert d5_env_lineage and d5_env_lineage.get("modelId") == "env-wins", d5_env_lineage
        assert d5_env_lineage.get("modelIdBasis") == "env", d5_env_lineage
        os.environ.pop("TORAD_SEAT_MODEL", None)
        assert _resolve_transcript_model(d5_sid, "2026-07-01T12:00:00Z", _home=d5_home) == "model-early"
        assert _resolve_transcript_model("00000000-0000-0000-0000-000000000000", _home=d5_home) is None

        d5_root = Path(tempfile.mkdtemp(prefix="manifest-d5-journal-"))
        d5_ledger = journal_ledger_dir / "d5-fixture.toml"
        d5_ledger.write_text(
            "\n".join([
                "[[items]]", 'id = "D5ITEM"', 'status = "done"',
                f"# [2026-07-03] CLAIM: owner={d5_sid} at=2026-07-01T12:00:00Z",
                "[[items]]", 'id = "D5NOPRESENCE"', 'status = "done"',
                f"# [2026-07-03] CLAIM: owner={d5_sid} at=2026-07-01T12:00:00Z",
                "[[items]]", 'id = "D5NONUUID"', 'status = "done"',
                "# [2026-07-03] CLAIM: owner=some-seat~deadbeef at=2026-07-01T12:00:00Z", "",
            ]),
            encoding="utf-8",
        )
        # D5REG has NO uuid ledger claim — it resolves through the sessions-registry seat window
        # (the join that covers the fleet-* tmux seats' bare-seat claims). D5PATH covers the
        # provider-complete onboarding path: a harness (Grok Build / Cursor class) whose
        # transcript filename cannot carry the fleet session id registers an explicit
        # `transcript` path on its registry row instead.
        d5_registry = Path(tempfile.mkdtemp(prefix="manifest-d5-registry-"))
        (d5_registry / f"{d5_sid}.json").write_text(
            json.dumps({"seat": "d5-reg-seat", "started": "2026-07-01T00:00:00Z",
                        "updated": "2026-07-10T00:00:00Z"}),
            encoding="utf-8",
        )
        d5_grok_history = d5_home / "prompt_history.jsonl"
        d5_grok_history.write_text(
            json.dumps({"timestamp": "2026-07-02T00:00:00Z", "model": "grok-4-code"}) + "\n",
            encoding="utf-8",
        )
        (d5_registry / "bbbbcccc-dddd-eeee-ffff-000011112222.json").write_text(
            json.dumps({"seat": "d5-path-seat", "started": "2026-07-01T00:00:00Z",
                        "updated": "2026-07-10T00:00:00Z",
                        "transcript": d5_grok_history.as_posix()}),
            encoding="utf-8",
        )
        d5_journal = d5_root / "journal" / "events.jsonl"
        d5_journal.parent.mkdir(parents=True)
        d5_journal.write_text(
            json.dumps({"seq": 1, "ts": "2026-07-01T12:00:00Z", "episodeLabel": "D5ITEM",
                        "kind": "manifest_verb", "verb": "claim", "itemId": "D5ITEM",
                        "seat": "d5-seat"}) + "\n"
            + json.dumps({"seq": 2, "ts": "2026-07-04T00:00:00Z", "episodeLabel": "D5REG",
                          "kind": "manifest_verb", "verb": "claim", "itemId": "D5REG",
                          "seat": "d5-reg-seat"}) + "\n"
            + json.dumps({"seq": 3, "ts": "2026-07-04T00:00:00Z", "episodeLabel": "D5PATH",
                          "kind": "manifest_verb", "verb": "claim", "itemId": "D5PATH",
                          "seat": "d5-path-seat"}) + "\n"
            + json.dumps({"seq": 4, "ts": "2026-07-04T00:00:00Z", "episodeLabel": "D5MISS",
                          "kind": "manifest_verb", "verb": "claim", "itemId": "D5MISS",
                          "seat": "d5-miss-seat"}) + "\n"
            + json.dumps({"seq": 5, "ts": "2026-07-01T01:00:05Z", "episodeLabel": "D5EXEC",
                          "kind": "manifest_verb", "verb": "claim", "itemId": "D5EXEC",
                          "seat": "d5-exec-seat"}) + "\n",
            encoding="utf-8",
        )
        # D5MISS: a registered seat whose session has NO findable transcript — the honest
        # no-transcript skip (never invented).
        (d5_registry / "ccccdddd-eeee-ffff-0000-111122223333.json").write_text(
            json.dumps({"seat": "d5-miss-seat", "started": "2026-07-01T00:00:00Z",
                        "updated": "2026-07-10T00:00:00Z"}),
            encoding="utf-8",
        )

        # PROD-D5B fixtures: D5EXEC has no uuid note and no registry row — only its claim's
        # EXECUTION RECORD in the bank (a structured tool_use command). A second bank file
        # quotes 'claim D5EXEC' as summary TEXT with a DIFFERENT model — the structural wall
        # must reject it (assert: attribution is model-exec, not model-wrong, not ambiguous).
        d5_bank = Path(tempfile.mkdtemp(prefix="manifest-d5-bank-"))
        (d5_bank / "exec-session.jsonl").write_text(
            json.dumps({"type": "assistant", "timestamp": "2026-07-01T00:59:00Z",
                        "message": {"model": "model-exec"}}) + "\n"
            + json.dumps({"type": "assistant", "timestamp": "2026-07-01T01:00:00Z",
                          "message": {"content": [{"type": "tool_use", "name": "Bash",
                                                   "input": {"command": "python3 dev/campaigns/manifest.py x.toml claim D5EXEC --session s"}}]}}) + "\n",
            encoding="utf-8",
        )
        (d5_bank / "summary-contamination.jsonl").write_text(
            json.dumps({"type": "assistant", "timestamp": "2026-07-01T01:00:01Z",
                        "message": {"model": "model-wrong"}}) + "\n"
            + json.dumps({"type": "user", "timestamp": "2026-07-01T01:00:02Z",
                          "message": {"content": [{"type": "text",
                                                   "text": "summary: ran claim D5EXEC earlier; D5EXEC -> in_flight"}]}}) + "\n",
            encoding="utf-8",
        )

        def _d5_backfill(out, dry):
            with contextlib.redirect_stdout(out):
                cmd_lineage_backfill(
                    journal_fixture.as_posix(), dry_run=dry, _root=d5_root,
                    _ledger_paths=[d5_ledger.as_posix()],
                    _this_file=journal_fixture_script.as_posix(),
                    _home=d5_home, _registry_dir=d5_registry, _bank_dir=d5_bank,
                )

        d5_bytes_before = d5_journal.read_bytes()
        d5_dry = io.StringIO()
        _d5_backfill(d5_dry, dry=True)
        assert (
            "[dry-run] lineage-backfill: 4 stamped "
            "(grok-4-code=1, model-early=1, model-exec=1, model-late=1; "
            "via execution-record=1, registry-transcript=2, transcript=1)"
        ) in d5_dry.getvalue(), d5_dry.getvalue()
        assert "no-journal-presence=1" in d5_dry.getvalue(), d5_dry.getvalue()
        assert "no-transcript=1" in d5_dry.getvalue(), d5_dry.getvalue()
        assert d5_journal.read_bytes() == d5_bytes_before, "dry-run must write NOTHING"
        d5_apply = io.StringIO()
        _d5_backfill(d5_apply, dry=False)
        assert "lineage-backfill: 4 stamped" in d5_apply.getvalue(), d5_apply.getvalue()
        d5_lines = [json.loads(l) for l in d5_journal.read_text(encoding="utf-8").splitlines() if l.strip()]
        assert len(d5_lines) == 9, d5_lines
        d5_stamps = {e["episodeLabel"]: e for e in d5_lines if e.get("kind") == "seatd_lifecycle"}
        assert set(d5_stamps) == {"D5ITEM", "D5REG", "D5PATH", "D5EXEC"}, d5_stamps
        assert d5_stamps["D5EXEC"]["detail"] == {"modelId": "model-exec"}, (
            "the structural wall must reject the summary-contamination file's model-wrong: ",
            d5_stamps,
        )
        assert d5_stamps["D5EXEC"]["basis"] == "execution-record", d5_stamps
        for stamp in d5_stamps.values():
            assert stamp["event"] == "policy_lineage_stamped", stamp
            assert stamp["backfilled"] is True, stamp
        assert d5_stamps["D5ITEM"]["detail"] == {"modelId": "model-early"}, d5_stamps
        assert d5_stamps["D5ITEM"]["seat"] == "d5-seat", d5_stamps
        assert d5_stamps["D5ITEM"]["basis"] == "transcript", d5_stamps
        assert d5_stamps["D5REG"]["detail"] == {"modelId": "model-late"}, d5_stamps
        assert d5_stamps["D5REG"]["basis"] == "registry-transcript", d5_stamps
        assert d5_stamps["D5PATH"]["detail"] == {"modelId": "grok-4-code"}, d5_stamps
        assert d5_stamps["D5PATH"]["basis"] == "registry-transcript", d5_stamps
        d5_again = io.StringIO()
        _d5_backfill(d5_again, dry=False)
        assert "lineage-backfill: 0 stamped" in d5_again.getvalue(), d5_again.getvalue()
        assert "already-attributed=4" in d5_again.getvalue(), d5_again.getvalue()
        d5_kpi = io.StringIO()
        with contextlib.redirect_stdout(d5_kpi):
            cmd_gym_kpi(
                journal_fixture.as_posix(), _root=d5_root,
                _now=datetime.now(timezone.utc), _ledger_paths=[d5_ledger.as_posix()],
            )
        assert "overall 4/5 claims lineage-attributed (80.0%)" in d5_kpi.getvalue(), d5_kpi.getvalue()
        assert "trajectories-7d:       0 distinct episodes" in d5_kpi.getvalue(), (
            "a backfilled stamp must never count as 7d fleet activity: " + d5_kpi.getvalue()
        )
        os.environ["TORAD_SEAT_MODEL"] = "selftest-model"
        shutil.rmtree(d5_home, ignore_errors=True)
        shutil.rmtree(d5_root, ignore_errors=True)
    finally:
        if previous_seat_model is None:
            os.environ.pop("TORAD_SEAT_MODEL", None)
        else:
            os.environ["TORAD_SEAT_MODEL"] = previous_seat_model
        shutil.rmtree(journal_root_dir, ignore_errors=True)

    tmp_verify = tempfile.NamedTemporaryFile("w", delete=False, suffix=".toml")
    tmp_verify.close()
    shutil.copy(path, tmp_verify.name)
    cmd_add(tmp_verify.name, "VERIFYTEST", "V", "verify item", "a/**", "old verify")
    cmd_edit_verify(tmp_verify.name, "VERIFYTEST", "new verify command")
    lines = _read(tmp_verify.name)
    s, e = _find(lines, "VERIFYTEST")
    blk = "".join(lines[s:e])
    assert 'verify = "new verify command"' in blk, blk
    assert "VERIFY-EDITED" in blk, blk
    with open(tmp_verify.name, "rb") as f:
        tomllib.load(f)

    tmp_packet = tempfile.NamedTemporaryFile("w", delete=False, suffix=".toml")
    tmp_packet.close()
    shutil.copy(path, tmp_packet.name)
    cmd_add(tmp_packet.name, "PACKETPEER", "P", "peer rm clean", "peer/**", "n/a")
    cmd_claim(tmp_packet.name, "PACKETPEER", "peer-owner", _alive=lambda _sid: False)
    # Self-sufficient packet target: reference ledgers (files=[] everywhere, e.g.
    # interface-contract.toml) otherwise have no eligible item and selftest can
    # never pass on them. next() prefers earlier EXISTING items, so behavior on
    # work ledgers is unchanged.
    cmd_add(tmp_packet.name, "PACKETSELF", "P", "packet fixture item", "fixture/dir", "echo ok")
    from contextlib import redirect_stdout
    import io

    packet_buf = io.StringIO()
    with redirect_stdout(packet_buf):
        with open(tmp_packet.name, "rb") as f:
            packet_manifest = tomllib.load(f)
        packet_campaign_name = packet_manifest["campaign"]["name"]
        packet_target = next(
            (
                item
                for item in packet_manifest["items"]
                if item.get("id") != "PACKETPEER"
                and isinstance(item.get("files"), list)
                and item.get("files")
                and isinstance(item.get("verify"), str)
                and item.get("verify").strip()
                and not item.get("verify").strip().startswith("TBD")
                and not (
                    # Plain whitespace split + punctuation strip: this scans PROSE
                    # (titles/verify), where shlex chokes on legitimate apostrophes
                    # ("we're", "seatd's") — deterministic string ops per the
                    # no-regex-where-structure-exists law's prose carve-out.
                    any(
                        token.strip(".,;:()[]{}!?\"'").lower()
                        in {"rm", "delete", "clean", "mv", "move"}
                        for token in f"{item.get('title') or ''} {item.get('verify') or ''}".split()
                    )
                    and any("*" in f for f in item.get("files", []))
                )
            ),
            None,
        )
        assert packet_target is not None, "no packet target available in fixture"
        packet_id = packet_target["id"]
        cmd_packet(tmp_packet.name, packet_id)
    packet_text = packet_buf.getvalue()
    packet_verify = packet_target["verify"]
    packet_repo_root_name = (_git_repo_root(tmp_packet.name) or Path.cwd()).name
    assert f"PACKET {packet_id} — {packet_campaign_name} campaign. ONE item." in packet_text, packet_text
    assert (
        f'0. pwd must be {packet_repo_root_name} root; if not, STOP and reply "{packet_id} blocked — wrong home".'
        in packet_text
    ), packet_text
    assert (
        f"1. Read the full spec: python3 dev/campaigns/manifest.py {tmp_packet.name} get {packet_id} — the SLOT note is the complete design, zero design freedom. Laws: manifest.py laws."
        in packet_text
    ), packet_text
    assert "1a. Claim identity = your tmux seat name." in packet_text, packet_text
    packet_files_text = ", ".join(packet_target["files"])
    assert f"2. Fence = exactly: {packet_files_text}." in packet_text, packet_text
    assert "3. COORDINATION FENCES — live peer items, do NOT touch their files:" in packet_text, packet_text
    assert "   - PACKETPEER @peer-owner: peer/**" in packet_text, packet_text
    assert (
        "4a. READ BUDGET: fence files + files the SLOT cites by exact path; anything else = "
        f"one-line justification note on {packet_id} FIRST."
        in packet_text
    ), packet_text
    assert (
        "4b. Premise-blocks require file:line EVIDENCE; belief that a requirement is unnecessary is not "
        "a skip reason — note the disagreement AND do the work. Every numbered slot point ships or gets "
        "an evidence-cited note."
        in packet_text
    ), packet_text
    assert (
        f"6. Note progress + evidence on {packet_id} via the manifest CLI as you work; verify: {packet_verify}; "
        f"set-status {packet_id} done only when green — set-status IS the report (it pokes the "
        f'orchestrator mechanically). Optionally reply ONE line after: "{packet_id} done — see ledger".'
        in packet_text
    ), packet_text

    tmp_no_verify = tempfile.NamedTemporaryFile("w", delete=False, suffix=".toml")
    tmp_no_verify.close()
    shutil.copy(path, tmp_no_verify.name)
    cmd_add(tmp_no_verify.name, "PKTNOVERIFY", "N", "missing verify item", "a/**", "n/a")
    lines = _read(tmp_no_verify.name)
    s, e = _find(lines, "PKTNOVERIFY")
    for j in range(s, e):
        if lines[j].lstrip().startswith("verify ="):
            del lines[j]
            break
    with open(tmp_no_verify.name, "w", encoding="utf-8") as f:
        f.writelines(lines)
    with open(tmp_no_verify.name, "rb") as f:
        tomllib.load(f)
    try:
        cmd_packet(tmp_no_verify.name, "PKTNOVERIFY")
        raise AssertionError("packet should have failed for missing verify")
    except SystemExit as exc:
        assert "missing verify" in str(exc), str(exc)

    tmp_destructive = tempfile.NamedTemporaryFile("w", delete=False, suffix=".toml")
    tmp_destructive.close()
    shutil.copy(path, tmp_destructive.name)
    cmd_add(tmp_destructive.name, "PKTDESTRUCTIVE", "D", "rm clean release", "a/**", "rm -rf dist")
    try:
        cmd_packet(tmp_destructive.name, "PKTDESTRUCTIVE")
        raise AssertionError("packet should have failed for destructive item with glob fence")
    except SystemExit as exc:
        assert "destructive item with un-enumerated fence" in str(exc), str(exc)

    tmp_apostrophe = tempfile.NamedTemporaryFile("w", delete=False, suffix=".toml")
    tmp_apostrophe.close()
    shutil.copy(path, tmp_apostrophe.name)
    cmd_add(tmp_apostrophe.name, "APOSTEST", "A", "session's packet title", "a/**", "echo ok")
    apostrophe_buf = io.StringIO()
    with redirect_stdout(apostrophe_buf):
        cmd_packet(tmp_apostrophe.name, "APOSTEST")
    apostrophe_text = apostrophe_buf.getvalue()
    assert f"PACKET APOSTEST — {packet_campaign_name} campaign. ONE item." in apostrophe_text, apostrophe_text

    # ORCH-DISPATCH-COUPLING (#924): dispatch FAILS CLOSED on an uncut ID (the whole bug), and
    # emits the packet + records claim intent on a real one.
    tmp_dispatch = tempfile.NamedTemporaryFile("w", delete=False, suffix=".toml")
    tmp_dispatch.close()
    shutil.copy(path, tmp_dispatch.name)
    # (a) uncut ID -> nonzero, naming the fix. This is the antibody the item exists for.
    try:
        cmd_dispatch(tmp_dispatch.name, "NEVERCUTID", "seat-x")
        raise AssertionError("dispatch must fail closed on an uncut ID")
    except SystemExit as exc:
        assert "not cut" in str(exc) and "NEVERCUTID" in str(exc), str(exc)
    # (b) a real, eligible item -> packet emitted AND a DISPATCH note recorded.
    cmd_add(tmp_dispatch.name, "DISPATCHOK", "D", "dispatchable item", "a/b.kt", "echo ok")
    dispatch_buf = io.StringIO()
    with redirect_stdout(dispatch_buf):
        cmd_dispatch(tmp_dispatch.name, "DISPATCHOK", "seat-y")
    dispatch_text = dispatch_buf.getvalue()
    assert f"PACKET DISPATCHOK — {packet_campaign_name} campaign. ONE item." in dispatch_text, dispatch_text
    lines = _read(tmp_dispatch.name)
    s, e = _find(lines, "DISPATCHOK")
    blk = "".join(lines[s:e])
    assert "DISPATCH: to=seat-y" in blk, blk
    # (c) an ill-formed (no-verify) item also fails closed — cannot dispatch a malformed item.
    cmd_add(tmp_dispatch.name, "DISPATCHNOVERIFY", "D", "no verify", "a/b.kt", "n/a")
    lines = _read(tmp_dispatch.name)
    s, e = _find(lines, "DISPATCHNOVERIFY")
    for j in range(s, e):
        if lines[j].lstrip().startswith("verify ="):
            del lines[j]
            break
    with open(tmp_dispatch.name, "w", encoding="utf-8") as f:
        f.writelines(lines)
    try:
        cmd_dispatch(tmp_dispatch.name, "DISPATCHNOVERIFY", "seat-z")
        raise AssertionError("dispatch must fail closed on an ill-formed item")
    except SystemExit as exc:
        assert "cannot dispatch" in str(exc), str(exc)

    tmp_claim = tempfile.NamedTemporaryFile("w", delete=False, suffix=".toml")
    tmp_claim.close()
    shutil.copy(path, tmp_claim.name)
    cmd_add(tmp_claim.name, "CLAIMTEST", "C", "claim item", "a/**", "n/a")
    cmd_claim(tmp_claim.name, "CLAIMTEST", "sid-a", _alive=lambda _sid: False)
    lines = _read(tmp_claim.name)
    s, e = _find(lines, "CLAIMTEST")
    blk = "".join(lines[s:e])
    assert 'status = "in_flight"' in blk, blk
    assert "CLAIM: owner=sid-a" in blk, blk
    assert "ATTEST-START: unavailable (" in blk, blk
    assert _block_owner(lines, s, e) == "sid-a", blk
    from contextlib import redirect_stdout
    import io

    buf = io.StringIO()
    with redirect_stdout(buf):
        cmd_list(tmp_claim.name, status="in_flight")
    listed = buf.getvalue()
    assert "@sid-a" in listed, listed

    try:
        cmd_claim(tmp_claim.name, "CLAIMTEST", "sid-b", _alive=lambda _sid: True)
        raise AssertionError("claim should have failed for live owner")
    except SystemExit as exc:
        assert "owned by sid-a" in str(exc) and "registry+tmux live" in str(exc), str(exc)
    lines = _read(tmp_claim.name)
    s, e = _find(lines, "CLAIMTEST")
    blk = "".join(lines[s:e])
    assert "CLAIM: owner=sid-b" not in blk, blk
    assert _block_owner(lines, s, e) == "sid-a", blk

    cmd_claim(tmp_claim.name, "CLAIMTEST", "sid-b", _alive=lambda _sid: False)
    lines = _read(tmp_claim.name)
    s, e = _find(lines, "CLAIMTEST")
    blk = "".join(lines[s:e])
    assert "supersedes=sid-a" in blk, blk
    assert _block_owner(lines, s, e) == "sid-b", blk

    cmd_release_stale(tmp_claim.name, "CLAIMTEST", "sid-b", _alive=lambda _sid: False)
    lines = _read(tmp_claim.name)
    s, e = _find(lines, "CLAIMTEST")
    blk = "".join(lines[s:e])
    assert 'status = "todo"' in blk, blk
    assert "CLAIM-RELEASED" in blk, blk
    assert _block_owner(lines, s, e) is None, blk

    cmd_claim(tmp_claim.name, "CLAIMTEST", "sid-c", _alive=lambda _sid: False)
    try:
        cmd_release_stale(tmp_claim.name, "CLAIMTEST", "sid-c", _alive=lambda _sid: True)
        raise AssertionError("release-stale should have failed for live owner")
    except SystemExit as exc:
        assert "registry+tmux live" in str(exc), str(exc)

    cmd_set_status(tmp_claim.name, "CLAIMTEST", "done")
    try:
        cmd_claim(tmp_claim.name, "CLAIMTEST", "sid-d", _alive=lambda _sid: False)
        raise AssertionError("claim should have failed for done item")
    except SystemExit as exc:
        assert "claim only todo/in_flight items" in str(exc), str(exc)
    lines = _read(tmp_claim.name)
    s, e = _find(lines, "CLAIMTEST")
    blk = "".join(lines[s:e])
    assert 'status = "done"' in blk, blk
    assert "CLAIM: owner=sid-d" not in blk, blk
    assert "Attribution unavailable: missing in_flight tree snapshot" in blk, blk

    cmd_add(tmp_claim.name, "PROSEONLY", "P", "prose collision item", "a/**", "n/a")
    cmd_note(
        tmp_claim.name,
        "PROSEONLY",
        'prose collision: ATTEST-START: tree="quote" is just text',
    )
    cmd_set_status(tmp_claim.name, "PROSEONLY", "done")
    lines = _read(tmp_claim.name)
    s, e = _find(lines, "PROSEONLY")
    blk = "".join(lines[s:e])
    assert 'status = "done"' in blk, blk
    assert 'prose collision: ATTEST-START: tree="quote" is just text' in blk, blk
    assert "Attribution unavailable: missing in_flight tree snapshot" in blk, blk

    git_repo = Path(tempfile.mkdtemp(prefix="manifest-attest-"))
    try:
        subprocess.run(["git", "-C", str(git_repo), "init", "-q"], check=True)
        subprocess.run(
            ["git", "-C", str(git_repo), "config", "user.email", "selftest@example.com"],
            check=True,
        )
        subprocess.run(
            ["git", "-C", str(git_repo), "config", "user.name", "Manifest Selftest"],
            check=True,
        )
        fixture_manifest = git_repo / "dev" / "campaigns" / "orchestration-product.toml"
        fixture_manifest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(path, fixture_manifest)
        fixture_script = git_repo / "dev" / "campaigns" / "manifest.py"
        shutil.copy(__file__, fixture_script)
        fixture_toml = git_repo / "dev" / "campaigns" / "fixture.toml"
        fixture_toml.write_text("base fixture\n", encoding="utf-8")
        subprocess.run(
            [
                "git",
                "-C",
                str(git_repo),
                "add",
                "dev/campaigns/orchestration-product.toml",
                "dev/campaigns/manifest.py",
                "dev/campaigns/fixture.toml",
            ],
            check=True,
        )
        subprocess.run(["git", "-C", str(git_repo), "commit", "-q", "-m", "base"], check=True)

        cmd_add(
            fixture_manifest.as_posix(),
            "GITATTEST",
            "G",
            "git bracket item",
            "dev/campaigns/manifest.py",
            "echo git",
        )
        cmd_claim(fixture_manifest.as_posix(), "GITATTEST", "git-sid", _alive=lambda _sid: False)
        lines = _read(fixture_manifest.as_posix())
        s, e = _find(lines, "GITATTEST")
        blk = "".join(lines[s:e])
        assert 'status = "in_flight"' in blk, blk
        assert "CLAIM: owner=git-sid" in blk, blk
        assert "ATTEST-START: tree=" in blk, blk

        fixture_script.write_text(fixture_script.read_text(encoding="utf-8") + "\n# scoped line\n", encoding="utf-8")
        fixture_toml.write_text("base fixture\nchanged = true\n", encoding="utf-8")

        cmd_set_status(fixture_manifest.as_posix(), "GITATTEST", "done")
        lines = _read(fixture_manifest.as_posix())
        s, e = _find(lines, "GITATTEST")
        blk = "".join(lines[s:e])
        done_note = next(line for line in lines[s:e] if "ATTEST: scoped diffstat:" in line)
        assert 'status = "done"' in blk, blk
        assert "ATTEST: scoped diffstat:" in blk, blk
        assert "dev/campaigns/manifest.py" in done_note, done_note
        assert "fixture.toml" not in done_note, done_note
        assert "orchestration-product.toml" not in done_note, done_note
        sidecar = git_repo / _sidecar_rel_path("GITATTEST")
        assert sidecar.exists(), sidecar
        sidecar_text = sidecar.read_text(encoding="utf-8")
        assert "diff --git a/dev/campaigns/manifest.py b/dev/campaigns/manifest.py" in sidecar_text, sidecar_text[:500]
        assert "fixture.toml" not in sidecar_text, sidecar_text

        # G24: done -> verified must NOT double-attest (bracket already closed).
        cmd_set_status(fixture_manifest.as_posix(), "GITATTEST", "verified")
        lines = _read(fixture_manifest.as_posix())
        s, e = _find(lines, "GITATTEST")
        attest_count = sum(1 for line in lines[s:e] if f"] {ATTR_NOTE}: " in line)
        assert attest_count == 1, f"double attest on done->verified: {attest_count}"

        # G24: verified WITHOUT a prior done closes the open bracket (the skipped-done hole).
        cmd_add(
            fixture_manifest.as_posix(),
            "VERIFATTEST",
            "G",
            "verified-closes-bracket item",
            "dev/campaigns/manifest.py",
            "echo verif",
        )
        cmd_claim(fixture_manifest.as_posix(), "VERIFATTEST", "verif-sid", _alive=lambda _sid: False)
        fixture_script.write_text(fixture_script.read_text(encoding="utf-8") + "\n# verified line\n", encoding="utf-8")
        cmd_set_status(fixture_manifest.as_posix(), "VERIFATTEST", "verified", _this_file=fixture_script.as_posix())
        lines = _read(fixture_manifest.as_posix())
        s, e = _find(lines, "VERIFATTEST")
        blk = "".join(lines[s:e])
        assert 'status = "verified"' in blk, blk
        assert "ATTEST: scoped diffstat:" in blk, blk
        verif_sidecar = git_repo / _sidecar_rel_path("VERIFATTEST")
        assert verif_sidecar.exists(), verif_sidecar

        # FENCE-AUTONARROW: a bare-directory-fenced item narrows to its ATTEST'd file(s) on
        # set-status verified -- BD-2/W1/W5 each cost a manual edit-fence because a done item's
        # broad directory fence stayed live forever, blocking every foreign seat's unrelated
        # work under it. dev/campaigns/*.toml is _attest_excluded, so scoping to "dev/campaigns/"
        # here isolates the ledger's own claim/attest self-writes from the one real change
        # (fixture_script) -- narrows to exactly that file, nothing else.
        cmd_add(
            fixture_manifest.as_posix(),
            "NARROWATTEST",
            "G",
            "fence autonarrow item",
            "dev/campaigns/",
            "echo narrow",
            override_bare_dir=True,
        )
        cmd_claim(fixture_manifest.as_posix(), "NARROWATTEST", "narrow-sid", _alive=lambda _sid: False)
        fixture_script.write_text(
            fixture_script.read_text(encoding="utf-8") + "\n# narrow scoped line\n", encoding="utf-8"
        )
        cmd_set_status(fixture_manifest.as_posix(), "NARROWATTEST", "done")
        cmd_set_status(fixture_manifest.as_posix(), "NARROWATTEST", "verified")
        lines = _read(fixture_manifest.as_posix())
        s, e = _find(lines, "NARROWATTEST")
        blk = "".join(lines[s:e])
        assert 'files = ["dev/campaigns/manifest.py"]' in blk, blk
        assert "FENCE-AUTONARROWED" in blk, blk

        # PROD-V2-VERDICT-TOOLKIT-PROMOTION: the auto-hydrate on `verified` only writes a trace when
        # the repo carries scripts/hydrate-traces.py (fleet). In a VENDORED repo (kandi/toolkit) that
        # script is absent, _hydrate_traces correctly no-ops, and this leg must be SKIPPED so
        # 'selftest green at every vendoring' holds — the portable verdict/nudge/packet cases still
        # run. When the enforcement-pack extraction makes the script set travel, this leg re-arms
        # automatically wherever the script lands. (The broken-hydrate error path below provides its
        # OWN script, so it stays portable and always runs.)
        hydrate_script = Path(__file__).resolve().parents[2] / "scripts" / "hydrate-traces.py"
        trace_path = git_repo / "dev" / "campaigns" / "traces" / f"{fixture_manifest.stem}.jsonl"
        if hydrate_script.is_file():
            assert trace_path.exists(), trace_path
            trace_records = [json.loads(line) for line in trace_path.read_text(encoding="utf-8").splitlines() if line.strip()]
            assert trace_records and trace_records[0]["type"] == "campaign" and trace_records[0]["schemaVersion"] == 1, trace_records[:1]
            assert any(record.get("type") == "trace" for record in trace_records), trace_records
        else:
            print(
                "selftest: scripts/hydrate-traces.py absent (vendored repo) — skipping the "
                "trace-hydration leg; portable verdict/nudge/packet cases still asserted",
                file=sys.stderr,
            )

        broken_script = git_repo / "scripts" / "broken-hydrate-traces.py"
        broken_script.parent.mkdir(parents=True, exist_ok=True)
        broken_script.write_text(
            "#!/usr/bin/env python3\n"
            "import sys\n"
            "print('broken hydrate', file=sys.stderr)\n"
            "sys.exit(7)\n",
            encoding="utf-8",
        )
        cmd_add(
            fixture_manifest.as_posix(),
            "BROKENHYDRATE",
            "G",
            "broken hydrate item",
            "dev/campaigns/manifest.py",
            "echo broken",
        )
        stderr_buf = io.StringIO()
        with redirect_stderr(stderr_buf):
            cmd_set_status(
                fixture_manifest.as_posix(),
                "BROKENHYDRATE",
                "verified",
                _this_file=fixture_script.as_posix(),
                _hydrate_script=broken_script.as_posix(),
            )
        lines = _read(fixture_manifest.as_posix())
        s, e = _find(lines, "BROKENHYDRATE")
        blk = "".join(lines[s:e])
        assert 'status = "verified"' in blk, blk
        assert "trace hydration failed" in stderr_buf.getvalue(), stderr_buf.getvalue()
    finally:
        shutil.rmtree(git_repo, ignore_errors=True)

    tmp_notify = tempfile.NamedTemporaryFile("w", delete=False, suffix=".toml")
    tmp_notify.close()
    shutil.copy(path, tmp_notify.name)
    cmd_add(tmp_notify.name, "NOTIFYTEST", "N", "notify wiring item", "a/**", "n/a")
    notify_calls = []

    def _fake_notify(poke_path, message):
        notify_calls.append((poke_path, message))

    # Canonical-guard fixture: a fixture dev/campaigns dir beside a COPIED manifest.py
    # (mirrors the T4/T3 fixture-repo pattern) so _is_canonical_ledger's true branch is
    # testable via the injectable _this_file, without ever writing into the real
    # dev/campaigns dir this script actually lives in. Also git-init'd (G6 extension, not
    # a duplicate fixture) so _git_repo_root resolves — the pointer-writer tests below reuse
    # this exact fixture_root/fixture_script/fixture_ledger.
    fixture_root = Path(tempfile.mkdtemp(prefix="manifest-notify-fixture-"))
    subprocess.run(["git", "-C", str(fixture_root), "init", "-q"], check=True)
    fixture_campaigns_dir = fixture_root / "dev" / "campaigns"
    fixture_campaigns_dir.mkdir(parents=True)
    fixture_script = fixture_campaigns_dir / "manifest.py"
    shutil.copy(__file__, fixture_script)
    fixture_ledger = fixture_campaigns_dir / "fixture-notify.toml"
    shutil.copy(path, fixture_ledger)
    cmd_add(fixture_ledger.as_posix(), "NOTIFYTEST", "N", "notify wiring item (canonical fixture)", "a/**", "n/a")
    cmd_add(fixture_ledger.as_posix(), "POINTERTEST", "N", "pointer writer item (canonical fixture)", "a/**", "n/a")

    # The whole selftest body runs under TORAD_LEDGER_NOTIFY=off (see cmd_selftest's wrapper),
    # so flip it back on for this block only — otherwise the poke-should-fire assertions below
    # could never observe a call. TORAD_SESSION_ROLE must ALSO be pinned explicitly (not left at
    # whatever the calling seat exports): a real orchestrator seat runs this very selftest with
    # TORAD_SESSION_ROLE=orchestrator, which would silently suppress every fire-assertion below
    # and mask them green-by-accident — pin to a non-orchestrator role for the fire cases and
    # only set "orchestrator" explicitly for the one case that means to test that suppression.
    previous_notify_env = os.environ.get(NOTIFY_OFF_ENV)
    previous_role_env = os.environ.get(NOTIFY_ROLE_ENV)
    os.environ[NOTIFY_OFF_ENV] = "on"
    os.environ[NOTIFY_ROLE_ENV] = "builder"
    try:
        # Canonical guard (kandi 09ae618f): a plain tmp-copy ledger outside dev/campaigns
        # never pokes, even with every other suppression disabled (notify=on, role=builder).
        cmd_set_status(tmp_notify.name, "NOTIFYTEST", "in_flight", _notify=_fake_notify)
        assert notify_calls == [], f"in_flight flip should not poke: {notify_calls}"

        cmd_note(tmp_notify.name, "NOTIFYTEST", "not a blocker", _notify=_fake_notify)
        assert notify_calls == [], f"non-BLOCKED note should not poke: {notify_calls}"

        cmd_set_status(tmp_notify.name, "NOTIFYTEST", "done", _notify=_fake_notify)
        assert notify_calls == [], (
            f"non-canonical tmp-copy ledger must not poke even with role=builder + notify=on: {notify_calls}"
        )

        cmd_note(tmp_notify.name, "NOTIFYTEST", "BLOCKED: waiting on operator", _notify=_fake_notify)
        assert notify_calls == [], (
            f"non-canonical tmp-copy ledger must not poke for a BLOCKED note either: {notify_calls}"
        )

        # Canonical fixture (fixture dev/campaigns beside a copied manifest.py): with the
        # directory identity satisfied via _this_file, the fire-assertions can observe calls.
        fixture_kwargs = {"_notify": _fake_notify, "_this_file": fixture_script.as_posix()}
        cmd_set_status(fixture_ledger.as_posix(), "NOTIFYTEST", "in_flight", **fixture_kwargs)
        assert notify_calls == [], f"in_flight flip should not poke: {notify_calls}"

        cmd_note(fixture_ledger.as_posix(), "NOTIFYTEST", "not a blocker", **fixture_kwargs)
        assert notify_calls == [], f"non-BLOCKED note should not poke: {notify_calls}"

        cmd_set_status(fixture_ledger.as_posix(), "NOTIFYTEST", "done", **fixture_kwargs)
        expected_done = (fixture_ledger.as_posix(), f"NOTIFYTEST done — see ledger ({fixture_ledger.stem})")
        assert notify_calls == [expected_done], notify_calls
        notify_calls.clear()

        cmd_note(fixture_ledger.as_posix(), "NOTIFYTEST", "BLOCKED: waiting on operator", **fixture_kwargs)
        expected_blocked = (fixture_ledger.as_posix(), f"NOTIFYTEST BLOCKED — see ledger ({fixture_ledger.stem})")
        assert notify_calls == [expected_blocked], notify_calls
        notify_calls.clear()

        os.environ[NOTIFY_OFF_ENV] = "off"
        cmd_note(fixture_ledger.as_posix(), "NOTIFYTEST", "BLOCKED: should not poke while off", **fixture_kwargs)
        assert notify_calls == [], f"TORAD_LEDGER_NOTIFY=off should suppress poke: {notify_calls}"

        os.environ[NOTIFY_OFF_ENV] = "on"
        os.environ[NOTIFY_ROLE_ENV] = "orchestrator"
        cmd_note(
            fixture_ledger.as_posix(),
            "NOTIFYTEST",
            "BLOCKED: should not poke for orchestrator role",
            **fixture_kwargs,
        )
        assert notify_calls == [], f"TORAD_SESSION_ROLE=orchestrator should suppress poke: {notify_calls}"
    finally:
        if previous_notify_env is None:
            os.environ.pop(NOTIFY_OFF_ENV, None)
        else:
            os.environ[NOTIFY_OFF_ENV] = previous_notify_env
        if previous_role_env is None:
            os.environ.pop(NOTIFY_ROLE_ENV, None)
        else:
            os.environ[NOTIFY_ROLE_ENV] = previous_role_env

    import io

    # Pointer-writer tests (G6, extending the same git-init'd fixture_root/fixture_script/
    # fixture_ledger above rather than duplicating a fresh fixture repo).
    registry_dir = Path(tempfile.mkdtemp(prefix="manifest-pointer-registry-"))
    (registry_dir / "session-old.json").write_text(
        json.dumps({"role": "builder", "seat": "seat-a", "updated": "2026-07-05T10:00:00Z"}),
        encoding="utf-8",
    )
    (registry_dir / "session-new.json").write_text(
        json.dumps({"role": "builder", "seat": "seat-a", "updated": "2026-07-05T11:00:00Z"}),
        encoding="utf-8",
    )
    pointer_state_dir = fixture_root / ".claude" / "state"
    pointer_file = pointer_state_dir / "ledger-active-session-new.json"
    pointer_kwargs = {"_alive": (lambda _sid: False), "_this_file": fixture_script.as_posix()}

    cmd_claim(fixture_ledger.as_posix(), "POINTERTEST", "seat-a", _registry_dir=registry_dir, **pointer_kwargs)
    assert pointer_file.is_file(), f"pointer not written: {sorted(pointer_state_dir.glob('*'))}"
    pointer_data = json.loads(pointer_file.read_text(encoding="utf-8"))
    assert pointer_data["item_id"] == "POINTERTEST", pointer_data
    assert pointer_data["ledger_path"] == str(fixture_ledger.resolve()), pointer_data
    assert pointer_data["seat"] == "seat-a", pointer_data
    assert "updated_at" in pointer_data, pointer_data
    # Newest-by-"updated" resolution: session-new (11:00) wins over session-old (10:00).
    assert not (pointer_state_dir / "ledger-active-session-old.json").exists(), (
        "the older registry entry must not have been chosen"
    )

    cmd_release_stale(
        fixture_ledger.as_posix(), "POINTERTEST", "seat-a", _alive=lambda _sid: False, _this_file=fixture_script.as_posix()
    )
    assert not pointer_file.exists(), "release-stale should have cleared the pointer"

    cmd_claim(
        fixture_ledger.as_posix(), "POINTERTEST", "seat-unregistered", _registry_dir=registry_dir, **pointer_kwargs
    )
    assert not any(pointer_state_dir.glob("ledger-active-*.json")), "unregistered seat must not write a pointer"

    cmd_claim(fixture_ledger.as_posix(), "POINTERTEST", "seat-a", _registry_dir=registry_dir, **pointer_kwargs)
    assert pointer_file.is_file(), "pointer should be re-written on reclaim"
    cmd_set_status(fixture_ledger.as_posix(), "POINTERTEST", "done", _this_file=fixture_script.as_posix())
    assert not pointer_file.exists(), "done flip should clear the pointer"

    # REV2-F5: a noop re-claim (the caller already owns the item) must still (re)write the
    # pointer — the ORIGINAL code explicitly skipped pointer writes on the noop path
    # (`if not noop: _write_pointer(...)`), so a pointer lost to a crash between a real
    # claim's ledger write and its pointer write could never self-repair via re-claim.
    cmd_add(fixture_ledger.as_posix(), "POINTERNOOP", "N", "noop pointer self-repair fixture", "a/**", "n/a")
    cmd_claim(fixture_ledger.as_posix(), "POINTERNOOP", "seat-a", _registry_dir=registry_dir, **pointer_kwargs)
    assert pointer_file.is_file(), "pointer not written on the initial claim"
    pointer_file.unlink()  # simulate a crash-lost pointer
    assert not pointer_file.exists()
    cmd_claim(fixture_ledger.as_posix(), "POINTERNOOP", "seat-a", _registry_dir=registry_dir, **pointer_kwargs)
    assert pointer_file.is_file(), "a noop re-claim must self-repair a crash-lost pointer"
    pointer_data_noop = json.loads(pointer_file.read_text(encoding="utf-8"))
    assert pointer_data_noop["item_id"] == "POINTERNOOP", pointer_data_noop

    # Canonical-guard reuse: a plain non-canonical tmp-copy ledger must never write a
    # pointer — proven against the REAL repo's real .claude/state dir, never just a fixture.
    real_root = _git_repo_root(path)
    real_state_before = sorted(
        (real_root / ".claude" / "state").glob("ledger-active-*.json")
    ) if real_root is not None else None
    tmp_pointer_ledger = tempfile.NamedTemporaryFile("w", delete=False, suffix=".toml")
    tmp_pointer_ledger.close()
    shutil.copy(path, tmp_pointer_ledger.name)
    cmd_add(tmp_pointer_ledger.name, "POINTERNONCANON", "P", "non-canonical pointer item", "a/**", "n/a")
    cmd_claim(tmp_pointer_ledger.name, "POINTERNONCANON", "seat-a", _registry_dir=registry_dir, _alive=lambda _sid: False)
    if real_root is not None:
        real_state_after = sorted((real_root / ".claude" / "state").glob("ledger-active-*.json"))
        assert real_state_before == real_state_after, (
            f"non-canonical claim must not touch the real .claude/state dir: "
            f"before={real_state_before} after={real_state_after}"
        )
    os.unlink(tmp_pointer_ledger.name)

    # Self-serve dispatch tests (G7A), extending the same git-init'd fixture_root/fixture_script
    # rather than duplicating a fresh fixture repo.
    from contextlib import redirect_stdout
    import io

    def _neutralize_done_items(ledger_path: Path) -> None:
        """Fixture hygiene: a copied base manifest may carry real, pre-existing done-but-
        unreviewed items (e.g. kotlin-hardening.toml's R6) — flip those to verified in this
        throwaway copy so review-debt-sensitive fixtures get a deterministic starting count
        regardless of what the base manifest happens to hold right now."""
        text = ledger_path.read_text(encoding="utf-8")
        ledger_path.write_text(text.replace('status = "done"', 'status = "verified"'), encoding="utf-8")

    # A dedicated ledger copy: fixture_ledger already carries 2 done items by this point
    # (NOTIFYTEST + POINTERTEST above), which would trip the review-debt brake before these
    # dispatch assertions even ran — same fixture_root/fixture_script, a fresh ledger file.
    fixture_dispatch_ledger = fixture_campaigns_dir / "fixture-dispatch.toml"
    shutil.copy(path, fixture_dispatch_ledger)
    _neutralize_done_items(fixture_dispatch_ledger)

    cmd_add(
        fixture_dispatch_ledger.as_posix(), "DISPATCH1", "N", "first queued dispatch item", "dispatch-a/**", "echo ok"
    )
    cmd_add(
        fixture_dispatch_ledger.as_posix(),
        "DISPATCH2",
        "N",
        "second queued dispatch item",
        "dispatch-b/**",
        "echo ok",
    )
    cmd_add(
        fixture_dispatch_ledger.as_posix(),
        "DISPATCHBUSY",
        "N",
        "already in-flight, blocks DISPATCH2 by fence overlap",
        "dispatch-b/sub/**",
        "echo ok",
        status="in_flight",
    )
    cmd_add(
        fixture_dispatch_ledger.as_posix(),
        "DISPATCHUNLISTED",
        "N",
        "eligible and non-overlapping but deliberately absent from next",
        "dispatch-c/**",
        "echo ok",
    )

    cmd_set_next(fixture_dispatch_ledger.as_posix(), "DISPATCH1, DISPATCH2")
    assert _read_next_ids(fixture_dispatch_ledger.as_posix()) == [
        "DISPATCH1",
        "DISPATCH2",
    ], "set-next round-trip failed"

    # CLI-birth path: a ledger created by `add` alone has no [campaign] section;
    # set-next must insert one (after any leading law banner) instead of dying.
    fixture_born_ledger = fixture_campaigns_dir / "fixture-cli-born.toml"
    cmd_add(
        fixture_born_ledger.as_posix(), "BORN1", "B", "cli-born item", "born/**", "echo ok"
    )
    cmd_add_law(fixture_born_ledger.as_posix(), "birth-law: banner precedes [campaign]")
    cmd_set_next(fixture_born_ledger.as_posix(), "BORN1")
    assert _read_next_ids(fixture_born_ledger.as_posix()) == ["BORN1"], (
        "CLI-born ledger: set-next did not bootstrap the [campaign] section"
    )
    tomllib.loads("".join(_read(fixture_born_ledger.as_posix())))

    dispatch_kwargs = {"_alive": (lambda _sid: False), "_this_file": fixture_script.as_posix()}

    first_buf = io.StringIO()
    with redirect_stdout(first_buf):
        cmd_next_packet(fixture_dispatch_ledger.as_posix(), "seat-dispatch-1", **dispatch_kwargs)
    first_out = first_buf.getvalue()
    assert "PACKET DISPATCH1" in first_out, first_out
    lines = _read(fixture_dispatch_ledger.as_posix())
    s, e = _find(lines, "DISPATCH1")
    assert 'status = "in_flight"' in "".join(lines[s:e]), "next-packet must claim the selected item"

    second_buf = io.StringIO()
    with redirect_stdout(second_buf):
        cmd_next_packet(fixture_dispatch_ledger.as_posix(), "seat-dispatch-2", **dispatch_kwargs)
    second_out = second_buf.getvalue()
    assert "queue empty or blocked" in second_out, second_out
    assert "DISPATCH1: status is in_flight, not todo" in second_out, second_out
    assert "DISPATCH2: fence overlaps with in_flight DISPATCHBUSY" in second_out, second_out
    assert "DISPATCHUNLISTED" not in second_out, (
        f"curation is the gate: an eligible item absent from next must never be selected: {second_out}"
    )

    fixture_debt_ledger = fixture_campaigns_dir / "fixture-debt.toml"
    shutil.copy(path, fixture_debt_ledger)
    _neutralize_done_items(fixture_debt_ledger)
    cmd_add(fixture_debt_ledger.as_posix(), "DEBT1", "N", "unreviewed done item one", "debt-a/**", "n/a", status="done")
    cmd_add(fixture_debt_ledger.as_posix(), "DEBT2", "N", "unreviewed done item two", "debt-b/**", "n/a", status="done")
    cmd_add(fixture_debt_ledger.as_posix(), "DEBTQUEUED", "N", "queued but braked by review debt", "debt-c/**", "echo ok")
    cmd_set_next(fixture_debt_ledger.as_posix(), "DEBTQUEUED")
    try:
        cmd_next_packet(
            fixture_debt_ledger.as_posix(),
            "seat-dispatch-3",
            _alive=lambda _sid: False,
            _this_file=fixture_script.as_posix(),
        )
        raise AssertionError("next-packet should refuse when review debt >= 2")
    except SystemExit as exc:
        assert "review debt 2" in str(exc), str(exc)
        assert "orchestrator review required before next dispatch" in str(exc), str(exc)

    # Handover tests (G9), extending the same registry_dir/fixture_root/fixture_script rather
    # than duplicating fresh fixtures; a dedicated ledger keeps this isolated from the review
    # debt already carried by fixture_ledger (NOTIFYTEST/POINTERTEST above).
    (registry_dir / "session-handover-old.json").write_text(
        json.dumps({"role": "builder", "seat": "seat-handover-new", "updated": "2026-07-05T12:00:00Z"}),
        encoding="utf-8",
    )
    (registry_dir / "session-handover-target.json").write_text(
        json.dumps({"role": "builder", "seat": "seat-handover-new", "updated": "2026-07-05T13:00:00Z"}),
        encoding="utf-8",
    )
    fixture_handover_ledger = fixture_campaigns_dir / "fixture-handover.toml"
    shutil.copy(path, fixture_handover_ledger)
    handover_kwargs = {"_registry_dir": registry_dir, "_this_file": fixture_script.as_posix()}
    handover_state_dir = fixture_root / ".claude" / "state"

    cmd_add(fixture_handover_ledger.as_posix(), "HANDOVERTEST", "N", "handover happy-path item", "handover-a/**", "n/a")
    cmd_claim(
        fixture_handover_ledger.as_posix(),
        "HANDOVERTEST",
        "seat-a",
        _alive=lambda _sid: False,
        **handover_kwargs,
    )
    old_pointer = handover_state_dir / "ledger-active-session-new.json"
    assert old_pointer.is_file(), f"claim should have written the pre-handover pointer: {sorted(handover_state_dir.glob('*'))}"

    try:
        cmd_handover(fixture_handover_ledger.as_posix(), "HANDOVERTEST", "seat-handover-new", "not-the-owner", **handover_kwargs)
        raise AssertionError("handover should refuse a wrong --by")
    except SystemExit as exc:
        assert "is owned by seat-a; you are not-the-owner (requested: not-the-owner)" in str(exc), str(exc)
        assert "handover must be run by the owner seat" in str(exc), str(exc)
        assert "manifest.py release-stale HANDOVERTEST --by <your seat>" in str(exc), str(exc)

    cmd_add(fixture_handover_ledger.as_posix(), "HANDOVERTODO", "N", "still todo, not eligible", "handover-b/**", "n/a")
    try:
        cmd_handover(fixture_handover_ledger.as_posix(), "HANDOVERTODO", "seat-handover-new", "seat-a", **handover_kwargs)
        raise AssertionError("handover should refuse a todo item")
    except SystemExit as exc:
        assert "is todo — handover only in_flight items" in str(exc), str(exc)

    cmd_handover(fixture_handover_ledger.as_posix(), "HANDOVERTEST", "seat-handover-new", "seat-a", **handover_kwargs)
    lines = _read(fixture_handover_ledger.as_posix())
    s, e = _find(lines, "HANDOVERTEST")
    blk = "".join(lines[s:e])
    assert _owned_owner(lines, s, e) == "seat-handover-new", blk
    assert 'status = "in_flight"' in blk, "handover must not change status"
    assert not old_pointer.exists(), "handover must clear the pre-handover pointer"
    new_pointer = handover_state_dir / "ledger-active-session-handover-target.json"
    assert new_pointer.is_file(), f"handover must write a pointer for --to: {sorted(handover_state_dir.glob('*'))}"
    assert not (handover_state_dir / "ledger-active-session-handover-old.json").exists(), (
        "the older registry row must not have been chosen for handover"
    )
    new_pointer_data = json.loads(new_pointer.read_text(encoding="utf-8"))
    assert new_pointer_data["item_id"] == "HANDOVERTEST", new_pointer_data
    assert new_pointer_data["seat"] == "seat-handover-new", new_pointer_data

    cmd_add(fixture_handover_ledger.as_posix(), "HANDOVERTEST2", "N", "unregistered-to item", "handover-c/**", "n/a")
    cmd_claim(
        fixture_handover_ledger.as_posix(),
        "HANDOVERTEST2",
        "seat-a",
        _alive=lambda _sid: False,
        **handover_kwargs,
    )
    stderr_buf = io.StringIO()
    with redirect_stderr(stderr_buf):
        cmd_handover(fixture_handover_ledger.as_posix(), "HANDOVERTEST2", "seat-nowhere", "seat-a", **handover_kwargs)
    lines2 = _read(fixture_handover_ledger.as_posix())
    s2, e2 = _find(lines2, "HANDOVERTEST2")
    blk2 = "".join(lines2[s2:e2])
    assert _owned_owner(lines2, s2, e2) == "seat-nowhere", (
        f"the CLAIM line must land even for an unregistered --to: {blk2}"
    )
    assert "warning: handover pointer skipped for seat-nowhere (no registry row)" in stderr_buf.getvalue(), (
        stderr_buf.getvalue()
    )
    assert not any("nowhere" in p.name for p in handover_state_dir.glob("*")), (
        "an unregistered --to must not write a pointer"
    )

    shutil.rmtree(registry_dir, ignore_errors=True)
    os.unlink(tmp_notify.name)
    shutil.rmtree(fixture_root, ignore_errors=True)

    try:
        _parse_single_flag(["--session", "a", "--session", "b"], "--session")
        raise AssertionError("repeated --session should fail")
    except SystemExit:
        pass

    try:
        _parse_single_flag([], "--session")
        raise AssertionError("missing --session should fail")
    except SystemExit:
        pass

    # NOTIFY-1 events round-trip: the canonical-marked fixtures above (NOTIFYTEST/
    # POINTERTEST/... claims) journaled real lines into this run's scratch fleet root;
    # the events read verb must see them (exit 0 + printed lines) without writing.
    import contextlib
    import io

    events_journal = _fleet_journal_path()
    journal_bytes_before = events_journal.read_bytes() if events_journal.is_file() else b""
    events_out = io.StringIO()
    try:
        with contextlib.redirect_stdout(events_out):
            cmd_events(tmp_claim.name, after_seq="-1", exclude_seat="no-such-seat")
        raise AssertionError("events must sys.exit")
    except SystemExit as exc:
        assert exc.code == 0, "events: expected journaled claim lines from the canonical fixtures"
    assert '"verb": "claim"' in events_out.getvalue(), "events output must carry the claim lines"
    events_out = io.StringIO()
    try:
        with contextlib.redirect_stdout(events_out):
            cmd_events(tmp_claim.name, after_seq="-1", verbs="no-such-verb")
        raise AssertionError("events must sys.exit")
    except SystemExit as exc:
        assert exc.code == 1, "events: a non-matching verb filter must exit 1 (Monitor keeps waiting)"
    journal_bytes_after = events_journal.read_bytes() if events_journal.is_file() else b""
    assert journal_bytes_after == journal_bytes_before, "events must be READ-ONLY on the journal"

    with open(tmp_claim.name, "rb") as f:
        tomllib.load(f)
    os.unlink(tmp.name)
    os.unlink(tmp_fence.name)
    os.unlink(tmp_verify.name)
    os.unlink(tmp_packet.name)
    os.unlink(tmp_no_verify.name)
    os.unlink(tmp_destructive.name)
    os.unlink(tmp_apostrophe.name)
    os.unlink(tmp_claim.name)

    # D11: id namespaces are PER-LEDGER by design (each campaign numbers its own G-/D-/S-series),
    # and every access is ledger-path-scoped, so cross-ledger reuse is harmless — NOT an invariant
    # to enforce. selftest reports the standing count as health context only (never a per-id flood:
    # that would be cry-wolf noise). The NEXT NEW collision is caught where it matters — at `add`.
    duplicates = _cross_ledger_duplicate_ids()
    dup_note = (
        f"; {len(duplicates)} cross-ledger id reuse(s) (per-ledger namespace, by design)"
        if duplicates else "; no cross-ledger id reuse"
    )
    print(
        "selftest OK (add/set-status/note/verdict/add-law/edit-fence/edit-verify/packet/claim/release-stale/events "
        f"round-trip + valid TOML{dup_note})"
    )


def main(argv):
    args = list(argv[1:])
    path = DEFAULT
    explicit_path = bool(args and args[0].endswith(".toml"))
    if explicit_path:
        path = args.pop(0)
    if not args:
        sys.exit(__doc__)
    cmd, rest = args[0], args[1:]
    if cmd == "list":
        kw = {}
        while rest:
            flag = rest.pop(0)
            if flag == "--status":
                kw["status"] = rest.pop(0)
            elif flag == "--phase":
                kw["phase"] = rest.pop(0)
        cmd_list(path, **kw)
    elif cmd == "get":
        cmd_get(path, rest[0])
    elif cmd == "next":
        cmd_next(path)
    elif cmd == "set-status":
        if len(rest) < 2:
            sys.exit("error: set-status requires <ID> <status> [--proof TOKEN]")
        proof = None
        if len(rest) > 2:
            proof = _parse_single_flag(rest[2:], "--proof")
        cmd_set_status(path, rest[0], rest[1], proof=proof)
    elif cmd == "proof-payload":
        if not rest:
            sys.exit("error: proof-payload requires <ID> [--nonce N] [--issued-at ISO] [--key-id ID]")
        item_id = rest.pop(0)
        kw = {}
        while rest:
            flag = rest.pop(0)
            if flag not in {"--nonce", "--issued-at", "--key-id"} or not rest:
                sys.exit("error: proof-payload usage: <ID> [--nonce N] [--issued-at ISO] [--key-id ID]")
            kw[flag.lstrip("-").replace("-", "_")] = rest.pop(0)
        cmd_proof_payload(path, item_id, **kw)
    elif cmd == "verify-signatures":
        rest, strict = _pop_bare_flag(rest, "--strict")
        require_proof_item = None
        if rest:
            require_proof_item = _parse_single_flag(rest, "--require-proof")
        cmd_verify_signatures(path, require_proof_item=require_proof_item, strict=strict)
    elif cmd == "backfill-signatures":
        dry_run = False
        private_key_path = None
        while rest:
            flag = rest.pop(0)
            if flag == "--dry-run":
                dry_run = True
            elif flag == "--private-key" and rest:
                private_key_path = rest.pop(0)
            else:
                sys.exit("error: backfill-signatures usage: [--dry-run] [--private-key PATH]")
        cmd_backfill_signatures(path, private_key_path=private_key_path, dry_run=dry_run)
    elif cmd == "edit-fence":
        rest, override_bare_dir = _pop_bare_flag(rest, "--override-bare-dir")
        if len(rest) != 2:
            sys.exit('error: edit-fence requires <ID> "f1, f2, ..." [--override-bare-dir]')
        cmd_edit_fence(path, rest[0], rest[1], override_bare_dir=override_bare_dir)
    elif cmd == "edit-verify":
        if len(rest) != 2:
            sys.exit('error: edit-verify requires <ID> "cmd"')
        cmd_edit_verify(path, rest[0], rest[1])
    elif cmd == "claim":
        if not rest:
            sys.exit("error: claim requires <ID> --session <sid> [--lease CLAUSE]")
        claim_rest, override_retired = _pop_bare_flag(rest[1:], "--override-retired")
        session_group, lease_group = _split_two_flags(claim_rest, "--session", "--lease")
        claim_kwargs = {"override_retired": override_retired}
        if lease_group:
            claim_kwargs["lease"] = _parse_lease_clause(_parse_single_flag(lease_group, "--lease"))
        cmd_claim(path, rest[0], _parse_single_flag(session_group, "--session"), **claim_kwargs)
    elif cmd == "release-stale":
        if not rest:
            sys.exit("error: release-stale requires <ID> --by <sid>")
        cmd_release_stale(path, rest[0], _parse_single_flag(rest[1:], "--by"))
    elif cmd == "handover":
        if not rest:
            sys.exit("error: handover requires <ID> --to <seat> --by <owner-seat>")
        handover_rest, override_retired = _pop_bare_flag(rest[1:], "--override-retired")
        to_group, by_group = _split_two_flags(handover_rest, "--to", "--by")
        cmd_handover(
            path,
            rest[0],
            _parse_single_flag(to_group, "--to"),
            _parse_single_flag(by_group, "--by"),
            override_retired=override_retired,
        )
    elif cmd == "note":
        # D13(c): exactly <ID> "text" — free text is NEVER reparsed as flags, and both
        # under-arity (traceback) and extra tokens (silently dropped text) are usage errors.
        if len(rest) != 2:
            sys.exit('usage: note <ID> "text"  (quote the text — it is taken verbatim, never parsed)')
        cmd_note(path, rest[0], rest[1])
    elif cmd == "gym-kpi":
        cmd_gym_kpi(path)
    elif cmd == "verdict-backfill":
        rest, dry_run = _pop_bare_flag(rest, "--dry-run")
        rest, repair = _pop_bare_flag(rest, "--repair")
        if rest:
            sys.exit("error: verdict-backfill usage: [--dry-run] [--repair]")
        cmd_verdict_backfill(path, dry_run=dry_run, repair=repair)
    elif cmd == "lineage-backfill":
        rest, dry_run = _pop_bare_flag(rest, "--dry-run")
        if rest:
            sys.exit("error: lineage-backfill usage: [--dry-run]")
        cmd_lineage_backfill(path, dry_run=dry_run)
    elif cmd == "verdict":
        usage = (
            "error: verdict usage: <ID> --outcome accepted|redo|blocked "
            "[--gap TEXT] [--contradiction LOCUS] [--env-failure]"
        )
        if not rest:
            sys.exit(usage)
        item_id = rest.pop(0)
        rest, env_failure = _pop_bare_flag(rest, "--env-failure")
        kw = {"env_failure": env_failure}
        while rest:
            flag = rest.pop(0)
            if flag == "--outcome" and rest:
                kw["outcome"] = rest.pop(0)
            elif flag == "--gap" and rest:
                kw["gap"] = rest.pop(0)
            elif flag == "--contradiction" and rest:
                kw["contradiction"] = rest.pop(0)
            else:
                sys.exit(usage)
        if "outcome" not in kw:
            sys.exit(usage)
        cmd_verdict(path, item_id, **kw)
    elif cmd == "cost":
        if not rest:
            sys.exit("error: cost requires <ID>")
        item_id = rest.pop(0)
        kw = {
            "db_path": DEFAULT_WORKSPACES_DB,
            "pricing_path": DEFAULT_LITELLM_PRICING,
            "now_text": None,
        }
        while rest:
            flag = rest.pop(0)
            if flag == "--db":
                if not rest:
                    sys.exit("error: --db requires a value")
                kw["db_path"] = rest.pop(0)
            elif flag == "--pricing":
                if not rest:
                    sys.exit("error: --pricing requires a value")
                kw["pricing_path"] = rest.pop(0)
            elif flag == "--now":
                if not rest:
                    sys.exit("error: --now requires a value")
                kw["now_text"] = rest.pop(0)
            else:
                sys.exit(f"error: unexpected argument {flag!r}")
        cmd_cost(path, item_id, **kw)
    elif cmd == "scope-diff":
        if len(rest) < 3 or "--repo" not in rest:
            sys.exit("error: scope-diff requires <base_sha> --repo <path> --files <f1> [f2 ...]")
        base_sha = rest.pop(0)
        repo_val = None
        files_val: list[str] = []
        while rest:
            flag = rest.pop(0)
            if flag == "--repo":
                if not rest:
                    sys.exit("error: --repo requires a value")
                repo_val = rest.pop(0)
            elif flag == "--files":
                while rest and not rest[0].startswith("--"):
                    files_val.append(rest.pop(0))
            else:
                sys.exit(f"error: unexpected argument {flag!r}")
        if repo_val is None:
            sys.exit("error: scope-diff requires --repo <path>")
        cmd_scope_diff(path, base_sha, repo_val, files_val)
    elif cmd == "seed-episode":
        if len(rest) != 1:
            sys.exit("error: seed-episode requires <out_path>")
        cmd_seed_episode(path, rest[0])
    elif cmd == "fence-uncommitted":
        if len(rest) != 1:
            sys.exit("error: fence-uncommitted requires <ID>")
        cmd_fence_uncommitted(path, rest[0])
    elif cmd == "stale-claims":
        minutes = 30.0
        if rest:
            if len(rest) == 2 and rest[0] == "--minutes":
                try:
                    minutes = float(rest[1])
                except ValueError:
                    sys.exit(f"error: --minutes requires a number (got {rest[1]!r})")
            else:
                sys.exit("error: stale-claims usage: stale-claims [--minutes N]")
        cmd_stale_claims(path, minutes)
    elif cmd == "add-law":
        cmd_add_law(path, rest[0])
    elif cmd == "laws":
        cmd_laws(path, aggregate=not explicit_path)
    elif cmd == "packet":
        if len(rest) != 1:
            sys.exit("error: packet requires <ID>")
        cmd_packet(path, rest[0])
    elif cmd == "dispatch":
        if not rest:
            sys.exit("error: dispatch requires <ID> --to <seat>")
        cmd_dispatch(path, rest[0], _parse_single_flag(rest[1:], "--to"))
    elif cmd == "fence-check":
        if len(rest) != 2:
            sys.exit("error: fence-check requires <ID> <pattern>")
        cmd_fence_check(path, rest[0], rest[1])
    elif cmd == "amend-header":
        cmd_amend_header(path, rest[0], rest[1])
    elif cmd == "set-next":
        if len(rest) != 1:
            sys.exit('error: set-next requires "ID1, ID2, ..."')
        cmd_set_next(path, rest[0])
    elif cmd == "next-packet":
        cmd_next_packet(path, _parse_single_flag(rest, "--session"))
    elif cmd == "events":
        ev_kw = {}
        while rest:
            flag = rest.pop(0)
            if not flag.startswith("--") or not rest:
                sys.exit("usage: events [--after-seq N] [--exclude-seat S] [--verbs v1,v2]")
            ev_kw[flag.lstrip("-").replace("-", "_")] = rest.pop(0)
        cmd_events(
            path,
            after_seq=ev_kw.get("after_seq"),
            exclude_seat=ev_kw.get("exclude_seat"),
            verbs=ev_kw.get("verbs"),
        )
    elif cmd == "add":
        rest, override_bare_dir = _pop_bare_flag(rest, "--override-bare-dir")
        kw = {}
        while rest:
            flag = rest.pop(0)
            # D13(b): a trailing valueless flag (or a stray non-flag token) must print usage,
            # never traceback on an empty pop — an instrument that crashes on its own usage
            # errors trains users to stop asking it questions.
            if not flag.startswith("--") or not rest:
                sys.exit(
                    "usage: add --id <ID> --phase <P> --title <T> "
                    "[--files <f1, f2, ...>] [--verify <cmd>] [--status <s>]"
                )
            kw[flag.lstrip("-").replace("-", "_")] = rest.pop(0)
        cmd_add(
            path,
            kw["id"],
            kw["phase"],
            kw["title"],
            kw.get("files", ""),
            kw.get("verify", "n/a"),
            kw.get("status", "todo"),
            override_bare_dir=override_bare_dir,
        )
    elif cmd == "remove":
        if len(rest) != 1:
            sys.exit("error: remove requires <ID>  (pristine unclaimed note-free todo items only)")
        cmd_remove(path, rest[0])
    elif cmd == "scan-bare-fences":
        cmd_scan_bare_fences(path)
    elif cmd == "selftest":
        cmd_selftest(path)
    else:
        sys.exit(f"unknown command {cmd!r}\n{__doc__}")


if __name__ == "__main__":
    main(sys.argv)
