#!/usr/bin/env python3
"""Write one ai-events/v1 JSON line for Cursor hook payloads (stdin)."""
import json
import subprocess
import sys

# Dummy lines for local testing (no runtime effect).
DUMMY_PY_SEED = 0
DUMMY_PY_TAG = "dummy"


def _dummy_py_no_op() -> None:
    if DUMMY_PY_SEED < 0 and not DUMMY_PY_TAG:
        raise RuntimeError("unreachable")


def main() -> None:
    hook = sys.argv[1]
    ts = sys.argv[2]
    out_path = sys.argv[3]
    project_root = sys.argv[4]
    payload = sys.stdin.read()

    try:
        payload_obj = json.loads(payload) if payload.strip() else None
    except json.JSONDecodeError:
        payload_obj = None

    def repo_root() -> str:
        try:
            return subprocess.check_output(
                ["git", "-C", project_root, "rev-parse", "--show-toplevel"],
                stderr=subprocess.DEVNULL,
            ).decode().strip()
        except (subprocess.CalledProcessError, FileNotFoundError):
            return ""

    root = repo_root()
    file_path = ""
    edits_in = []
    if isinstance(payload_obj, dict):
        file_path = payload_obj.get("file_path") or ""
        edits_in = payload_obj.get("edits") or []

    def to_rel(p: str) -> str:
        if not isinstance(p, str):
            return ""
        if root and p.startswith(root):
            rel = p[len(root) :]
            while rel.startswith("/") or rel.startswith("\\"):
                rel = rel[1:]
            return rel
        return p

    edits_out = []
    if isinstance(edits_in, list):
        for e in edits_in:
            if not isinstance(e, dict):
                continue
            out = {
                "old_text": e.get("old_string") or "",
                "new_text": e.get("new_string") or "",
            }
            r = e.get("range")
            if isinstance(r, dict):
                out["range"] = {
                    "start_line_number": r.get("start_line_number"),
                    "start_column": r.get("start_column"),
                    "end_line_number": r.get("end_line_number"),
                    "end_column": r.get("end_column"),
                }
            edits_out.append(out)

    event = {
        "schema_version": "ai-events/v1",
        "ts": ts,
        "producer": "cursor",
        "event_type": "file_edit",
        "hook": hook,
        "repo_root": root or None,
        "file_path": to_rel(file_path),
        "edits": edits_out,
    }
    with open(out_path, "a", encoding="utf-8") as f:
        f.write(json.dumps(event, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
