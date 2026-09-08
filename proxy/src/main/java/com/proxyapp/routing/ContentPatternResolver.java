package com.proxyapp.routing;

import com.proxyapp.routing.model.MessageType;
import com.proxyapp.routing.model.ResolverConfig;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * {@link MessageTypeResolver} that types an inbound frame by matching its content (decoded ISO-8859-1)
 * against regex → message type rules, for persistent-session sockets carrying several types. The match
 * is a substring {@code find}, so {@code "kind"\s*:\s*"status"} types a JSON frame. First match wins,
 * but {@link ResolverConfig#patterns()} arrives as a Jackson {@code HashMap} in hash order, not the
 * operator's — so write mutually exclusive patterns.
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
