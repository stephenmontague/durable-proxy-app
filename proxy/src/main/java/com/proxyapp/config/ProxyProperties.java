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

    /**
     * Upper bound on how many ports one pool may expand to. A site opens a handful of inbound
     * ports, one per TCP channel, so anything near this is a typo — most likely a range whose
     * end was mistyped ({@code 6000-65535} instead of {@code 6000-6535}). Worth catching because
     * the expanded list is seeded into control-workflow state, where a runaway range would bloat
     * every history event and every update response.
     */
    private static final int MAX_POOL_SIZE = 1024;

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

        public Ingress {
            // Parse eagerly and discard the result: this runs during property binding, so a
            // malformed pool fails startup with the offending token named. Every sibling field
            // already behaves this way (a non-numeric ftp-port fails binding) -- without this,
            // tcpPortPool would be the one field whose errors surface later, from inside the
            // bootstrap retry loop, leaving a proxy that looks healthy but binds nothing.
            expandPool(tcpPortPool);
        }
    }

    /**
     * Optional bootstrap device config for a <i>brand-new</i> control workflow. Ignored once one
     * exists, since Temporal is then the source of truth — so this cannot be used to push config
     * to a running install. Leave blank to start empty and configure through the control API.
     */
    public record Seed(String devicesResource) {
    }

    /**
     * The concrete inbound TCP ports this site made available. Validated during property binding
     * (see {@link Ingress}), so by the time anything calls this the spec is known to parse.
     */
    public List<Integer> tcpPortPool() {
        return expandPool(ingress == null ? null : ingress.tcpPortPool());
    }

    /**
     * Expand a pool spec — a comma list of single ports and {@code from-to} ranges — into concrete
     * port numbers. Duplicates are collapsed and declaration order is kept. A blank or absent spec
     * is an empty pool, not an error.
     *
     * @throws IllegalArgumentException if a token is not a number, a range runs backwards, a port
     *                                  falls outside 1-65535, or the pool exceeds
     *                                  {@link #MAX_POOL_SIZE} ports
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
