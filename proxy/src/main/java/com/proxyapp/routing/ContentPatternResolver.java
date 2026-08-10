package com.proxyapp.routing;

import com.proxyapp.routing.model.MessageType;
import com.proxyapp.routing.model.ResolverConfig;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * {@link MessageTypeResolver} that types an inbound frame by matching its <b>content</b> (decoded
 * ISO-8859-1) against regexes — for persistent-session sockets that carry several message types.
 * The match is a substring search ({@code find}), so a rule like {@code "kind"\s*:\s*"status"}
 * types a JSON frame. Patterns: regex → message type.
 *
 * <p><b>Ordering caveat:</b> the first matching pattern wins, but iteration order is whatever the
 * incoming {@link ResolverConfig#patterns()} map provides — for a Jackson-deserialized
 * {@code HashMap} that is hash order, not the order the operator wrote. Write mutually exclusive
 * patterns. Making order authoritative would mean imposing it at the deserialization boundary.
 */
public class ContentPatternResolver implements MessageTypeResolver {

    public static final String KIND = "content-pattern";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public Optional<MessageType> resolve(ResolverConfig config, InboundContext context) {
        if (context.raw() == null || config.patterns() == null) {
            return Optional.empty();
        }
        String content = new String(context.raw(), StandardCharsets.ISO_8859_1);
        for (Map.Entry<String, String> entry : config.patterns().entrySet()) {
            if (PatternCache.get(entry.getKey()).matcher(content).find()) {
                return Optional.of(MessageType.of(entry.getValue()));
            }
        }
        return Optional.empty();
    }
}
