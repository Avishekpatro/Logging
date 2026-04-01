#!/bin/sh
set -eu

# Cursor hook: beforeShellExecution
# If the user runs "git commit ...", print an AI acceptance summary based on staged files.
# This hook is fail-open: it never blocks the command.

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
    # Best-effort: run correlator, but never block commit.
    mvn -f ai-metrics-forwarder-java/pom.xml -q exec:java -Dexec.args="--commit --no-otel" || true
    ;;
esac

# Allow the shell command to proceed.
printf '%s\n' '{"continue": true, "permission": "allow"}'

