package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.metrics.export.MetricExporter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class Forwarder {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AttributeKey<String> ATTR_HOOK = AttributeKey.stringKey("hook");
    private static final AttributeKey<String> ATTR_LANGUAGE = AttributeKey.stringKey("language");
    /** Cursor model / agent id (e.g. composer-2-fast), from hook payload. */
    private static final AttributeKey<String> ATTR_AGENT = AttributeKey.stringKey("cursor.agent");

    /**
     * OpenTelemetry metrics for the live forwarder. With OTLP, created lazily on first Cursor AI edit
     * so we do not run periodic exports when idle.
     */
    private static final class LiveOtel {
        private final SdkMeterProvider meterProvider;
        private final LongCounter aiGeneratedLoc;
        private final LongCounter aiReplacedLoc;
        private final LongUpDownCounter aiNetLoc;
        private final LongCounter aiEditEvents;

        private LiveOtel(
                SdkMeterProvider meterProvider,
                LongCounter aiGeneratedLoc,
                LongCounter aiReplacedLoc,
                LongUpDownCounter aiNetLoc,
                LongCounter aiEditEvents) {
            this.meterProvider = meterProvider;
            this.aiGeneratedLoc = aiGeneratedLoc;
            this.aiReplacedLoc = aiReplacedLoc;
            this.aiNetLoc = aiNetLoc;
            this.aiEditEvents = aiEditEvents;
        }

        static LiveOtel createNoExport(Resource resource) {
            SdkMeterProvider mp = SdkMeterProvider.builder().setResource(resource).build();
            return buildMeters(mp);
        }

        static LiveOtel createWithOtlpExport(Resource resource) {
            String endpointBase = System.getenv().getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4318")
                    .replaceAll("/+$", "");
            MetricExporter exporter = OtlpHttpMetricExporter.builder()
                    .setEndpoint(endpointBase + "/v1/metrics")
                    .build();
            SdkMeterProvider mp = SdkMeterProvider.builder()
                    .setResource(resource)
                    .registerMetricReader(
                            PeriodicMetricReader.builder(exporter)
                                    .setInterval(Duration.ofSeconds(1))
                                    .build()
                    )
                    .build();
            return buildMeters(mp);
        }

        private static LiveOtel buildMeters(SdkMeterProvider meterProvider) {
            OpenTelemetrySdk otel = OpenTelemetrySdk.builder().setMeterProvider(meterProvider).build();
            GlobalOpenTelemetry.set(otel);
            Meter meter = otel.getMeter("cursor-hooks.ai-edits");
            LongCounter aiGeneratedLoc = meter.counterBuilder("ai_generated_loc_total")
                    .setDescription("Total LOC produced by Cursor AI file edits (new_string lines).")
                    .setUnit("1")
                    .build();
            LongCounter aiReplacedLoc = meter.counterBuilder("ai_replaced_loc_total")
                    .setDescription("Total LOC replaced/removed during Cursor AI edits (old_string lines).")
                    .setUnit("1")
                    .build();
            LongUpDownCounter aiNetLoc = meter.upDownCounterBuilder("ai_net_loc_total")
                    .setDescription("Net LOC change from Cursor AI edits (new - old).")
                    .setUnit("1")
                    .build();
            LongCounter aiEditEvents = meter.counterBuilder("ai_edit_events_total")
                    .setDescription("Count of Cursor AI edit events observed by hooks.")
                    .setUnit("1")
                    .build();
            return new LiveOtel(meterProvider, aiGeneratedLoc, aiReplacedLoc, aiNetLoc, aiEditEvents);
        }

        void record(long totalOld, long totalNew, long totalNet, Attributes attrs) {
            aiEditEvents.add(1, attrs);
            if (totalNew != 0) {
                aiGeneratedLoc.add(totalNew, attrs);
            }
            if (totalOld != 0) {
                aiReplacedLoc.add(totalOld, attrs);
            }
            if (totalNet != 0) {
                aiNetLoc.add(totalNet, attrs);
            }
        }

        void shutdown() {
            meterProvider.shutdown().join(5, TimeUnit.SECONDS);
        }
    }

    private static String repoId() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path name = cwd.getFileName();
        return name == null ? "unknown" : name.toString();
    }

    private static String guessLanguage(String filePath) {
        if (filePath == null) return "unknown";
        int idx = filePath.lastIndexOf('.');
        if (idx < 0 || idx == filePath.length() - 1) return "unknown";
        return filePath.substring(idx + 1).toLowerCase();
    }

    /** Model / agent name for OTLP (Cursor: usually {@code model} on hook payload; v1 events use {@code agent}). */
    private static String resolveCursorAgent(JsonNode event, JsonNode payload) {
        try {
            if (payload != null && event == payload) {
                if (event.has("agent") && !event.get("agent").isNull()) {
                    String a = event.get("agent").asText("").trim();
                    if (!a.isEmpty()) return a;
                }
            } else if (payload != null) {
                if (payload.has("model") && !payload.get("model").isNull()) {
                    String a = payload.get("model").asText("").trim();
                    if (!a.isEmpty()) return a;
                }
                if (payload.has("agent") && !payload.get("agent").isNull()) {
                    String a = payload.get("agent").asText("").trim();
                    if (!a.isEmpty()) return a;
                }
                if (payload.has("cursor_model") && !payload.get("cursor_model").isNull()) {
                    String a = payload.get("cursor_model").asText("").trim();
                    if (!a.isEmpty()) return a;
                }
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }

 

    private static long lineCount(String s) {
        if (s == null || s.isEmpty()) return 0;
        long count = 0;
        for (String line : s.split("\n", -1)) {
            if (line.trim().isEmpty()) continue; // ignore blank/whitespace-only lines
            count++;
        }
        return count;
    }

    private static long readOffset(Path offsetPath) {
        try {
            if (!Files.exists(offsetPath)) return 0;
            String t = Files.readString(offsetPath, StandardCharsets.UTF_8).trim();
            return t.isEmpty() ? 0 : Long.parseLong(t);
        } catch (Exception e) {
            return 0;
        }
    }

    private static void writeOffset(Path offsetPath, long off) {
        try {
            Files.writeString(offsetPath, Long.toString(off), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static String runCmd(List<String> command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        int code = p.waitFor();
        if (code != 0) throw new IOException("Command failed (" + code + "): " + String.join(" ", command));
        return sb.toString();
    }

    private static boolean isGitRepo() {
        try {
            runCmd(List.of("git", "rev-parse", "--is-inside-work-tree"));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Map<String, Map<String, Integer>> stagedLineMultisets(Set<String> stagedFiles) throws Exception {
        Map<String, Map<String, Integer>> out = new HashMap<>();
        for (String relPath : stagedFiles) {
            // Staged content for that path.
            String content;
            try {
                content = runCmd(List.of("git", "show", ":" + relPath));
            } catch (Exception e) {
                continue;
            }
            Map<String, Integer> counts = new HashMap<>();
            for (String line : content.split("\n", -1)) {
                if (line.trim().isEmpty()) continue; // ignore blank/whitespace-only lines
                counts.merge(line, 1, Integer::sum);
            }
            out.put(relPath, counts);
        }
        return out;
    }

    private static String toRepoRelative(String filePath, String repoRoot) {
        if (filePath == null) return "";
        if (repoRoot == null || repoRoot.isEmpty()) return filePath;
        if (filePath.startsWith(repoRoot)) {
            String rel = filePath.substring(repoRoot.length());
            while (rel.startsWith("/") || rel.startsWith("\\")) rel = rel.substring(1);
            return rel;
        }
        return filePath;
    }

    private static int countAndConsume(Map<String, Integer> multiset, String line) {
        Integer c = multiset.get(line);
        if (c == null || c <= 0) return 0;
        if (c == 1) multiset.remove(line);
        else multiset.put(line, c - 1);
        return 1;
    }

    private static int commitCorrelateAndPrint(Path eventsPath) throws Exception {
        if (!isGitRepo()) {
            return 0;
        }

        String repoRoot = runCmd(List.of("git", "rev-parse", "--show-toplevel")).trim();
        Set<String> stagedFiles = new HashSet<>();
        for (String line : runCmd(List.of("git", "diff", "--cached", "--name-only")).split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) stagedFiles.add(t);
        }
        if (stagedFiles.isEmpty()) {
            return 0;
        }

        Map<String, Map<String, Integer>> stagedLineCountsByFile = stagedLineMultisets(stagedFiles);

        Path commitOffsetPath = Paths.get(".cursor/hooks/state/ai_events.commit.offset").toAbsolutePath();
        long offset = readOffset(commitOffsetPath);
        byte[] bytes = Files.readAllBytes(eventsPath);
        if (offset > bytes.length) offset = 0;

        String chunk = new String(bytes, (int) offset, bytes.length - (int) offset, StandardCharsets.UTF_8);
        long newOffset = bytes.length;

        long generated = 0;
        long accepted = 0;
        LinkedHashSet<String> agentsContributing = new LinkedHashSet<>();

        for (String line : chunk.split("\n")) {
            if (line.trim().isEmpty()) continue;
            JsonNode event;
            try {
                event = MAPPER.readTree(line);
            } catch (IOException e) {
                continue;
            }
            // Support both:
            // - v1: {schema_version, producer, event_type, file_path, edits:[{old_text,new_text,range?}]}
            // - legacy: {ts, hook, payload:{file_path, edits:[{old_string,new_string,range?}]}}
            JsonNode payload;
            JsonNode edits;
            String filePath;

            if (event.has("schema_version") && "ai-events/v1".equals(event.get("schema_version").asText())
                    && event.has("event_type") && "file_edit".equals(event.get("event_type").asText())) {
                // Only measure Cursor-applied edits (other producers e.g. test scripts are ignored).
                String producer = event.has("producer") ? event.get("producer").asText("") : "cursor";
                if (!"cursor".equals(producer)) {
                    continue;
                }
                payload = event;
                edits = event.get("edits");
                filePath = event.has("file_path") ? event.get("file_path").asText("") : "";
            } else {
                payload = event.get("payload");
                if (payload == null || payload.isNull()) continue;
                edits = payload.get("edits");
                filePath = payload.has("file_path") ? payload.get("file_path").asText("") : "";
            }

            if (edits == null || !edits.isArray() || edits.isEmpty()) continue;

            String relPath = toRepoRelative(filePath, repoRoot);
            Map<String, Integer> stagedMultiset = stagedLineCountsByFile.get(relPath);
            if (stagedMultiset == null) continue; // only correlate staged files

            long generatedBeforeEvent = generated;
            String agentStr = resolveCursorAgent(event, payload);
            if (agentStr.length() > 128) agentStr = agentStr.substring(0, 128);

            for (JsonNode e : edits) {
                String newStr;
                if (payload == event) {
                    newStr = e.has("new_text") && !e.get("new_text").isNull() ? e.get("new_text").asText() : "";
                } else {
                    newStr = e.has("new_string") && !e.get("new_string").isNull() ? e.get("new_string").asText() : "";
                }
                if (newStr.isEmpty()) continue;
                // Count generated LOC and accepted LOC by checking presence in staged file.
                for (String genLine : newStr.split("\n", -1)) {
                    if (genLine.trim().isEmpty()) continue; // ignore blank/whitespace-only lines
                    generated += 1;
                    accepted += countAndConsume(stagedMultiset, genLine);
                }
            }
            if (generated > generatedBeforeEvent) {
                agentsContributing.add(agentStr);
            }
        }

        writeOffset(commitOffsetPath, newOffset);

        if (generated == 0) {
            // No Cursor AI lines in this commit window — do not print efficiency.
            return 0;
        }

        long rejected = Math.max(0, generated - accepted);
        double eff = 100.0 * accepted / (double) generated;
        String agentField;
        if (agentsContributing.isEmpty()) {
            agentField = "";
        } else if (agentsContributing.size() == 1) {
            agentField = " agent=" + agentsContributing.iterator().next();
        } else {
            agentField = " agents=" + String.join(",", agentsContributing);
        }
        System.err.println("");
        System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.err.println("  Cursor AI — commit-time efficiency (staged snapshot)");
        System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println(
                "[cursor-ai][commit]" + agentField +
                        " generated_loc=" + generated +
                        " accepted_loc=" + accepted +
                        " rejected_loc=" + rejected +
                        " efficiency=" + String.format("%.2f", eff) + "%"
        );
        System.err.println("");
        return 0;
    }

    public static void main(String[] args) throws Exception {
        boolean once = false;
        boolean print = false;
        boolean noOtel = false;
        boolean commit = false;
        for (String a : args) {
            if ("--once".equals(a)) once = true;
            if ("--print".equals(a)) print = true;
            if ("--no-otel".equals(a)) noOtel = true;
            if ("--commit".equals(a)) commit = true;
        }

        String eventsPathEnv = System.getenv("CURSOR_AI_EVENTS_PATH");
        Path eventsPath = Paths.get(eventsPathEnv != null ? eventsPathEnv : ".cursor/hooks/state/ai_events.jsonl")
                .toAbsolutePath();
        Path offsetPath = Paths.get(eventsPath.toString() + ".offset");

        Files.createDirectories(eventsPath.getParent());
        if (!Files.exists(eventsPath)) Files.writeString(eventsPath, "", StandardCharsets.UTF_8);

        if (commit) {
            commitCorrelateAndPrint(eventsPath);
            return;
        }

        Resource resource = Resource.getDefault().merge(
                Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), "cursor-hooks-forwarder",
                        AttributeKey.stringKey("service.version"), "0.1.0",
                        AttributeKey.stringKey("repo"), repoId()
                ))
        );

        // --no-otel: in-process metrics only (no export). Otherwise: lazy OTLP — no export until first Cursor AI edit.
        LiveOtel liveOtel = noOtel ? LiveOtel.createNoExport(resource) : null;

        long offset = readOffset(offsetPath);
        long totalGeneratedAll = 0;
        long totalAcceptedProxyAll = 0;

        while (true) {
            byte[] bytes = Files.readAllBytes(eventsPath);
            if (offset > bytes.length) offset = 0;

            if (offset < bytes.length) {
                String chunk = new String(bytes, (int) offset, bytes.length - (int) offset, StandardCharsets.UTF_8);
                offset = bytes.length;
                writeOffset(offsetPath, offset);

                for (String line : chunk.split("\n")) {
                    if (line.trim().isEmpty()) continue;

                    JsonNode event;
                    try {
                        event = MAPPER.readTree(line);
                    } catch (IOException e) {
                        continue;
                    }
                    JsonNode payload;
                    JsonNode edits;
                    String filePath;
                    String hook;

                    if (event.has("schema_version") && "ai-events/v1".equals(event.get("schema_version").asText())
                            && event.has("event_type") && "file_edit".equals(event.get("event_type").asText())) {
                        String producer = event.has("producer") ? event.get("producer").asText("") : "cursor";
                        if (!"cursor".equals(producer)) {
                            continue;
                        }
                        payload = event;
                        edits = event.get("edits");
                        filePath = event.has("file_path") ? event.get("file_path").asText("") : "";
                        hook = event.has("hook") ? event.get("hook").asText("file_edit") : "file_edit";
                    } else {
                        payload = event.get("payload");
                        if (payload == null || payload.isNull()) continue;
                        edits = payload.get("edits");
                        filePath = payload.has("file_path") ? payload.get("file_path").asText("") : "";
                        hook = event.has("hook") ? event.get("hook").asText("unknown") : "unknown";
                    }

                    if (edits == null || !edits.isArray() || edits.isEmpty()) continue;

                    long totalOld = 0;
                    long totalNew = 0;
                    long totalNet = 0;

                    for (JsonNode e : edits) {
                        String oldStr;
                        String newStr;
                        if (payload == event) {
                            oldStr = e.has("old_text") && !e.get("old_text").isNull() ? e.get("old_text").asText() : "";
                            newStr = e.has("new_text") && !e.get("new_text").isNull() ? e.get("new_text").asText() : "";
                        } else {
                            oldStr = e.has("old_string") && !e.get("old_string").isNull() ? e.get("old_string").asText() : "";
                            newStr = e.has("new_string") && !e.get("new_string").isNull() ? e.get("new_string").asText() : "";
                        }
                        long oldLoc = lineCount(oldStr);
                        long newLoc = lineCount(newStr);
                        totalOld += oldLoc;
                        totalNew += newLoc;
                        totalNet += (newLoc - oldLoc);
                    }

                    if (hook.length() > 128) hook = hook.substring(0, 128);

                    String language = guessLanguage(filePath);
                    if (language.length() > 128) language = language.substring(0, 128);

                    String agent = resolveCursorAgent(event, payload);
                    if (agent.length() > 128) agent = agent.substring(0, 128);

                    AttributesBuilder ab = Attributes.builder();
                    ab.put(ATTR_HOOK, hook);
                    ab.put(ATTR_LANGUAGE, language);
                    ab.put(ATTR_AGENT, agent);
                    Attributes attrs = ab.build();

                    if (noOtel) {
                        liveOtel.record(totalOld, totalNew, totalNet, attrs);
                    } else {
                        LiveOtel ot = liveOtel != null ? liveOtel : (liveOtel = LiveOtel.createWithOtlpExport(resource));
                        ot.record(totalOld, totalNew, totalNet, attrs);
                    }

                    // Console stats (local testing)
                    // NOTE: Cursor hooks record AI edits, not subsequent human edits.
                    // "accepted" here is a proxy: max(net_new_lines, 0).
                    long acceptedProxy = Math.max(totalNet, 0);
                    totalGeneratedAll += totalNew;
                    totalAcceptedProxyAll += acceptedProxy;

                    if (print) {
                        double eff = totalGeneratedAll == 0 ? 0.0 : (100.0 * totalAcceptedProxyAll / (double) totalGeneratedAll);
                        System.out.println(
                                "[cursor-ai] hook=" + hook +
                                        " agent=" + agent +
                                        " lang=" + language +
                                        " generated_loc=" + totalNew +
                                        " accepted_loc_proxy=" + acceptedProxy +
                                        " efficiency_proxy=" + String.format("%.2f", eff) + "%" +
                                        " totals(generated=" + totalGeneratedAll +
                                        ", accepted_proxy=" + totalAcceptedProxyAll + ")"
                        );
                    }
                }
            }

            if (once) break;
            TimeUnit.SECONDS.sleep(1);
        }

        if (liveOtel != null) {
            liveOtel.shutdown();
        }
    }
}

