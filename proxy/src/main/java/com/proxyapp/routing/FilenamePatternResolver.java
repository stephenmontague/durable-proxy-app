package com.proxyapp.routing;
import com.proxyapp.routing.model.MessageType;
import com.proxyapp.routing.model.ResolverConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reference {@link MessageTypeResolver}: maps inbound filenames to message types via regex,
 * for FTP devices that drop multiple types into one folder. First matching pattern wins.
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
        // LinkedHashMap preserves declaration order so "first match wins" is well-defined.
        Map<String, String> patterns = new LinkedHashMap<>(config.patterns());
        for (Map.Entry<String, String> e : patterns.entrySet()) {
            if (Pattern.compile(e.getKey()).matcher(context.filename()).matches()) {
                return Optional.of(MessageType.of(e.getValue()));
            }
        }
        return Optional.empty();
    }
}
