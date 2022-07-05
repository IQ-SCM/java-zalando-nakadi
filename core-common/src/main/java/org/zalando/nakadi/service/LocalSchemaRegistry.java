package org.zalando.nakadi.service;

import org.apache.avro.Schema;
import org.apache.avro.SchemaNormalization;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.zalando.nakadi.domain.EventTypeSchemaBase;
import org.zalando.nakadi.exceptions.runtime.NoSuchEventTypeException;
import org.zalando.nakadi.exceptions.runtime.NoSuchSchemaException;
import org.zalando.nakadi.exceptions.runtime.UnsupportedSchemaTypeException;
import org.zalando.nakadi.util.AvroUtils;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

// temporarily storage for event type avro schemas untill schema repository supports them
@Service
public class LocalSchemaRegistry implements SchemaProviderService {

    public static final String ENVELOPE_KEY = "envelope";
    public static final String BATCH_PUBLISHING_KEY = "batch.publishing";
    public static final String BATCH_CONSUMPTION_KEY = "batch.consumption";

    private static final Comparator<String> SCHEMA_VERSION_COMPARATOR = Comparator.comparingInt(Integer::parseInt);
    // envelope must be first
    private static final Collection<String> NAKADI_API_SCHEMA_NAMES = Set.of(
            ENVELOPE_KEY,
            BATCH_PUBLISHING_KEY,
            BATCH_CONSUMPTION_KEY);

    private final Map<String, TreeMap<String, Schema>> schemaVersionsByName;

    public boolean isLocalSchema(final String name) {
        return NAKADI_API_SCHEMA_NAMES.contains(name);
    }

    @Autowired
    public LocalSchemaRegistry(
            @Value("${nakadi.avro.schema.root:classpath:avro-schema/}") final Resource avroSchemaRes)
            throws IOException {
        schemaVersionsByName = new HashMap<>();

        for (final String apiSchemaName : NAKADI_API_SCHEMA_NAMES) {
            final Map<String, Schema> embeddedSchemas = schemaVersionsByName.values().stream()
                    .flatMap((map) -> map.entrySet().stream())
                    .collect(Collectors.toMap(
                            (entry) -> entry.getValue().getFullName(),
                            Map.Entry::getValue));
            final TreeMap<String, Schema> versionToSchema =
                    loadAvroSchemaVersionsFromResource(avroSchemaRes, apiSchemaName, embeddedSchemas);
            if (versionToSchema.isEmpty()) {
                throw new NoSuchSchemaException("No avro schema found for: " + apiSchemaName);
            }
            schemaVersionsByName.put(apiSchemaName, versionToSchema);
        }
    }

    private TreeMap<String, Schema> loadAvroSchemaVersionsFromResource(
            final Resource avroSchemaRes,
            final String schemaName,
            @Nullable final Map<String, Schema> embeddedTypes) {

        final TreeMap<String, Schema> versionToSchema = new TreeMap<>(SCHEMA_VERSION_COMPARATOR);
        for (int i = 0; ; ++i) {
            try {
                final String relativeName = String.format("%s/%s.%d.avsc", schemaName, schemaName, i);
                final InputStream is = avroSchemaRes.createRelative(relativeName).getInputStream();
                versionToSchema.put(String.valueOf(i), AvroUtils.getParsedSchema(is, embeddedTypes));
            } catch (final IOException e) {
                break;
            }
        }
        return versionToSchema;
    }

    @Override
    public Schema getAvroSchema(final String schemaName, final String schemaVersion) {
        final Schema schema = getAvroSchemaVersions(schemaName).get(schemaVersion);
        if (schema == null) {
            throw new NoSuchSchemaException(
                    "Avro schema not found for: " + schemaName + ", version " + schemaVersion);
        }
        return schema;
    }

    @Override
    public String getAvroSchemaVersion(final String name, final Schema schema) throws NoSuchSchemaException {
        // TODO maybe implement cache for this
        final long schemaFingerprint = SchemaNormalization.parsingFingerprint64(schema);
        return getAvroSchemaVersions(name).entrySet().stream()
                .filter((entry) -> SchemaNormalization.parsingFingerprint64(entry.getValue()) == schemaFingerprint)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new NoSuchSchemaException(
                        String.format("schema is not found for %s", name)));
    }

    @Override
    public String getSchemaVersion(final String name, final String schema, final EventTypeSchemaBase.Type type)
            throws NoSuchSchemaException, UnsupportedSchemaTypeException {
        if (EventTypeSchemaBase.Type.AVRO_SCHEMA.equals(type)) {
            return getAvroSchemaVersion(name, new Schema.Parser().parse(schema));
        } else {
            throw new UnsupportedSchemaTypeException("Unsupported schema type: " + type);
        }
    }

    public TreeMap<String, Schema> getAvroSchemaVersions(final String schemaName) {
        final TreeMap<String, Schema> versionToSchema = schemaVersionsByName.get(schemaName);
        if (versionToSchema == null) {
            throw new NoSuchEventTypeException("Avro event type not found: " + schemaName);
        }
        return versionToSchema;
    }
}
