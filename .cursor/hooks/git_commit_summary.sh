#!/bin/sh
# Cursor hook: beforeShellExecution (matcher: git commit ...)
# Prints commit-time AI efficiency for staged files, then allows the commit to proceed.
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
    echo "" >&2
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
    echo "  Cursor AI — commit-time efficiency (staged snapshot)" >&2
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
    # compile + run so it works even if ai-metrics-forwarder-java/target was cleaned
    if ! mvn -f ai-metrics-forwarder-java/pom.xml -q compile exec:java -Dexec.args="--commit --no-otel"; then
      echo "[cursor-ai] Efficiency step failed (commit still allowed)." >&2
    fi
    echo "" >&2
    ;;
esac

printf '%s\n' '{"continue": true, "permission": "allow"}'
