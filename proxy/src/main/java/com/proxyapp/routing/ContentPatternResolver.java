package com.proxyapp.routing;
import com.proxyapp.routing.model.MessageType;
import com.proxyapp.routing.model.ResolverConfig;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@link MessageTypeResolver} that types an inbound frame by matching its <b>content</b> (decoded
 * ISO-8859-1) against regexes — for persistent-session sockets that carry several message types.
 * First matching pattern wins; the match is a substring search ({@code find}), so a rule like
 * {@code "kind"\s*:\s*"status"} types a JSON frame. Patterns: regex → message type.
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
        // LinkedHashMap preserves declaration order so "first match wins" is well-defined.
        Map<String, String> patterns = new LinkedHashMap<>(config.patterns());
        for (Map.Entry<String, String> entry : patterns.entrySet()) {
            if (Pattern.compile(entry.getKey()).matcher(content).find()) {
                return Optional.of(MessageType.of(entry.getValue()));
            }
        }
        return Optional.empty();
    }
}
