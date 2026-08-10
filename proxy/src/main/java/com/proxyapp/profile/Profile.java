package com.proxyapp.profile;

import com.proxyapp.routing.MessageCatalog;
import com.proxyapp.routing.model.DeviceTemplate;

import java.util.List;

/**
 * A bundle of domain config: message catalog + device templates. Everything domain-specific
 * lives here; the core only sees opaque types and channels.
 */
public interface Profile {

    String name();

    MessageCatalog catalog();

    List<DeviceTemplate> deviceTemplates();
}
