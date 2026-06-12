import type { CatalogEntryDto } from "@/lib/types";

// The reference device-fleet catalog, mirroring com.proxyapp.profile.DeviceFleetProfile. Used by
// the Catalog page's "Import starter profile" action to seed a fresh (or pre-Part-3) install. It's
// only a starting point — operators edit freely afterward, and can define entirely different types
// for any domain. Keep in sync with DeviceFleetProfile.catalog() so an import matches what the
// proxy boots with.
export const DEVICE_FLEET_CATALOG: CatalogEntryDto[] = [
  { type: "DEVICE_COMMAND", direction: "CLOUD_TO_EDGE", codec: "json", cloudEndpoint: null, businessIdField: "commandId" },
  { type: "COMMAND_RESULT", direction: "EDGE_TO_CLOUD", codec: "json", cloudEndpoint: "/api/command-result", businessIdField: "commandId" },
  { type: "CONFIG_UPDATE", direction: "CLOUD_TO_EDGE", codec: "json", cloudEndpoint: null, businessIdField: "configId" },
  { type: "CONFIG_ACK", direction: "EDGE_TO_CLOUD", codec: "json", cloudEndpoint: "/api/config-ack", businessIdField: "configId" },
  { type: "REPORT_REQUEST", direction: "CLOUD_TO_EDGE", codec: "json", cloudEndpoint: null, businessIdField: "reportId" },
  { type: "REPORT_UPLOAD", direction: "EDGE_TO_CLOUD", codec: "json", cloudEndpoint: "/api/report-upload", businessIdField: "reportId" },
];
