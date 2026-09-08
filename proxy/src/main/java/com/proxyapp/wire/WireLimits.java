package com.proxyapp.wire;

/** Ceilings shared by the delimiter-framed readers, which both parse untrusted peers. */
public final class WireLimits {

    /** A peer that exceeds this has lost framing, so the link is dropped. */
    public static final int MAX_FRAME_BYTES = 10 * 1024 * 1024;

    /** While seeking a start delimiter, don't hoard unbounded noise. */
    public static final int NOISE_COMPACT_THRESHOLD = 8 * 1024;

    private WireLimits() {
    }
}
