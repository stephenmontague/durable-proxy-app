package com.proxyapp.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The vectors here are the shared spec — management-ui/src/lib/wire-string.test.ts
 * carries the SAME inputs and expected outputs/messages. Change them in both places.
 */
class WireStringTest {

    @Test
    void plainPrintableTextPassesThrough() {
        assertThat(WireString.decode("ACK ok")).isEqualTo(new byte[]{'A', 'C', 'K', ' ', 'o', 'k'});
    }

    @Test
    void simpleEscapes() {
        assertThat(WireString.decode("\\r\\n\\t\\\\")).isEqualTo(new byte[]{0x0D, 0x0A, 0x09, '\\'});
    }

    @Test
    void hexEscapes() {
        assertThat(WireString.decode("\\x0b")).isEqualTo(new byte[]{0x0B});
        assertThat(WireString.decode("\\x1C\\x0D")).isEqualTo(new byte[]{0x1C, 0x0D});
        assertThat(WireString.decode("\\xFF")).isEqualTo(new byte[]{(byte) 0xFF});
    }

    @Test
    void namedTokens() {
        assertThat(WireString.decode("<VT>")).isEqualTo(new byte[]{0x0B});
        assertThat(WireString.decode("<FS><CR>")).isEqualTo(new byte[]{0x1C, 0x0D});
        assertThat(WireString.decode("<STX>data<ETX>")).isEqualTo(
                new byte[]{0x02, 'd', 'a', 't', 'a', 0x03});
        assertThat(WireString.decode("<ACK>")).isEqualTo(new byte[]{0x06});
        assertThat(WireString.decode("<NAK>")).isEqualTo(new byte[]{0x15});
        assertThat(WireString.decode("<NUL>")).isEqualTo(new byte[]{0x00});
        assertThat(WireString.decode("<DEL>")).isEqualTo(new byte[]{0x7F});
    }

    @Test
    void mllpAckTemplateDecodes() {
        assertThat(WireString.decodeToString("<VT>ACK {activityId}<FS><CR>"))
                .isEqualTo("ACK {activityId}\r");
    }

    @Test
    void escapedAngleBracketIsLiteral() {
        assertThat(WireString.decode("\\<tag>")).isEqualTo(new byte[]{'<', 't', 'a', 'g', '>'});
    }

    @Test
    void bareCloseBracketIsLiteral() {
        assertThat(WireString.decode("a>b")).isEqualTo(new byte[]{'a', '>', 'b'});
    }

    @Test
    void danglingBackslash() {
        assertThatThrownBy(() -> WireString.decode("abc\\"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dangling escape at end of input");
    }

    @Test
    void unknownEscape() {
        assertThatThrownBy(() -> WireString.decode("a\\qb"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown escape '\\q' at position 1");
    }

    @Test
    void hexEscapeNeedsTwoDigits() {
        assertThatThrownBy(() -> WireString.decode("\\x0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("\\x escape requires two hex digits at position 0");
        assertThatThrownBy(() -> WireString.decode("ab\\xZZ"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("\\x escape requires two hex digits at position 2");
    }

    @Test
    void unterminatedToken() {
        assertThatThrownBy(() -> WireString.decode("<STX"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unterminated token starting at position 0");
        assertThatThrownBy(() -> WireString.decode("a<longername>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unterminated token starting at position 1");
    }

    @Test
    void unknownToken() {
        assertThatThrownBy(() -> WireString.decode("<XYZ>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown token '<XYZ>' at position 0");
    }

    @Test
    void rawControlCharacterRejected() {
        assertThatThrownBy(() -> WireString.decode("abcd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unsupported character at position 2 (use \\xNN or a <TOKEN>)");
        assertThatThrownBy(() -> WireString.decode("é"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unsupported character at position 0 (use \\xNN or a <TOKEN>)");
    }

    @Test
    void validateReturnsErrorOrNull() {
        assertThat(WireString.validate("<VT>ok")).isNull();
        assertThat(WireString.validate("\\x0"))
                .isEqualTo("\\x escape requires two hex digits at position 0");
    }
}
