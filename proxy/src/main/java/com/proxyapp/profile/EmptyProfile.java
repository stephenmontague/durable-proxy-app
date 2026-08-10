package com.proxyapp.profile;

import com.proxyapp.routing.MessageCatalog;
import com.proxyapp.routing.model.DeviceTemplate;

import java.util.List;

/**
 * The default profile: empty catalog, no device templates. Everything is defined at runtime through
 * the control workflow, which is then the source of truth. Set {@code proxy.profile} to pin a
 * catalog at build time instead — see {@link DeviceFleetProfile}.
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
