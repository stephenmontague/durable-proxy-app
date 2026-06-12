// Mirror of the proxy's com.proxyapp.routing.WireString — grammar AND error message
// text must stay identical (operators compare UI errors with the workflow's lastError).
// The shared test vectors live in WireStringTest.java and wire-string.test.ts.

const TOKENS: Record<string, number> = {};
[
  "NUL", "SOH", "STX", "ETX", "EOT", "ENQ", "ACK", "BEL",
  "BS", "TAB", "LF", "VT", "FF", "CR", "SO", "SI",
  "DLE", "DC1", "DC2", "DC3", "DC4", "NAK", "SYN", "ETB",
  "CAN", "EM", "SUB", "ESC", "FS", "GS", "RS", "US",
].forEach((name, i) => {
  TOKENS[name] = i;
});
TOKENS["DEL"] = 0x7f;

function isHex(c: string): boolean {
  return /[0-9a-fA-F]/.test(c);
}

/** Decode to bytes (0–255 values). Throws Error with the deterministic message. */
export function decodeWireString(text: string): number[] {
  const out: number[] = [];
  let i = 0;
  while (i < text.length) {
    const c = text[i];
    const code = text.charCodeAt(i);
    if (c === "\\") {
      if (i + 1 >= text.length) {
        throw new Error("dangling escape at end of input");
      }
      const e = text[i + 1];
      switch (e) {
        case "\\":
          out.push(0x5c);
          break;
        case "r":
          out.push(0x0d);
          break;
        case "n":
          out.push(0x0a);
          break;
        case "t":
          out.push(0x09);
          break;
        case "<":
          out.push(0x3c);
          break;
        case "x": {
          if (i + 4 > text.length || !isHex(text[i + 2]) || !isHex(text[i + 3])) {
            throw new Error(`\\x escape requires two hex digits at position ${i}`);
          }
          out.push(parseInt(text.slice(i + 2, i + 4), 16));
          i += 2; // extra advance for the two hex digits
          break;
        }
        default:
          throw new Error(`unknown escape '\\${e}' at position ${i}`);
      }
      i += 2;
    } else if (c === "<") {
      // Window of 7 name chars: longest real token is 3, but a slightly longer
      // unknown name should say "unknown token", not "unterminated".
      const close = text.indexOf(">", i + 1);
      if (close < 0 || close > i + 8) {
        throw new Error(`unterminated token starting at position ${i}`);
      }
      const name = text.slice(i + 1, close);
      const byte = TOKENS[name];
      if (byte === undefined) {
        throw new Error(`unknown token '<${name}>' at position ${i}`);
      }
      out.push(byte);
      i = close + 1;
    } else if (code >= 0x20 && code <= 0x7e) {
      out.push(code);
      i++;
    } else {
      throw new Error(`unsupported character at position ${i} (use \\xNN or a <TOKEN>)`);
    }
  }
  return out;
}

/** Validator-friendly form: the error message, or null when the string parses. */
export function validateWireString(text: string): string | null {
  try {
    decodeWireString(text);
    return null;
  } catch (e) {
    return e instanceof Error ? e.message : String(e);
  }
}
