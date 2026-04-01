#!/bin/sh
# Cursor hook: beforeShellExecution (matcher: git commit ...)
# Runs same commit-time efficiency as .githooks/pre-commit (output only if Cursor AI edits exist).
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

payload="$(cat || true)"

command="$(printf '%s' "$payload" | python3 -c 'import json,sys
try:
  obj=json.load(sys.stdin)
  print(obj.get("command",""))
except Exception:
  print("")
')"

case "$command" in
  git\ commit* )
    mvn -f ai-metrics-forwarder-java/pom.xml -q compile exec:java -Dexec.args="--commit --no-otel" 2>&1 \
      || echo "[cursor-ai] Efficiency step failed (commit still allowed)." >&2
    ;;
esac

printf '%s\n' '{"continue": true, "permission": "allow"}'
