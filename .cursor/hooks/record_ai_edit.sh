#!/bin/sh
set -eu

# Cursor producer (generic architecture):
# - Reads Cursor hook JSON payload from stdin
# - Writes standardized JSONL events to .cursor/hooks/state/ai_events.jsonl
# - This is an "event producer" only; correlation/metrics happen elsewhere.

HOOK_NAME="${1:-unknown}"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"
STATE_DIR="$PROJECT_ROOT/.cursor/hooks/state"
OUT_PATH="$STATE_DIR/ai_events.jsonl"

mkdir -p "$STATE_DIR"

# Read stdin fully (the hook payload JSON).
payload="$(cat)"

# Standardized event envelope (ai-events/v1).
ts="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

# Use python if available for safe JSON string escaping; otherwise best-effort.
if command -v python3 >/dev/null 2>&1; then
  printf '%s' "$payload" | python3 "$SCRIPT_DIR/record_ai_edit.py" "$HOOK_NAME" "$ts" "$OUT_PATH" "$PROJECT_ROOT"
else
  # Fallback: record a minimal, legacy-style envelope.
  printf '{"ts":"%s","hook":"%s","payload":%s}\n' "$ts" "$HOOK_NAME" "$payload" >> "$OUT_PATH" || true
fi

# Hooks should output valid JSON.
printf '%s\n' "{}"
