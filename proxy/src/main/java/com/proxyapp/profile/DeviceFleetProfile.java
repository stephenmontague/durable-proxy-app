package com.proxyapp.profile;

import com.proxyapp.routing.MessageCatalog;
import com.proxyapp.routing.model.CatalogEntry;
import com.proxyapp.routing.model.ChannelKind;
import com.proxyapp.routing.model.DeviceTemplate;
import com.proxyapp.routing.model.DeviceTemplate.TemplateBinding;
import com.proxyapp.routing.model.Direction;
import com.proxyapp.routing.model.MessageType;
import com.proxyapp.routing.model.Transport;

import java.util.List;

/**
 * An example {@link Profile}, modelling a cloud platform that manages a fleet of on-prem edge
 * devices (gateways, controllers, sensors). It exists to show what a profile supplies — a message
 * catalog naming every type the install can route, plus device templates that pre-fill a typical
 * channel layout — and is not part of the core.
 *
 * <p>Use it as a template: write a profile for your own domain, register it in
 * {@link ProfileRegistry}, and select it with {@code proxy.profile}. Nothing outside this package
 * knows what the types mean, so the same proxy connects any cloud-side system to any on-prem one.
 * A fresh install that configures everything through the control API needs no profile at all —
 * see {@link EmptyProfile}.
 */
public final class DeviceFleetProfile implements Profile {

    public static final String NAME = "device-fleet";

    public static final MessageType DEVICE_COMMAND = MessageType.of("DEVICE_COMMAND");
    public static final MessageType COMMAND_RESULT = MessageType.of("COMMAND_RESULT");
    public static final MessageType CONFIG_UPDATE = MessageType.of("CONFIG_UPDATE");
    public static final MessageType CONFIG_ACK = MessageType.of("CONFIG_ACK");
    public static final MessageType REPORT_REQUEST = MessageType.of("REPORT_REQUEST");
    public static final MessageType REPORT_UPLOAD = MessageType.of("REPORT_UPLOAD");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public MessageCatalog catalog() {
        return new MessageCatalog(List.of(
                new CatalogEntry(DEVICE_COMMAND, Direction.CLOUD_TO_EDGE, "json", null, "commandId"),
                new CatalogEntry(COMMAND_RESULT, Direction.EDGE_TO_CLOUD, "json", "/api/command-result", "commandId"),
                new CatalogEntry(CONFIG_UPDATE, Direction.CLOUD_TO_EDGE, "json", null, "configId"),
                new CatalogEntry(CONFIG_ACK, Direction.EDGE_TO_CLOUD, "json", "/api/config-ack", "configId"),
                new CatalogEntry(REPORT_REQUEST, Direction.CLOUD_TO_EDGE, "json", null, "reportId"),
                new CatalogEntry(REPORT_UPLOAD, Direction.EDGE_TO_CLOUD, "json", "/api/report-upload", "reportId")));
    }

    @Override
    public List<DeviceTemplate> deviceTemplates() {
        // A typical edge gateway: commands over HTTP, config pushes over raw TCP, batch reports via FTP.
        return List.of(new DeviceTemplate("device-fleet-standard", "Standard edge gateway",
                List.of(
                        new TemplateBinding(DEVICE_COMMAND, Transport.HTTP, ChannelKind.PATH, "/commands", 0),
                        new TemplateBinding(COMMAND_RESULT, Transport.HTTP, ChannelKind.PATH, "/command-result", 0),
                        new TemplateBinding(CONFIG_UPDATE, Transport.TCP, ChannelKind.PORT, null, 0),
                        new TemplateBinding(CONFIG_ACK, Transport.TCP, ChannelKind.PORT, null, 1),
                        new TemplateBinding(REPORT_REQUEST, Transport.FTP, ChannelKind.FOLDER, "report-requests", 0),
                        new TemplateBinding(REPORT_UPLOAD, Transport.FTP, ChannelKind.FOLDER, "report-uploads", 0))));
    }
}
