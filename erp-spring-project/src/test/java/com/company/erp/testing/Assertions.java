package com.company.erp.testing;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Minimal assertion helpers tailored for the demo. Each method throws an
 * {@link AssertionError} with a descriptive message when the expectation is not
 * met.
 */
public final class Assertions {

    private Assertions() {
    }

    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    public static void assertBigDecimalEquals(String expected, Object actual, String message) {
        BigDecimal left = new BigDecimal(expected);
        BigDecimal right = actual instanceof BigDecimal ? (BigDecimal) actual : new BigDecimal(actual.toString());
        if (left.compareTo(right) != 0) {
            throw new AssertionError(message + " expected=" + left + " actual=" + right);
        }
    }

    public static void assertNotNull(Object value, String message) {
        if (value == null) {
            throw new AssertionError(message);
        }
    }
}
