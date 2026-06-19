import { describe, expect, it } from "vitest";
import { samplePayload } from "./sample-payload";
import type { CatalogEntryDto, CodecName } from "./types";

function entry(codec: CodecName, businessIdField: string | null = null): CatalogEntryDto {
  return {
    type: "DIVERT_COMMAND",
    direction: "CLOUD_TO_EDGE",
    codec,
    cloudEndpoint: null,
    businessIdField,
  };
}

describe("samplePayload", () => {
  it("json embeds the business id under businessIdField and is valid JSON", () => {
    const parsed = JSON.parse(samplePayload(entry("json", "commandId"), "CMD-1"));
    expect(parsed.commandId).toBe("CMD-1");
  });

  it("xml wraps the business id in the configured element", () => {
    const p = samplePayload(entry("xml", "id"), "SP-7");
    expect(p).toContain("<id>SP-7</id>");
    expect(p).toContain("<divert-command>");
  });

  it("raw produces a delimited line containing the type and id", () => {
    const p = samplePayload(entry("raw"), "DIV-9");
    expect(p).toBe("DIVERT_COMMAND,DIV-9,...");
  });

  it("falls back to 'id' when no businessIdField is set", () => {
    const parsed = JSON.parse(samplePayload(entry("json"), "X-1"));
    expect(parsed.id).toBe("X-1");
  });
});
