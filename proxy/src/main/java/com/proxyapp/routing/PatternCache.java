package com.proxyapp.routing;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Compiled-regex cache for the resolvers, which run once per inbound message. Never evicted;
 * bounded by the number of distinct patterns in the live config.
 */
final class PatternCache {

    private static final Map<String, Pattern> COMPILED = new ConcurrentHashMap<>();

    private PatternCache() {
    }

    static Pattern get(String regex) {
        return COMPILED.computeIfAbsent(regex, Pattern::compile);
    }
}
