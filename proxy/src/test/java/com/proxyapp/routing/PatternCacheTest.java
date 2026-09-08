package com.proxyapp.routing;

import com.proxyapp.routing.model.MessageType;
import com.proxyapp.routing.model.ResolverConfig;
import com.proxyapp.routing.model.Transport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Resolvers run once per inbound message, so the regexes they are configured with must be compiled
 * once rather than on every frame.
 */
class PatternCacheTest {

    @Test
    void sameRegexYieldsTheSameCompiledInstance() {
        Pattern first = PatternCache.get("^ALARM-\\d+$");
        Pattern second = PatternCache.get("^ALARM-\\d+$");

        // Identity, not equality: Pattern has no equals(), so this is what proves it was cached.
        assertThat(first).isSameAs(second);
    }

    @Test
    void differentRegexesGetDifferentInstances() {
        assertThat(PatternCache.get("^A$")).isNotSameAs(PatternCache.get("^B$"));
    }

    @Test
    void anInvalidRegexStillReportsItsSyntaxError() {
        assertThatThrownBy(() -> PatternCache.get("([unclosed"))
                .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void resolvingRepeatedlyReusesTheCompiledPattern() {
        String regex = "\"kind\"\\s*:\\s*\"telemetry\"";
        ResolverConfig config = new ResolverConfig(
                ContentPatternResolver.KIND, Map.of(regex, "DEVICE_TELEMETRY"));
        ContentPatternResolver resolver = new ContentPatternResolver();

        for (int i = 0; i < 100; i++) {
            assertThat(resolver.resolve(config, ctx("{\"kind\":\"telemetry\",\"seq\":" + i + "}")))
                    .contains(MessageType.of("DEVICE_TELEMETRY"));
        }

        assertThat(PatternCache.get(regex)).isSameAs(PatternCache.get(regex));
    }

    private static MessageTypeResolver.InboundContext ctx(String content) {
        return new MessageTypeResolver.InboundContext(
                Transport.TCP, "dev-1", null, content.getBytes(StandardCharsets.ISO_8859_1));
    }
}
