import type { CatalogEntryDto } from "@/lib/types";

/**
 * A short, editable starting payload for dispatching a message type, shaped to the type's codec so
 * it lands on the wire correctly (the proxy encodes the codec's payload as-is). The business id is
 * embedded where the codec reads it (businessIdField, default "id"), keeping the sample self-consistent.
 * The operator edits this before dispatching — it's a starting point, not a fixed message.
 */
export function samplePayload(entry: CatalogEntryDto, businessId: string): string {
  const idField = entry.businessIdField?.trim() || "id";
  switch (entry.codec) {
    case "json":
      return JSON.stringify({ [idField]: businessId, value: "..." }, null, 2);
    case "xml": {
      const el = xmlTag(entry.type);
      return `<${el}><${idField}>${businessId}</${idField}><value>...</value></${el}>`;
    }
    case "raw":
    default:
      // Opaque/delimited payloads (CSV, fixed-width, vendor blobs) — a delimited line to edit.
      return `${entry.type},${businessId},...`;
  }
}

/** A safe XML element name derived from the message type. */
function xmlTag(type: string): string {
  return type.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "") || "message";
}
