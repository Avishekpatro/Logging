# AI Events Schema v1 (`ai-events/v1`)

This repository uses a **standardized append-only JSON Lines** stream as the core integration point for:

- IDE / AI tool event producers (Cursor hooks, IDE plugins, agents)
- Git correlation (commit-time “accepted” measurement)
- Telemetry exporters (OpenTelemetry)

Each line is a single JSON object event.

## File location (default)

`.cursor/hooks/state/ai_events.jsonl`

Producers may write elsewhere if configured, but the forwarder expects this path by default.

## Event: `file_edit`

Minimal required fields:

- `schema_version`: `"ai-events/v1"`
- `ts`: UTC timestamp string
- `producer`: producer identifier (examples: `"cursor"`, `"vscode"`, `"jetbrains"`, `"cli-agent"`)
- `event_type`: `"file_edit"`
- `file_path`: **repo-relative path** preferred (fallback: absolute path)
- `edits`: array of edits
  - `old_text`: string (may be empty)
  - `new_text`: string (may be empty)
  - `range` (optional): for precise edits (Tab/inline completion, etc.)
    - `start_line_number`, `start_column`, `end_line_number`, `end_column`

Optional fields:

- `hook`: producer-specific hook/action name (e.g. `"afterTabFileEdit"`)
- `agent`: Cursor model / agent id when known (e.g. `"composer-2-fast"`); copied from hook payload `model` / `agent` / `cursor_model`
- `repo_root`: absolute repo root (if known)
- `session_id`, `suggestion_id`: stable IDs if the producer can provide them

### Example

```json
{
  "schema_version": "ai-events/v1",
  "ts": "2026-03-17T22:10:00Z",
  "producer": "cursor",
  "event_type": "file_edit",
  "hook": "afterTabFileEdit",
  "repo_root": "/Users/me/repo",
  "file_path": "src/main/java/com/example/Foo.java",
  "edits": [
    {
      "old_text": "",
      "new_text": "class Foo {}\n",
      "range": { "start_line_number": 1, "start_column": 1, "end_line_number": 1, "end_column": 1 }
    }
  ]
}
```

## Notes on metrics

- Producers should emit **what was applied** (edits), not “accepted/rejected”.
- Commit-time correlation is the source of truth for **accepted** measurement.
- Blank/whitespace-only lines are typically excluded from LOC metrics.

