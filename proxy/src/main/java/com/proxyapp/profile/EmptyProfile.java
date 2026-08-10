package com.proxyapp.profile;

import com.proxyapp.routing.MessageCatalog;
import com.proxyapp.routing.model.DeviceTemplate;

import java.util.List;

/**
 * The default profile: nothing pre-configured. The proxy boots with an empty catalog and no device
 * templates, and every message type and device is defined at runtime through the control workflow,
 * which is where that config then lives (Temporal stays the source of truth).
 *
 * <p>This is the normal choice for a real install — an install is a blank slate its operator fills
 * in, not something shipped pre-populated. Select a built-in profile instead with
 * {@code proxy.profile} when you want the catalog fixed at build time; see
 * {@link DeviceFleetProfile} for a worked example.
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
