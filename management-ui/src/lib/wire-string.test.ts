import { describe, expect, it } from "vitest";
import { decodeWireString, validateWireString } from "./wire-string";

// Vectors copied verbatim from the Java spec test (WireStringTest.java).
// If these change, change them there too — the two parsers must stay identical.

describe("decodeWireString", () => {
  it("plain printable text passes through", () => {
    expect(decodeWireString("ACK ok")).toEqual([65, 67, 75, 32, 111, 107]);
  });

  it("simple escapes", () => {
    expect(decodeWireString("\\r\\n\\t\\\\")).toEqual([0x0d, 0x0a, 0x09, 0x5c]);
  });

  it("hex escapes", () => {
    expect(decodeWireString("\\x0b")).toEqual([0x0b]);
    expect(decodeWireString("\\x1C\\x0D")).toEqual([0x1c, 0x0d]);
    expect(decodeWireString("\\xFF")).toEqual([0xff]);
  });

  it("named tokens", () => {
    expect(decodeWireString("<VT>")).toEqual([0x0b]);
    expect(decodeWireString("<FS><CR>")).toEqual([0x1c, 0x0d]);
    expect(decodeWireString("<STX>data<ETX>")).toEqual([0x02, 100, 97, 116, 97, 0x03]);
    expect(decodeWireString("<ACK>")).toEqual([0x06]);
    expect(decodeWireString("<NAK>")).toEqual([0x15]);
    expect(decodeWireString("<NUL>")).toEqual([0x00]);
    expect(decodeWireString("<DEL>")).toEqual([0x7f]);
  });

  it("escaped angle bracket is literal", () => {
    expect(decodeWireString("\\<tag>")).toEqual([60, 116, 97, 103, 62]);
  });

  it("bare close bracket is literal", () => {
    expect(decodeWireString("a>b")).toEqual([97, 62, 98]);
  });

  it("dangling backslash", () => {
    expect(() => decodeWireString("abc\\")).toThrow("dangling escape at end of input");
  });

  it("unknown escape", () => {
    expect(() => decodeWireString("a\\qb")).toThrow("unknown escape '\\q' at position 1");
  });

  it("hex escape needs two digits", () => {
    expect(() => decodeWireString("\\x0")).toThrow(
      "\\x escape requires two hex digits at position 0",
    );
    expect(() => decodeWireString("ab\\xZZ")).toThrow(
      "\\x escape requires two hex digits at position 2",
    );
  });

  it("unterminated token", () => {
    expect(() => decodeWireString("<STX")).toThrow("unterminated token starting at position 0");
    expect(() => decodeWireString("a<longername>")).toThrow(
      "unterminated token starting at position 1",
    );
  });

  it("unknown token", () => {
    expect(() => decodeWireString("<XYZ>")).toThrow("unknown token '<XYZ>' at position 0");
  });

  it("raw control character rejected", () => {
    expect(() => decodeWireString("abcd")).toThrow(
      "unsupported character at position 2 (use \\xNN or a <TOKEN>)",
    );
    expect(() => decodeWireString("é")).toThrow(
      "unsupported character at position 0 (use \\xNN or a <TOKEN>)",
    );
  });
});

describe("validateWireString", () => {
  it("returns error or null", () => {
    expect(validateWireString("<VT>ok")).toBeNull();
    expect(validateWireString("\\x0")).toBe("\\x escape requires two hex digits at position 0");
  });
});
