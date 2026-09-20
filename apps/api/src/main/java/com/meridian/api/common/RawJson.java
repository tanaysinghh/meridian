package com.meridian.api.common;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * A {@code jsonb} column on its way back out to the client, untouched.
 *
 * <p>The old service read {@code jsonb} with node-postgres (which parsed it into a plain JS value)
 * and handed it straight to {@code res.json()}. Round-tripping those documents through Java types
 * would risk changing them — a rule predicate is an open-ended shape, and the ML service's
 * {@code contributions} array is whatever the model emitted. Wrapping the raw column text and
 * writing it back verbatim keeps the response byte-for-byte equivalent and means a new field added
 * on the ML side flows through without a DTO change here.
 *
 * <p>{@code null} and empty are normalised to a JSON {@code null} so the key is still present, which
 * is what the previous {@code LEFT JOIN} behaviour produced.
 */
@JsonSerialize(using = RawJson.Serializer.class)
public record RawJson(String json) {

    public static RawJson of(String json) {
        return new RawJson(json);
    }

    /** Convenience for the common "column was null" case. */
    public static RawJson ofNullable(String json) {
        return new RawJson(json == null || json.isBlank() ? null : json);
    }

    static class Serializer extends ValueSerializer<RawJson> {
        @Override
        public void serialize(RawJson value, JsonGenerator gen, SerializationContext ctxt) {
            if (value == null || value.json() == null || value.json().isBlank()) {
                gen.writeNull();
            } else {
                gen.writeRawValue(value.json());
            }
        }
    }
}
