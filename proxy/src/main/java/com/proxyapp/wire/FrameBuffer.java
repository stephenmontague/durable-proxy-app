package com.proxyapp.wire;

import java.util.Arrays;

/**
 * Growable byte buffer for delimiter-framed reads, with cheap {@code endsWith} — avoids the O(n²)
 * toByteArray scans a {@code ByteArrayOutputStream} would cost when checking for a frame terminator
 * after every appended byte.
 *
 * <p>Not thread-safe: each instance belongs to one connection's read loop.
 */
public final class FrameBuffer {

    private byte[] data = new byte[1024];
    private int size;

    public void append(byte b) {
        if (size == data.length) {
            data = Arrays.copyOf(data, data.length * 2);
        }
        data[size++] = b;
    }

    public boolean endsWith(byte[] suffix) {
        return Bytes.endsWith(data, size, suffix);
    }

    public byte[] toArray(int length) {
        return Arrays.copyOf(data, length);
    }

    /** Keep only the last {@code n} bytes (delimiter-straddle window for noise mode). */
    public void compactKeepLast(int n) {
        if (n <= 0) {
            size = 0;
            return;
        }
        if (size > n) {
            System.arraycopy(data, size - n, data, 0, n);
            size = n;
        }
    }

    public void reset() {
        size = 0;
    }

    public int size() {
        return size;
    }
}
