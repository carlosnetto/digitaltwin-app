package com.matera.digitaltwin.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves i18n display templates for transaction metadata.
 *
 * ── Caching strategy ──────────────────────────────────────────────────────────
 * English ("en") is pre-loaded at startup. All other languages are loaded
 * lazily on the first request and cached indefinitely. A DB failure during
 * load returns null from the mapping function, which ConcurrentHashMap treats
 * as "do not store" — so the next request retries automatically.
 *
 * ── Template pre-parsing ──────────────────────────────────────────────────────
 * summary_data  : "Sent ${amount} ${currency} to ${recipient_name}"
 * detailed_data : [{"label":"Recipient","value":"${recipient_name}"}, ...]
 *
 * Both are parsed once at load time into typed Segment lists. Runtime resolution
 * is a plain O(n) string concatenation — no regex, no re-parsing per request.
 * Dotted paths (${custom.key}) are pre-split so navigation is also allocation-free.
 *
 * ── Language fallback ─────────────────────────────────────────────────────────
 * If no template exists for the requested (transCode, lang) pair, "en" is tried.
 * If "en" also has no template, Optional.empty() is returned — the caller should
 * display the raw ledger description.
 */
@Service
public class TransactionDisplayService {

    private static final Logger log = LoggerFactory.getLogger(TransactionDisplayService.class);

    private static final String DEFAULT_LANG = "en";

    private final JdbcTemplate jdbc;
    private final ObjectMapper  objectMapper;

    // lang → (transCode → pre-parsed template)
    // "en" is always present after @PostConstruct (or will be retried on first access)
    private final ConcurrentHashMap<String, Map<Integer, DisplayTemplate>> cache =
            new ConcurrentHashMap<>();

    public TransactionDisplayService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc         = jdbc;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        Map<Integer, DisplayTemplate> en = loadLanguage(DEFAULT_LANG);
        if (en != null) {
            cache.put(DEFAULT_LANG, en);
            log.info("TransactionDisplay: pre-loaded {} '{}' templates", en.size(), DEFAULT_LANG);
        } else {
            log.warn("TransactionDisplay: failed to pre-load '{}' — will retry on first request", DEFAULT_LANG);
        }
    }

    /**
     * Resolves the summary and detail fields for a transaction.
     *
     * @param transCode transaction code
     * @param lang      BCP-47 tag (e.g. "en", "pt-BR"); falls back to "en" if no template found
     * @param metadata  deserialized metadata blob
     * @return resolved display, or Optional.empty() if no template exists for this code
     */
    public Optional<ResolvedDisplay> resolve(int transCode, String lang,
                                             Map<String, Object> metadata) {
        DisplayTemplate template = findTemplate(transCode, lang);
        if (template == null && !DEFAULT_LANG.equals(lang)) {
            template = findTemplate(transCode, DEFAULT_LANG);   // language fallback
        }
        if (template == null) return Optional.empty();

        String summary = resolveSegments(template.summarySegments(), metadata);
        List<ResolvedField> fields = template.fields().stream()
                .map(f -> new ResolvedField(f.label(), resolveSegments(f.segments(), metadata)))
                .toList();
        return Optional.of(new ResolvedDisplay(summary, fields));
    }

    private DisplayTemplate findTemplate(int transCode, String lang) {
        // computeIfAbsent: if mapping fn returns null, nothing is stored → retry next call
        Map<Integer, DisplayTemplate> templates = cache.computeIfAbsent(lang, l -> {
            Map<Integer, DisplayTemplate> loaded = loadLanguage(l);
            if (loaded == null) {
                log.warn("TransactionDisplay: load failed for lang='{}', will retry on next request", l);
            } else {
                log.info("TransactionDisplay: lazily loaded {} '{}' templates", loaded.size(), l);
            }
            return loaded;
        });
        return templates != null ? templates.get(transCode) : null;
    }

    // ── DB load ───────────────────────────────────────────────────────────────

    private Map<Integer, DisplayTemplate> loadLanguage(String lang) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT trans_code, summary_data, detailed_data::text AS detailed_data
                    FROM digitaltwinapp.transaction_schema_i18n
                    WHERE lang = ?
                    """, lang);

            Map<Integer, DisplayTemplate> templates = new HashMap<>();
            for (var row : rows) {
                int    code        = ((Number) row.get("trans_code")).intValue();
                String summaryRaw  = (String)  row.get("summary_data");
                String detailedRaw = (String)  row.get("detailed_data");
                templates.put(code, new DisplayTemplate(
                        parseTemplate(summaryRaw  != null ? summaryRaw  : ""),
                        parseDetailedData(detailedRaw)));
            }
            return Map.copyOf(templates);   // immutable; empty map = valid (lang has no templates)
        } catch (Exception e) {
            log.error("TransactionDisplay: error loading lang='{}': {}", lang, e.getMessage(), e);
            return null;   // signals transient failure — caller must not cache
        }
    }

    // ── Template parsing (once at load time) ──────────────────────────────────

    private List<FieldTemplate> parseDetailedData(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, String>> raw = objectMapper.readValue(json, new TypeReference<>() {});
            return raw.stream()
                    .map(e -> new FieldTemplate(
                            e.getOrDefault("label", ""),
                            parseTemplate(e.getOrDefault("value", ""))))
                    .toList();
        } catch (Exception e) {
            log.warn("TransactionDisplay: failed to parse detailed_data JSON: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Parses a template string like "Sent ${amount} ${currency} to ${recipient_name}"
     * into an immutable list of typed segments. Dotted paths are pre-split at load
     * time so runtime navigation is a plain array walk. A malformed "${..." without
     * a closing brace is treated as a literal.
     */
    static List<Segment> parseTemplate(String template) {
        List<Segment> segments = new ArrayList<>();
        int i = 0;
        while (i < template.length()) {
            int start = template.indexOf("${", i);
            if (start == -1) {
                if (i < template.length()) segments.add(new Literal(template.substring(i)));
                break;
            }
            if (start > i) segments.add(new Literal(template.substring(i, start)));
            int end = template.indexOf('}', start + 2);
            if (end == -1) {
                segments.add(new Literal(template.substring(start)));   // malformed — treat as literal
                break;
            }
            segments.add(new PathRef(template.substring(start + 2, end).split("\\.", -1)));
            i = end + 1;
        }
        return List.copyOf(segments);
    }

    // ── Segment resolution (per request) ──────────────────────────────────────

    private static String resolveSegments(List<Segment> segments, Map<String, Object> metadata) {
        if (segments.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Segment seg : segments) {
            switch (seg) {
                case Literal(var text) -> sb.append(text);
                case PathRef(var path) -> {
                    Object val = navigatePath(metadata, path);
                    if (val != null) sb.append(val);
                }
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Object navigatePath(Map<String, Object> root, String[] path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> m)) return null;
            current = ((Map<String, Object>) m).get(key);
        }
        return current;
    }

    // ── Internal types (immutable after construction) ─────────────────────────

    sealed interface Segment permits Literal, PathRef {}
    record Literal(String text)   implements Segment {}
    record PathRef(String[] path) implements Segment {}

    private record FieldTemplate(String label, List<Segment> segments) {}
    private record DisplayTemplate(List<Segment> summarySegments, List<FieldTemplate> fields) {}

    // ── Public output types ───────────────────────────────────────────────────

    public record ResolvedField(String label, String value) {}
    public record ResolvedDisplay(String summary, List<ResolvedField> fields) {}
}
