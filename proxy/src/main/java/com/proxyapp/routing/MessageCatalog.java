package com.proxyapp.routing;
import com.proxyapp.routing.model.CatalogEntry;
import com.proxyapp.routing.model.MessageType;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** The loaded profile's message catalog: every type the install can route, keyed by name. */
public final class MessageCatalog {

    private final Map<MessageType, CatalogEntry> entries;

    public MessageCatalog(Collection<CatalogEntry> entries) {
        this.entries = entries.stream().collect(Collectors.toMap(
                CatalogEntry::type, e -> e, (a, b) -> {
                    throw new IllegalArgumentException("duplicate catalog entry for " + a.type());
                }, LinkedHashMap::new));
    }

    public Optional<CatalogEntry> entry(MessageType type) {
        return Optional.ofNullable(entries.get(type));
    }

    public CatalogEntry require(MessageType type) {
        CatalogEntry e = entries.get(type);
        if (e == null) {
            throw new IllegalArgumentException("unknown message type: " + type);
        }
        return e;
    }

    public Collection<CatalogEntry> entries() {
        return entries.values();
    }

    /** type name -> direction, the slim deterministic view shipped into the control workflow. */
    public Map<String, String> typeDirections() {
        Map<String, String> out = new LinkedHashMap<>();
        entries.forEach((t, e) -> out.put(t.value(), e.direction().name()));
        return out;
    }
}
