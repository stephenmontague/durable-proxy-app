package com.proxyapp.routing;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Escape syntax for TCP wire-protocol strings (delimiters, ack/nak templates): a way to
 * write control bytes in plain printable config. Mirrored byte-for-byte by the
 * management UI's {@code lib/wire-string.ts} — grammar AND error message text must stay
 * identical, because operators compare the UI's inline errors with the control
 * workflow's {@code lastError}.
 *
 * <p>Grammar: printable ASCII (0x20–0x7E) literals, except {@code \} starts an escape
 * ({@code \\ \r \n \t \< \xHH}) and {@code <} starts a named control token
 * ({@code <STX>}, {@code <VT>}, {@code <FS>}, … — the full C0 set plus {@code <DEL>}).
 * A literal {@code <} is written {@code \<}. Bytes map 1:1 via ISO-8859-1.
 *
 * <p>Pure and deterministic — safe inside workflow signal handlers (via ConfigValidator).
 */
public final class WireString {

    private static final Map<String, Character> TOKENS = new LinkedHashMap<>();

    static {
        String[] c0 = {"NUL", "SOH", "STX", "ETX", "EOT", "ENQ", "ACK", "BEL",
                "BS", "TAB", "LF", "VT", "FF", "CR", "SO", "SI",
                "DLE", "DC1", "DC2", "DC3", "DC4", "NAK", "SYN", "ETB",
                "CAN", "EM", "SUB", "ESC", "FS", "GS", "RS", "US"};
        for (int i = 0; i < c0.length; i++) {
            TOKENS.put(c0[i], (char) i);
        }
        TOKENS.put("DEL", (char) 0x7F);
    }

    private WireString() {
    }

    /** Decoded bytes (ISO-8859-1). @throws IllegalArgumentException on syntax errors. */
    public static byte[] decode(String text) {
        return decodeToString(text).getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * Decoded form as a String of chars 0–255 — used for reply templates so
     * {@code {activityId}}/{@code {reason}} substitution can stay string-level.
     */
    public static String decodeToString(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\') {
                if (i + 1 >= text.length()) {
                    throw new IllegalArgumentException("dangling escape at end of input");
                }
                char e = text.charAt(i + 1);
                switch (e) {
                    case '\\' -> out.append('\\');
                    case 'r' -> out.append('\r');
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case '<' -> out.append('<');
                    case 'x' -> {
                        if (i + 4 > text.length()
                                || !isHex(text.charAt(i + 2)) || !isHex(text.charAt(i + 3))) {
                            throw new IllegalArgumentException(
                                    "\\x escape requires two hex digits at position " + i);
                        }
                        out.append((char) Integer.parseInt(text.substring(i + 2, i + 4), 16));
                        i += 2; // extra advance for the two hex digits
                    }
                    default -> throw new IllegalArgumentException(
                            "unknown escape '\\" + e + "' at position " + i);
                }
                i += 2;
            } else if (c == '<') {
                // Window of 7 name chars: longest real token is 3, but a slightly longer
                // unknown name should say "unknown token", not "unterminated".
                int close = text.indexOf('>', i + 1);
                if (close < 0 || close > i + 8) {
                    throw new IllegalArgumentException(
                            "unterminated token starting at position " + i);
                }
                String name = text.substring(i + 1, close);
                Character b = TOKENS.get(name);
                if (b == null) {
                    throw new IllegalArgumentException(
                            "unknown token '<" + name + ">' at position " + i);
                }
                out.append((char) b.charValue());
                i = close + 1;
            } else if (c >= 0x20 && c <= 0x7E) {
                out.append(c);
                i++;
            } else {
                throw new IllegalArgumentException(
                        "unsupported character at position " + i + " (use \\xNN or a <TOKEN>)");
            }
        }
        return out.toString();
    }

    /** Validator-friendly form: the error message, or null when the string parses. */
    public static String validate(String text) {
        try {
            decodeToString(text);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
