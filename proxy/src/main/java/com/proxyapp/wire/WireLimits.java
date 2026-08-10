package com.proxyapp.wire;

/**
 * Safety bounds shared by every delimiter-framed TCP reader — the ingress listener and the
 * persistent device session. Both read from untrusted peers, so both need the same ceilings; they
 * were previously declared separately in each and could drift apart silently.
 */
public final class WireLimits {

    /** Hard ceiling on one frame. A peer that exceeds it has lost framing, so the link is dropped. */
    public static final int MAX_FRAME_BYTES = 10 * 1024 * 1024;

    /** While seeking a start delimiter, don't hoard unbounded noise. */
    public static final int NOISE_COMPACT_THRESHOLD = 8 * 1024;

    private WireLimits() {
    }
}
