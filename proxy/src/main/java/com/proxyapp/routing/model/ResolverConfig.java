package com.proxyapp.routing.model;

import java.util.Map;

/**
 * Opt-in escape hatch for opaque-multiplexed devices: binds a {@link MessageTypeResolver}
 * to a single channel that carries multiple message types. Off (null) by default.
 *
 * @param kind     resolver implementation key, e.g. {@code "filename-pattern"}
 * @param patterns implementation-specific rules; for filename-pattern: regex -> message type
 */
public record ResolverConfig(String kind, Map<String, String> patterns) {
}
