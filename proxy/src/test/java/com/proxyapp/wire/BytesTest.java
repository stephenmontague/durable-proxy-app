package com.proxyapp.wire;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * These were one method named {@code contains} with two different implementations. The distinction
 * is load-bearing — a full scan per appended byte would make the reply read quadratic — so pin it.
 */
class BytesTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void containsFindsANeedleAnywhere() {
        byte[] haystack = b("noise ACK trailing");
        assertThat(Bytes.contains(haystack, haystack.length, b("ACK"))).isTrue();
    }

    @Test
    void endsWithOnlyMatchesTheTail() {
        byte[] haystack = b("noise ACK trailing");
        // The very difference that matters: present, but not at the end.
        assertThat(Bytes.endsWith(haystack, haystack.length, b("ACK"))).isFalse();
        assertThat(Bytes.endsWith(haystack, haystack.length, b("trailing"))).isTrue();
    }

    @Test
    void bothRespectTheSizeBoundRatherThanTheArrayLength() {
        byte[] buffer = new byte[64];
        byte[] filled = b("ACK");
        System.arraycopy(filled, 0, buffer, 0, filled.length);

        // Only the first 3 bytes are real; the trailing zeros must not count as content.
        assertThat(Bytes.contains(buffer, 3, b("ACK"))).isTrue();
        assertThat(Bytes.endsWith(buffer, 3, b("ACK"))).isTrue();
        assertThat(Bytes.endsWith(buffer, buffer.length, b("ACK"))).isFalse();
    }

    @Test
    void aNeedleLongerThanTheContentNeverMatches() {
        byte[] haystack = b("AC");
        assertThat(Bytes.contains(haystack, haystack.length, b("ACK"))).isFalse();
        assertThat(Bytes.endsWith(haystack, haystack.length, b("ACK"))).isFalse();
    }

    @Test
    void anEmptyNeedleNeverMatches() {
        byte[] haystack = b("ACK");
        assertThat(Bytes.contains(haystack, haystack.length, new byte[0])).isFalse();
        assertThat(Bytes.endsWith(haystack, haystack.length, new byte[0])).isFalse();
    }

    @Test
    void frameBufferGrowsPastItsInitialCapacityAndStillMatches() {
        FrameBuffer buf = new FrameBuffer();
        for (int i = 0; i < 5_000; i++) {
            buf.append((byte) 'x');
        }
        for (byte value : b("<END>")) {
            buf.append(value);
        }

        assertThat(buf.size()).isEqualTo(5_005);
        assertThat(buf.endsWith(b("<END>"))).isTrue();
        assertThat(buf.toArray(buf.size() - 5)).hasSize(5_000);
    }

    @Test
    void frameBufferCompactKeepLastRetainsTheDelimiterStraddleWindow() {
        FrameBuffer buf = new FrameBuffer();
        for (byte value : b("garbage<ST")) {
            buf.append(value);
        }

        buf.compactKeepLast(2); // keep "ST" so a following 'X' can complete "<STX>"-style matching
        assertThat(buf.size()).isEqualTo(2);
        assertThat(buf.endsWith(b("ST"))).isTrue();

        buf.compactKeepLast(0);
        assertThat(buf.size()).isZero();
    }
}
