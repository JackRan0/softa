package io.softa.framework.orm.utils;

import java.math.BigInteger;

import io.softa.framework.base.exception.IllegalArgumentException;

/**
 * Numeric helpers for optimistic-lock version fields.
 */
public final class VersionLockUtils {

    private VersionLockUtils() {
    }

    /**
     * Whether the version a client sent is the stored one. The two seldom share a type: the column
     * reads back as Integer or Long, a client sends whatever its entity or JSON carried, and a Long
     * reaches the browser as a string (JacksonConfig), so they are compared as numbers.
     *
     * @param provided the version the client read
     * @param stored the version in the database
     * @return true when both are the same number
     */
    public static boolean sameVersion(Object provided, Object stored) {
        return toBigInteger(provided).equals(toBigInteger(stored));
    }

    private static BigInteger toBigInteger(Object value) {
        return switch (value) {
            case Integer i -> BigInteger.valueOf(i);
            case Long l -> BigInteger.valueOf(l);
            case Short s -> BigInteger.valueOf(s);
            case Byte b -> BigInteger.valueOf(b);
            case BigInteger bi -> bi;
            case String s when s.strip().matches("-?\\d+") -> new BigInteger(s.strip());
            case null -> throw unsupported(null);
            default -> throw unsupported(value);
        };
    }

    private static IllegalArgumentException unsupported(Object value) {
        String type = value == null ? "null" : value.getClass().getName();
        return new IllegalArgumentException("Optimistic-lock version must be an integer number, but was {0} ({1}).",
                value, type);
    }
}
