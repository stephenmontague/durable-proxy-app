package com.proxyapp.support;

import org.awaitility.Awaitility;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Polling waits for the socket-driven tests. Sessions and listeners settle on background threads,
 * so assertions have to wait for a condition rather than assume it already holds.
 *
 * <p>Backed by Awaitility, which reports the condition's final state on timeout — a hand-rolled
 * poll loop can only say "it was still false". Use this for waiting on something to <i>become</i>
 * true; proving something never happens still needs a plain sleep plus an assertion.
 */
public final class Await {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(25);

    private Await() {
    }

    /** Wait up to {@code timeoutMs} for {@code condition}, failing the test if it never holds. */
    public static void until(BooleanSupplier condition, long timeoutMs) {
        Awaitility.await()
                .atMost(Duration.ofMillis(timeoutMs))
                .pollInterval(POLL_INTERVAL)
                .until(condition::getAsBoolean);
    }

    /**
     * Wait up to {@code timeoutMs} for {@code condition}, describing what was being waited for in
     * the failure message.
     */
    public static void until(String description, BooleanSupplier condition, long timeoutMs) {
        Awaitility.await(description)
                .atMost(Duration.ofMillis(timeoutMs))
                .pollInterval(POLL_INTERVAL)
                .until(condition::getAsBoolean);
    }
}
