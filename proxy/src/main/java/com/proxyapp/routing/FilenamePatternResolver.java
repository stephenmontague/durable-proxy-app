package com.proxyapp.routing;

import com.proxyapp.routing.model.MessageType;
import com.proxyapp.routing.model.ResolverConfig;

import java.util.Map;
import java.util.Optional;

/**
 * Reference {@link MessageTypeResolver}: maps inbound filenames to message types via regex, for FTP
 * devices that drop multiple types into one folder. First match wins, but
 * {@link ResolverConfig#patterns()} arrives as a Jackson {@code HashMap} in hash order, not the
 * operator's — so write mutually exclusive patterns.
 */
public class FilenamePatternResolver implements MessageTypeResolver {

    public static final String KIND = "filename-pattern";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public Optional<MessageType> resolve(ResolverConfig config, InboundContext context) {
        if (context.filename() == null || config.patterns() == null) {
            return Optional.empty();
        }
        for (Map.Entry<String, String> e : config.patterns().entrySet()) {
            if (PatternCache.get(e.getKey()).matcher(context.filename()).matches()) {
                return Optional.of(MessageType.of(e.getValue()));
            }
        }
        return Optional.empty();
    }
}
