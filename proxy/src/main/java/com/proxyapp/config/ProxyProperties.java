package com.proxyapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Bootstrap-only configuration. Operational config (devices, routing, enabled) lives in
 * the control workflow; only what's needed to reach Temporal and describe the site
 * infrastructure stays local.
 */
@ConfigurationProperties(prefix = "proxy")
public record ProxyProperties(String taskQueue, String controlTaskQueue, String profile,
                              Cloud cloud, Ingress ingress, Seed seed) {

    /** Property path used in parse errors, so an operator can find what to edit. */
    private static final String POOL_PROPERTY = "proxy.ingress.tcp-port-pool";

    /** Catches a mistyped range end (6000-65535 for 6000-6535); a generous allocation still fits. */
    private static final int MAX_POOL_SIZE = 8192;

    public record Cloud(String baseUrl) {
    }

    /**
     * Site infrastructure, set once at install by IT. {@code tcpPortPool} is a range ("6000-6010") or
     * comma list ("6000,6001") of inbound ports available for routing bindings.
     */
    public record Ingress(String tcpPortPool, int ftpPort, String ftpRoot,
                          String ftpUser, String ftpPassword) {

        public Ingress {
            expandPool(tcpPortPool); // parse now, so a bad pool fails binding like any other field
        }
    }

    /**
     * Optional bootstrap device config for a brand-new control workflow. Ignored once one exists, since
     * Temporal is then the source of truth, so this cannot push config to a running install.
     */
    public record Seed(String devicesResource) {
    }

    /** The concrete inbound TCP ports this site made available. Validated during property binding, so
     *  by the time anything calls this the spec is known to parse. */
    public List<Integer> tcpPortPool() {
        return expandPool(ingress == null ? null : ingress.tcpPortPool());
    }

    /**
     * Expand a comma list of ports and {@code from-to} ranges, collapsing duplicates. Blank is an
     * empty pool, not an error; anything malformed throws {@link IllegalArgumentException}.
     */
    private static List<Integer> expandPool(String spec) {
        Set<Integer> pool = new LinkedHashSet<>();
        if (spec == null || spec.isBlank()) {
            return List.of();
        }
        for (String part : spec.split(",")) {
            String range = part.trim();
            if (range.isEmpty()) {
                continue;
            }
            int dash = range.indexOf('-');
            if (dash > 0) {
                int from = port(range.substring(0, dash), range);
                int to = port(range.substring(dash + 1), range);
                if (from > to) {
                    throw new IllegalArgumentException(POOL_PROPERTY + ": range '" + range
                            + "' runs backwards (" + from + " > " + to + ")");
                }
                if (to - from + 1 > MAX_POOL_SIZE) {
                    throw new IllegalArgumentException(POOL_PROPERTY + ": range '" + range
                            + "' covers " + (to - from + 1) + " ports, more than the "
                            + MAX_POOL_SIZE + " allowed — check for a mistyped range end");
                }
                for (int p = from; p <= to; p++) {
                    pool.add(p);
                }
            } else {
                pool.add(port(range, range));
            }
            if (pool.size() > MAX_POOL_SIZE) {
                throw new IllegalArgumentException(POOL_PROPERTY + ": pool exceeds "
                        + MAX_POOL_SIZE + " ports");
            }
        }
        return List.copyOf(pool);
    }

    private static int port(String token, String context) {
        int value;
        try {
            value = Integer.parseInt(token.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(POOL_PROPERTY + ": '" + context
                    + "' is not a port or port range");
        }
        if (value < 1 || value > 65535) {
            throw new IllegalArgumentException(POOL_PROPERTY + ": port " + value + " in '" + context
                    + "' is outside 1-65535");
        }
        return value;
    }
}
