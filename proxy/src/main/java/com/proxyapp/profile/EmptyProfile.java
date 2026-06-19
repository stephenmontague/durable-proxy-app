package com.proxyapp.profile;

import com.proxyapp.routing.model.DeviceTemplate;
import com.proxyapp.routing.MessageCatalog;

import java.util.List;

/**
 * The default profile for a fresh install: nothing pre-configured. The proxy boots with an empty
 * catalog and no device templates; the operator defines every message type and device through the
 * UI, and that config persists in the control workflow (Temporal stays the source of truth). This is
 * the production model — an install is a blank slate, not a seeded demo. Swap to {@link DeviceFleetProfile}
 * (proxy.profile=device-fleet) for the legacy reference fleet.
 */
public final class EmptyProfile implements Profile {

    public static final String NAME = "empty";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public MessageCatalog catalog() {
        return new MessageCatalog(List.of());
    }

    @Override
    public List<DeviceTemplate> deviceTemplates() {
        return List.of();
    }
}
