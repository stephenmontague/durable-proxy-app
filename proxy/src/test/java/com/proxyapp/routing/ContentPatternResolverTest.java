package com.proxyapp.routing;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContentPatternResolverTest {

    private final ContentPatternResolver resolver = new ContentPatternResolver();

    private static MessageTypeResolver.InboundContext ctx(String content) {
        return new MessageTypeResolver.InboundContext(
                Transport.TCP, "dev-1", null, content.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void firstMatchingPatternWins() {
        Map<String, String> patterns = new LinkedHashMap<>();
        patterns.put("\"kind\"\\s*:\\s*\"status\"", "DEVICE_STATUS");
        patterns.put("\"kind\"\\s*:\\s*\"alarm\"", "DEVICE_ALARM");
        ResolverConfig config = new ResolverConfig(ContentPatternResolver.KIND, patterns);

        assertThat(resolver.resolve(config, ctx("{\"kind\":\"alarm\",\"id\":7}")))
                .contains(MessageType.of("DEVICE_ALARM"));
        assertThat(resolver.resolve(config, ctx("{\"kind\":\"status\",\"id\":1}")))
                .contains(MessageType.of("DEVICE_STATUS"));
    }

    @Test
    void noMatchReturnsEmpty() {
        ResolverConfig config = new ResolverConfig(ContentPatternResolver.KIND, Map.of("ALARM", "DEVICE_ALARM"));
        assertThat(resolver.resolve(config, ctx("a quiet heartbeat"))).isEmpty();
    }

    @Test
    void kindMatchesTheRegistryKey() {
        assertThat(resolver.kind()).isEqualTo("content-pattern");
    }
}
