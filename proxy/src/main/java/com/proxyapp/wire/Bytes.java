package com.proxyapp.wire;

/**
 * Byte-matching helpers for the TCP wire paths. Both take an explicit {@code size} so callers can
 * scan a partially-filled buffer without copying it.
 *
 * <p>The two methods answer genuinely different questions, and picking the wrong one is a
 * performance trap rather than a correctness one — see {@link #endsWith}.
 */
public final class Bytes {

    private Bytes() {
    }

    /**
     * Whether {@code needle} appears anywhere in the first {@code size} bytes of {@code haystack}.
     * Use when the buffer is scanned once after it is complete.
     */
    public static boolean contains(byte[] haystack, int size, byte[] needle) {
        if (needle.length == 0 || needle.length > size) {
            return false;
        }
        for (int from = 0; from <= size - needle.length; from++) {
            if (matchesAt(haystack, from, needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the first {@code size} bytes of {@code haystack} end with {@code suffix}.
     *
     * <p>This is the right check when re-testing after each single-byte append: only the tail can
     * newly match, so a full {@link #contains} scan per byte would make an otherwise linear read
     * quadratic. It is <b>not</b> a substring test — a caller that needs "appears anywhere" on a
     * completed buffer wants {@link #contains}.
     */
    public static boolean endsWith(byte[] haystack, int size, byte[] suffix) {
        if (suffix.length == 0 || suffix.length > size) {
            return false;
        }
        return matchesAt(haystack, size - suffix.length, suffix);
    }

    private static boolean matchesAt(byte[] haystack, int from, byte[] needle) {
        for (int i = 0; i < needle.length; i++) {
            if (haystack[from + i] != needle[i]) {
                return false;
            }
        }
        return true;
    }
}
