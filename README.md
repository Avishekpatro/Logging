## Cursor Hooks → OpenTelemetry metrics (generic)

This project includes Cursor Hooks that record standardized **AI edit events** whenever Cursor AI edits a file, plus a Java forwarder that turns those events into OpenTelemetry **metrics**.

### What it captures (events)

- **Tab (inline completions)** via `afterTabFileEdit` (includes precise edit ranges)
- **Agent edits** via `afterFileEdit`

Events are written to `.cursor/hooks/state/ai_events.jsonl`.

Metrics are exported via **OTLP/HTTP** by the Java forwarder.

### Event schema (stable contract)

Each line in `.cursor/hooks/state/ai_events.jsonl` is a JSON object:

- `schema_version`: currently `ai-events/v1`
- `producer`: event producer (Cursor is one producer; other IDEs/tools can add more)
- `event_type`: currently `file_edit`
- `file_path`: repo-relative preferred
- `edits[]`: normalized edits (`old_text`, `new_text`, optional `range`)

Teams can build their own forwarder in any language by tailing this file and converting `payload.edits[*].old_string/new_string`
into counts, acceptance metrics, etc.

See `docs/event-schema-v1.md` for the full schema.

### Files

- `.cursor/hooks.json`: hook registration
- `.cursor/hooks/record_ai_edit.sh`: generic hook recorder (reads JSON from stdin, writes JSONL events)
- `ai-metrics-forwarder-java/`: Java forwarder that exports OTel metrics from recorded events
- `otel-collector.yaml`: Collector config (OTLP receiver + Prometheus exporter)
- `docker-compose.otel.yaml`: runs the Collector locally

### Run locally (Collector)

1) Start the collector:

```bash
docker compose -f docker-compose.otel.yaml up
```

2) Install hook dependencies:

```bash
mvn -f ai-metrics-forwarder-java/pom.xml -q test -DskipTests
```

3) Start the Java forwarder:

```bash
mvn -f ai-metrics-forwarder-java/pom.xml -q exec:java -Dexec.args="--print --no-otel"
```

4) Use Cursor normally (Tab completions / Agent edits). The hooks will record events; the forwarder will emit metrics.

### Commit-time efficiency (correlation)

Efficiency compares **staged** file lines to AI events in `.cursor/hooks/state/ai_events.jsonl` and prints:

`[cursor-ai][commit] generated_loc=… accepted_loc=… rejected_loc=… efficiency=…%`

**Reliable (recommended): Git `pre-commit` hook** — output always appears in the same terminal as `git commit`:

```bash
git config core.hooksPath .githooks
chmod +x .githooks/pre-commit
```

(One-time per clone; `core.hooksPath` is stored in this repo’s local `.git/config`.)

**Optional:** Cursor’s `beforeShellExecution` hook (`.cursor/hooks.json`) may **not** print in the integrated terminal in some setups; the Git hook above does not depend on Cursor for visibility.

Manual run (same logic):

```bash
mvn -f ai-metrics-forwarder-java/pom.xml -q compile exec:java -Dexec.args="--commit --no-otel"
```

### View metrics

- **Prometheus scrape endpoint**: `http://localhost:9464/metrics`

### Configuration

- **OTLP endpoint**: set `OTEL_EXPORTER_OTLP_ENDPOINT` (default `http://localhost:4318`)
- **Events path**: set `CURSOR_AI_EVENTS_PATH` (default `.cursor/hooks/state/ai_events.jsonl`)

