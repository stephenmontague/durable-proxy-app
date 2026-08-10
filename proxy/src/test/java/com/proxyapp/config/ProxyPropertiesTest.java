package com.proxyapp.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Port-pool parsing. This is site infrastructure IT sets once at install, and it is read at boot
 * from a Spring bean — so a malformed value has to name the property and the offending token
 * rather than surface a bare NumberFormatException.
 */
class ProxyPropertiesTest {

    private static List<Integer> pool(String spec) {
        return props(spec).tcpPortPool();
    }

    private static ProxyProperties props(String spec) {
        return new ProxyProperties("q", "cq", "empty", null,
                new ProxyProperties.Ingress(spec, 2221, "./data", "u", "p"), null);
    }

    @Test
    void expandsARange() {
        assertThat(pool("6000-6003")).containsExactly(6000, 6001, 6002, 6003);
    }

    @Test
    void expandsACommaList() {
        assertThat(pool("6000,6002,6004")).containsExactly(6000, 6002, 6004);
    }

    @Test
    void expandsMixedRangesAndSinglesWithSurroundingWhitespace() {
        assertThat(pool(" 6000 - 6001 , 6005 , 7000-7001 "))
                .containsExactly(6000, 6001, 6005, 7000, 7001);
    }

    @Test
    void blankOrAbsentPoolIsEmptyNotAnError() {
        assertThat(pool("")).isEmpty();
        assertThat(pool("   ")).isEmpty();
        assertThat(new ProxyProperties("q", "cq", "empty", null, null, null).tcpPortPool()).isEmpty();
    }

    @Test
    void emptyTokensBetweenCommasAreSkipped() {
        assertThat(pool("6000,,6002")).containsExactly(6000, 6002);
    }

    @Test
    void nonNumericTokenNamesThePropertyAndTheToken() {
        assertThatThrownBy(() -> pool("6000,six-thousand-one"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proxy.ingress.tcp-port-pool")
                .hasMessageContaining("six-thousand-one");
    }

    @Test
    void backwardsRangeIsRejected() {
        assertThatThrownBy(() -> pool("6010-6000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runs backwards");
    }

    @Test
    void portOutsideTheLegalRangeIsRejected() {
        assertThatThrownBy(() -> pool("70000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside 1-65535");
        assertThatThrownBy(() -> pool("0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside 1-65535");
    }
}
