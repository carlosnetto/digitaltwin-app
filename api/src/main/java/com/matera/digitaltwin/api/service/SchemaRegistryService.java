package com.matera.digitaltwin.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pre-compiles and caches every JSON Schema (Draft 2020-12) stored in
 * digitaltwinapp.transaction_schemas.
 *
 * Schemas are loaded once at startup via @PostConstruct and held as an
 * immutable Map snapshot in a volatile field. Calling refresh() replaces
 * the snapshot atomically — in-flight validate() calls see either the old
 * or the new map, never a partially-updated one. Compilation cost (parsing,
 * $ref resolution, regex compilation) is paid once; validate() is pure
 * in-memory tree traversal on every call.
 *
 * Missing schema row = pass-through (Optional.empty()). Transaction codes
 * without a registered schema are accepted without validation by design.
 */
@Service
public class SchemaRegistryService {

    private static final Logger log = LoggerFactory.getLogger(SchemaRegistryService.class);

    private final JdbcTemplate      jdbc;
    private final ObjectMapper       objectMapper;
    private final JsonSchemaFactory  factory;

    // Immutable snapshot — replaced atomically by refresh()
    private volatile Map<Integer, JsonSchema> schemas = Map.of();

    public SchemaRegistryService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc         = jdbc;
        this.objectMapper = objectMapper;
        this.factory      = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    }

    @PostConstruct
    public void load() {
        log.info("SchemaRegistry: loading pre-compiled schemas from transaction_schemas...");
        try {
            Map<Integer, JsonSchema> compiled = new HashMap<>();
            jdbc.queryForList("""
                    SELECT trans_code, json_schema::text AS json_schema
                    FROM digitaltwinapp.transaction_schemas
                    """)
                .forEach(row -> {
                    int    code = ((Number) row.get("trans_code")).intValue();
                    String json = (String)  row.get("json_schema");
                    compiled.put(code, factory.getSchema(json));
                });
            schemas = Map.copyOf(compiled);   // immutable snapshot — volatile write
            log.info("SchemaRegistry: {} schemas loaded and compiled", compiled.size());
        } catch (Exception e) {
            log.error("SchemaRegistry: failed to load schemas — validation disabled until refresh(): {}",
                      e.getMessage(), e);
        }
    }

    /**
     * Validates a metadata map against the registered schema for transCode.
     *
     * @return Optional.empty()    — no schema registered; payload accepted as-is.
     *         Optional.of(empty) — schema found; payload is VALID.
     *         Optional.of(errors) — schema found; payload is INVALID; errors describe violations.
     */
    public Optional<Set<ValidationMessage>> validate(int transCode, Map<String, Object> metadata) {
        JsonSchema schema = schemas.get(transCode);   // volatile read
        if (schema == null) return Optional.empty();

        JsonNode node = objectMapper.valueToTree(metadata);   // no serialization round-trip
        return Optional.of(schema.validate(node));
    }

    /**
     * Reloads all schemas from the DB. The swap is atomic — safe to call at any time
     * without locking out concurrent validate() calls.
     */
    public void refresh() {
        log.info("SchemaRegistry: manual refresh triggered");
        load();
    }

    public boolean hasSchema(int transCode) {
        return schemas.containsKey(transCode);
    }
}
