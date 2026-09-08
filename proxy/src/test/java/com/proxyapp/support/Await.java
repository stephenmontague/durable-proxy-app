package com.proxyapp.support;

import org.awaitility.Awaitility;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Polling waits for the socket-driven tests, which settle on background threads. For waiting on
 * something to <i>become</i> true; proving something never happens still needs a sleep.
 */
public final class Await {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(25);

    private Await() {
    }

    public static void until(BooleanSupplier condition, long timeoutMs) {
        Awaitility.await()
                .atMost(Duration.ofMillis(timeoutMs))
                .pollInterval(POLL_INTERVAL)
                .until(condition::getAsBoolean);
    }

    /** {@code description} names what was awaited in the timeout failure. */
    public static void until(String description, BooleanSupplier condition, long timeoutMs) {
        Awaitility.await(description)
                .atMost(Duration.ofMillis(timeoutMs))
                .pollInterval(POLL_INTERVAL)
                .until(condition::getAsBoolean);
    }
}
