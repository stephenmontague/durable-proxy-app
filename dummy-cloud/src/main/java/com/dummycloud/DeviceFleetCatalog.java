package com.dummycloud;

import java.util.Map;

/**
 * Cloud-side copy of the device-fleet reference catalog: outbound type -> business-id field.
 * The cloud app owns the catalog (layer 1 of the 3-layer config); the proxy ships the same
 * profile.
 */
public final class DeviceFleetCatalog {

    public static final String DEVICE_COMMAND = "DEVICE_COMMAND";
    public static final String CONFIG_UPDATE = "CONFIG_UPDATE";
    public static final String REPORT_REQUEST = "REPORT_REQUEST";

    public static final Map<String, String> OUTBOUND_BUSINESS_ID_FIELDS = Map.of(
            DEVICE_COMMAND, "commandId",
            CONFIG_UPDATE, "configId",
            REPORT_REQUEST, "reportId");

    private DeviceFleetCatalog() {
    }
}
