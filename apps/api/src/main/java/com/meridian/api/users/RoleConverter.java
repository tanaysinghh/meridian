package com.meridian.api.users;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists {@link Role} as the lowercase string the schema's CHECK constraint expects.
 *
 * <p>{@code @Enumerated(STRING)} would write {@code TEAM_LEAD}, which the constraint rejects, so the
 * mapping is explicit. Unknown values coming back from the database fall back to
 * {@link Role#DEVELOPER} — the same default the column carries — rather than failing the read.
 */
@Converter(autoApply = true)
public class RoleConverter implements AttributeConverter<Role, String> {

    @Override
    public String convertToDatabaseColumn(Role attribute) {
        return attribute == null ? null : attribute.wire();
    }

    @Override
    public Role convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        try {
            return Role.from(dbData);
        } catch (IllegalArgumentException ex) {
            return Role.DEVELOPER;
        }
    }
}
