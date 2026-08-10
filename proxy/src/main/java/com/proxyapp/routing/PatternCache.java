package com.proxyapp.routing;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Compiled-regex cache shared by the {@link MessageTypeResolver} implementations. Resolvers sit on
 * the inbound path and are called once per message, so compiling the same operator-authored regex
 * on every frame is pure waste.
 *
 * <p>Keyed by the regex source. Size is bounded in practice by the number of distinct patterns in
 * the live {@code ResolverConfig}s — operator-edited config, so tens at most; entries for patterns
 * removed by a later config edit simply go unused rather than being evicted.
 */
final class PatternCache {

    private static final Map<String, Pattern> COMPILED = new ConcurrentHashMap<>();

    private PatternCache() {
    }

    /** @throws java.util.regex.PatternSyntaxException if the regex does not compile */
    static Pattern get(String regex) {
        return COMPILED.computeIfAbsent(regex, Pattern::compile);
    }
}
