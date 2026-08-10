package com.proxyapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Bootstrap-only configuration. Operational config (devices, routing, enabled) lives in
 * the control workflow; only what's needed to reach Temporal and describe the site
 * infrastructure stays local.
 */
@ConfigurationProperties(prefix = "proxy")
public record ProxyProperties(String taskQueue, String controlTaskQueue, String profile,
                              Cloud cloud, Ingress ingress, Seed seed) {

    public record Cloud(String baseUrl) {
    }

    /**
     * Site infrastructure, set once at install by IT.
     *
     * @param tcpPortPool inbound TCP ports available for routing bindings, as a range
     *                    ("6000-6010") or comma list ("6000,6001")
     */
    public record Ingress(String tcpPortPool, int ftpPort, String ftpRoot,
                          String ftpUser, String ftpPassword) {
    }

    /** Demo convenience: device config used to seed a brand-new control workflow. */
    public record Seed(String devicesResource) {
    }

    public List<Integer> tcpPortPool() {
        List<Integer> pool = new ArrayList<>();
        String spec = ingress == null ? null : ingress.tcpPortPool();
        if (spec == null || spec.isBlank()) {
            return pool;
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
                for (int p = from; p <= to; p++) {
                    pool.add(p);
                }
            } else {
                pool.add(port(range, range));
            }
        }
        return pool;
    }

    private static final String POOL_PROPERTY = "proxy.ingress.tcp-port-pool";

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
