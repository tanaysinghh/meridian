package com.meridian.api.common;

import jakarta.persistence.Tuple;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Column readers for native-query {@link Tuple} rows.
 *
 * <p>Several endpoints — the analytics aggregates, the PR list, the reviewer load query — depend on
 * Postgres features that JPQL has no way to express: {@code LATERAL} joins to pick each PR's latest
 * score, {@code date_trunc}, {@code PERCENTILE_CONT}, {@code unnest} over a {@code TEXT[]}. Those
 * stay as native SQL, carried over from the previous implementation, and this class is what turns
 * their result columns into Java values.
 *
 * <p>Each accessor tolerates the range of types the JDBC driver may hand back for a given column
 * ({@code TIMESTAMPTZ} arrives as a {@code Timestamp} or an {@code OffsetDateTime} depending on
 * path, {@code jsonb} as a {@code PGobject} or a {@code String}), and returns {@code null} for a
 * missing or null column rather than throwing — outer joins legitimately produce nulls here.
 */
public final class Tuples {

    private Tuples() {
    }

    private static Object raw(Tuple tuple, String column) {
        try {
            return tuple.get(column);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public static UUID uuid(Tuple tuple, String column) {
        Object value = raw(tuple, column);
        if (value == null) {
            return null;
        }
        if (value instanceof UUID u) {
            return u;
        }
        return UUID.fromString(value.toString());
    }

    public static String string(Tuple tuple, String column) {
        Object value = raw(tuple, column);
        return value == null ? null : value.toString();
    }

    public static Integer integer(Tuple tuple, String column) {
        Object value = raw(tuple, column);
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.valueOf(value.toString());
    }

    /** Integer column that the old SQL cast to {@code ::int} and defaulted to 0. */
    public static int intOrZero(Tuple tuple, String column) {
        Integer value = integer(tuple, column);
        return value == null ? 0 : value;
    }

    public static Long longValue(Tuple tuple, String column) {
        Object value = raw(tuple, column);
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.valueOf(value.toString());
    }

    /**
     * A {@code ::float} column. Kept as a boxed {@link Double} because the aggregates legitimately
     * return null when a window contains no rows, and the frontend distinguishes null from zero.
     */
    public static Double doubleValue(Tuple tuple, String column) {
        Object value = raw(tuple, column);
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return Double.valueOf(value.toString());
    }

    public static BigDecimal decimal(Tuple tuple, String column) {
        Object value = raw(tuple, column);
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal d) {
            return d;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return new BigDecimal(value.toString());
    }

    public static Boolean bool(Tuple tuple, String column) {
        Object value = raw(tuple, column);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.valueOf(value.toString());
    }

    public static Instant instant(Tuple tuple, String column) {
        Object value = raw(tuple, column);
        if (value == null) {
            return null;
        }
        if (value instanceof Instant i) {
            return i;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof LocalDateTime ldt) {
            // date_trunc() results arrive without a zone; they were computed in UTC.
            return ldt.toInstant(ZoneOffset.UTC);
        }
        if (value instanceof java.util.Date d) {
            return d.toInstant();
        }
        throw new IllegalStateException("unsupported timestamp type for column " + column + ": " + value.getClass());
    }

    /** A Postgres {@code TEXT[]} column. Never null — an absent array reads as an empty list. */
    public static List<String> stringList(Tuple tuple, String column) {
        Object value = raw(tuple, column);
        if (value == null) {
            return List.of();
        }
        if (value instanceof java.sql.Array array) {
            try {
                Object inner = array.getArray();
                if (inner instanceof Object[] items) {
                    List<String> out = new ArrayList<>(items.length);
                    for (Object item : items) {
                        out.add(item == null ? null : item.toString());
                    }
                    return out;
                }
            } catch (java.sql.SQLException ex) {
                throw new IllegalStateException("could not read array column " + column, ex);
            }
        }
        if (value instanceof Object[] items) {
            return Arrays.stream(items).map(i -> i == null ? null : i.toString()).toList();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(i -> i == null ? null : i.toString()).toList();
        }
        return List.of(value.toString());
    }

    /**
     * A {@code jsonb} column, kept as text so it can be written back to the client unchanged.
     *
     * @see RawJson
     */
    public static RawJson json(Tuple tuple, String column) {
        Object value = raw(tuple, column);
        if (value == null) {
            return RawJson.of(null);
        }
        return RawJson.ofNullable(value.toString());
    }
}
