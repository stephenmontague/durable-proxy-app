package com.proxyapp.routing.model;

import java.util.Map;

/**
 * Opt-in escape hatch for opaque-multiplexed devices: binds a MessageTypeResolver to a single
 * channel carrying several message types. {@code kind} is the implementation key (e.g.
 * {@code "filename-pattern"}); {@code patterns} is its rules, for that kind regex -> message type.
 */
public record ResolverConfig(String kind, Map<String, String> patterns) {
}
