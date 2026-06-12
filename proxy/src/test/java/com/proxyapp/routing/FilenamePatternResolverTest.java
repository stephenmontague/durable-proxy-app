package com.proxyapp.routing;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FilenamePatternResolverTest {

    private final FilenamePatternResolver resolver = new FilenamePatternResolver();

    private MessageTypeResolver.InboundContext context(String filename) {
        return new MessageTypeResolver.InboundContext(Transport.FTP, "mixed", filename, new byte[0]);
    }

    @Test
    void firstMatchingPatternWins() {
        Map<String, String> patterns = new LinkedHashMap<>();
        patterns.put("PC-.*\\.json", "COMMAND_RESULT");
        patterns.put("PA-.*\\.json", "CONFIG_ACK");
        ResolverConfig config = new ResolverConfig(FilenamePatternResolver.KIND, patterns);

        assertThat(resolver.resolve(config, context("PC-1001.json")))
                .contains(MessageType.of("COMMAND_RESULT"));
        assertThat(resolver.resolve(config, context("PA-7.json")))
                .contains(MessageType.of("CONFIG_ACK"));
        assertThat(resolver.resolve(config, context("other.txt"))).isEmpty();
    }

    @Test
    void noFilenameMeansNoResolution() {
        ResolverConfig config = new ResolverConfig(FilenamePatternResolver.KIND,
                Map.of(".*", "COMMAND_RESULT"));
        assertThat(resolver.resolve(config, context(null))).isEmpty();
    }
}
