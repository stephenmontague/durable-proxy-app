package com.proxyapp.profile;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Built-in profiles, selected by the {@code proxy.profile} bootstrap property. */
public final class ProfileRegistry {

    private final Map<String, Profile> profiles;

    public ProfileRegistry(List<Profile> profiles) {
        this.profiles = profiles.stream()
                .collect(Collectors.toMap(Profile::name, Function.identity()));
    }

    public static ProfileRegistry builtIn() {
        return new ProfileRegistry(List.of(new DeviceFleetProfile()));
    }

    public Profile require(String name) {
        Profile p = profiles.get(name);
        if (p == null) {
            throw new IllegalArgumentException(
                    "unknown profile '" + name + "', available: " + profiles.keySet());
        }
        return p;
    }
}
