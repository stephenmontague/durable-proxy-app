import { describe, expect, it } from "vitest";
import { validateCatalog, validateCatalogEntry } from "./validate";
import type { CatalogEntryDto } from "./types";

// Parity vectors mirroring CatalogValidatorTest.java — error strings must match the Java
// CatalogValidator character-for-character, since the UI and the workflow validate the same
// catalog and an operator compares the inline error with what the workflow would reject.

function entry(overrides: Partial<CatalogEntryDto>): CatalogEntryDto {
  return {
    type: "COMMAND_RESULT",
    direction: "EDGE_TO_CLOUD",
    codec: "json",
    cloudEndpoint: "/api/command-result",
    businessIdField: "commandId",
    ...overrides,
  };
}

describe("validateCatalogEntry", () => {
  it("valid EDGE_TO_CLOUD entry has no errors", () => {
    expect(validateCatalogEntry(entry({}))).toEqual([]);
  });

  it("CLOUD_TO_EDGE needs no cloudEndpoint", () => {
    expect(
      validateCatalogEntry(
        entry({ type: "DEVICE_COMMAND", direction: "CLOUD_TO_EDGE", cloudEndpoint: null }),
      ),
    ).toEqual([]);
  });

  it("blank type is rejected", () => {
    expect(validateCatalogEntry(entry({ type: "  " }))).toContain(
      "message type name must not be blank",
    );
  });

  it("unknown direction is rejected", () => {
    expect(
      validateCatalogEntry(entry({ type: "X", direction: "SIDEWAYS" as never })),
    ).toContain("message type X: unknown direction 'SIDEWAYS' (expected CLOUD_TO_EDGE or EDGE_TO_CLOUD)");
  });

  it("unknown codec is rejected with the sorted available list", () => {
    expect(
      validateCatalogEntry(entry({ type: "X", codec: "yaml" as never })),
    ).toContain("message type X: unknown codec 'yaml', available: [json, raw, xml]");
  });

  it("EDGE_TO_CLOUD without cloudEndpoint is rejected", () => {
    expect(
      validateCatalogEntry(entry({ type: "X", cloudEndpoint: "  " })),
    ).toContain("message type X: EDGE_TO_CLOUD type requires a cloudEndpoint");
  });
});

describe("validateCatalog", () => {
  it("empty catalog is rejected", () => {
    expect(validateCatalog([])).toContain("catalog must define at least one message type");
  });

  it("duplicate types are rejected", () => {
    const dup = entry({ type: "DUP", direction: "CLOUD_TO_EDGE", cloudEndpoint: null });
    expect(validateCatalog([dup, { ...dup }])).toContain("duplicate message type: DUP");
  });

  it("a clean multi-entry catalog passes", () => {
    expect(
      validateCatalog([
        entry({ type: "ORDER_PUSH", direction: "CLOUD_TO_EDGE", cloudEndpoint: null }),
        entry({ type: "ORDER_ACK", direction: "EDGE_TO_CLOUD", cloudEndpoint: "/api/order-ack" }),
      ]),
    ).toEqual([]);
  });
});
